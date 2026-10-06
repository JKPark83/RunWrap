package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/// 자연어 대회 기록 파서 (이슈 #35) — "작년 10월 춘마 하프 1시간 45분" 같은 문장을
/// Apple Intelligence(Foundation Models)의 @Generable 제약 디코딩으로 구조화한다.
///
/// 역할 분담이 핵심이다: 모델은 자연어 → 필드 분해까지만 하고, 초 환산·종목 매칭·
/// 상대 날짜 해석은 전부 순수 코드(`interpret`)가 한다 — "숫자 계산은 모델에 맡기지
/// 않는다". 결과는 수동 입력 폼을 채우는 데만 쓰고 바로 저장하지 않는다:
/// 3B 온디바이스 모델의 오독은 사용자가 저장 전에 잡는다.
///
/// (Android: Foundation Models 경로(isAvailable·parse)는 iOS 전용이라 옮기지 않는다 —
/// 순수 해석 `interpret`만 옮겨 완주증 OCR(FinisherCertificateReader)이 쓴다)
object RaceResultParser {
    /// 파싱 결과 — 폼 프리필 재료. 확신 없는 필드는 nil로 남겨 기존 폼 값을 유지한다
    data class Parsed(
        val race: RaceDistance?,
        val timeSec: Double?,
        val date: Instant?,
    )

    /// 모델 출력 → 폼 값 해석 (순수 로직 — 테스트는 이 함수만 겨눈다):
    /// - 초 환산: 시·분·초를 코드로 합산한다 (모델에 산술을 맡기지 않는다)
    /// - 종목 매칭: 공인 거리와 ±15% 안이면 해당 종목, 밖이면 nil (폼 기본값 유지)
    /// - 날짜: 연도가 없으면 그 월·일의 가장 가까운 과거로 해석한다 —
    ///   "작년 가을(10월)"은 지금이 8월이면 자연히 작년 10월이 된다.
    ///   미래 연도나 최근 2년 밖 연도가 오면 오독으로 보고 버린다 (두 자리는 2000년대).
    /// - 범위 검사: 시 0~7·분·초 0~59(휠 범위) 밖이나 음수인 필드는 버린다 (이슈 #93).
    ///   단 시가 없으면 "105분"처럼 분만으로 말한 경우를 살리기 위해 분은 0~479까지 허용한다.
    /// - 세 필드 모두 확신이 없으면 nil — 화면이 "읽지 못했다"를 안내한다
    fun interpret(distanceKm: Double?, hours: Int?, minutes: Int?, seconds: Int?,
                  year: Int?, month: Int?, day: Int?, now: Instant, zone: ZoneId): Parsed? {
        val race: RaceDistance? = distanceKm?.let { km ->
            if (!(km > 0)) return@let null
            RaceDistance.entries.firstOrNull { abs(it.km - km) / it.km <= 0.15 }
        }
        // 시·분·초 범위 검사 (이슈 #93) — 입력 휠 범위(0~7시간, 0~59분·초) 밖이거나 음수인
        // 필드는 오독으로 보고 그 필드만 버린다. 곱셈은 검사 뒤에 해 거대값의 오버플로 트랩을 막는다.
        // 시간 없이 분만 오면("105분") 분이 최상위 단위라 휠 상한(8시간 미만)까지 허용한다.
        val validHours = hours?.takeIf { it in 0..7 }
        val validMinutes = minutes?.takeIf { it in 0..(if (hours == null) 479 else 59) }
        val validSeconds = seconds?.takeIf { it in 0..59 }
        val totalSec = (validHours ?: 0) * 3_600 + (validMinutes ?: 0) * 60 + (validSeconds ?: 0)
        val timeSec: Double? = if (totalSec > 0) totalSec.toDouble() else null
        val date: Instant? = month?.let { month ->
            if (month !in 1..12) return@let null
            if (year != null) {
                // 두 자리 연도('25년')는 2000년대로 본다. 대회 기록 최대 나이(약 2년) 밖의
                // 연도는 오독이다 — 서기 25년이 date <= now를 통과하던 구멍 (이슈 #93)
                val fullYear = if (year in 0..99) year + 2_000 else year
                val currentYear = now.atZone(zone).year
                if (fullYear !in (currentYear - 2)..currentYear) return@let null
                // 그 달의 실제 일수로 검증 — '9월 31일'이 10월 1일로 정규화되지 않게.
                // 그 달에 없는 일이면 일을 모를 때처럼 15일로 둔다
                val validDays = 1..YearMonth.of(fullYear, month).lengthOfMonth()
                val resolvedDay = day?.takeIf { it in validDays } ?: 15
                val date = LocalDate.of(fullYear, month, resolvedDay).atStartOfDay(zone).toInstant()
                if (!(date <= now)) return@let null
                return@let date
            }
            // 일을 모르면 15일 — 예측 재료로는 월 해상도면 충분하다 (VO₂max 창이 ±14일).
            // 연도가 없으면 31일까지 받고, 그 달에 없는 일은 nextDate가 처리한다
            val resolvedDay = day?.takeIf { it in 1..31 } ?: 15
            previousMatch(month, resolvedDay, now, zone)
        }
        if (race == null && timeSec == null && date == null) return null
        return Parsed(race = race, timeSec = timeSec, date = date)
    }

    /// Swift `calendar.nextDate(after: now, matching: {month, day}, matchingPolicy: .nextTime,
    /// direction: .backward)` — now보다 엄격히 앞선 가장 가까운 그 월·일 자정.
    /// 그 해에 없는 날(2월 30일·4월 31일·평년 2월 29일)은 .nextTime대로 다음 달 1일 자정이 후보다
    /// (실측: Swift probe — 2026-08-26에 2/30 → 2026-03-01, now가 후보 자정과 같으면 전년도)
    private fun previousMatch(month: Int, day: Int, now: Instant, zone: ZoneId): Instant {
        fun candidate(year: Int): Instant {
            val yearMonth = YearMonth.of(year, month)
            val date = if (day <= yearMonth.lengthOfMonth()) yearMonth.atDay(day)
                       else yearMonth.plusMonths(1).atDay(1)
            return date.atStartOfDay(zone).toInstant()
        }
        val currentYear = now.atZone(zone).year
        val thisYear = candidate(currentYear)
        // 전년도 후보는 항상 now보다 앞선다 (12월은 31일까지 있어 다음 해로 넘어가지 않는다)
        return if (thisYear < now) thisYear else candidate(currentYear - 1)
    }
}
