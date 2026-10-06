package com.jkpark.runwrap.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

/// iOS Theme.swift 중 순수 로직 부분 — `RRTone`(톤 값·라벨)과 `Format`(수치·날짜 포맷)만 엔진에 둔다.
/// 색 토큰(`RR`)·톤 색 매핑·ToneBadge·Eyebrow 등 SwiftUI 쪽은 :app ui/Theme.kt가 맡는다.

/// 카드 상태 톤 4가지 — 시안의 배지/강조색 매핑 (과부하·주의·유지·개선).
/// String·Codable은 위젯 스냅샷(App Group JSON)에 톤을 싣기 위해서다 (이슈 #181)
@kotlinx.serialization.Serializable
enum class RRTone {
    overload, caution, steady, improving;

    val label: String
        get() = when (this) {
            overload -> "과부하 구간"
            caution -> "주의 구간"
            steady -> "유지 중"
            improving -> "좋아지는 중"
        }
}

/// 수치 포맷 공통 헬퍼
object Format {
    /// "1:52:34" 또는 "48:10"
    fun duration(seconds: Double): String {
        val s = seconds.swiftRoundedInt()
        return if (s >= 3600)
            String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /// "5′20″"
    fun pace(secPerKm: Double): String {
        val s = secPerKm.swiftRoundedInt()
        return "${s / 60}′${String.format(Locale.ROOT, "%02d", s % 60)}″"
    }

    /// "5′20″/km"
    fun paceKm(secPerKm: Double): String = pace(secPerKm) + "/km"

    fun km(value: Double): String = fmt(value, 1)

    /// 걷뛰 분 — 정수는 "2", 반 분은 "3.5". 사다리에 0.5분 단위가 있어 나눠 찍는다.
    /// 엔진의 `headline`과 카드의 metric이 같은 표기를 쓰도록 여기 둔다 (WalkRunEngine)
    fun walkRunMinutes(value: Double): String =
        if (value == value.swiftRounded()) fmt(value, 0) else fmt(value, 1)

    /// "4,120" — 천 단위 구분 정수 (칼로리 카드)
    ///
    /// iOS는 NumberFormatter(.decimal, 소수 0자리, 기본 halfEven, 기기 로케일)다. 한국 사용자(ko_KR) 기준
    /// 출력을 직접 조립한다 — 쉼표 3자리 구분, 음수 부호는 부호 비트(-0.4 → "-0"), 큰 수는 최단 십진 표기 뒤 0 채움.
    /// NaN·무한대는 이 맥(ko_KR)에서 잰 NumberFormatter 출력 그대로다.
    fun kcal(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "+∞" else "-∞"
        val digits = BigDecimal.valueOf(abs(value)).setScale(0, RoundingMode.HALF_EVEN).toPlainString()
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (value.toRawBits() < 0) "-$grouped" else grouped
    }

    /// "8월 2째주" — 차트 주 라벨. 두 달에 걸친 주는 그 주의 목요일이 속한 달로
    /// 정하고(ISO 8601 관행), 째주 순번도 목요일 날짜 기준(1–7일 → 1째주)이다.
    /// withYear를 켜면 "2026년 8월 2째주" — 연도도 목요일 기준(연말·연초에 걸친 주 대응).
    fun weekLabel(weekStart: Instant, zone: ZoneId, withYear: Boolean = false): String {
        val thursday = weekStart.atZone(zone).plusDays(3)
        val month = thursday.monthValue
        val ordinal = (thursday.dayOfMonth + 6) / 7
        val label = "${month}월 ${ordinal}째주"
        return if (withYear) "${thursday.year}년 $label" else label
    }

    /// "9월 30일 오후 3:12" — 날짜·시각 한 줄 표기 (설정의 마지막 iCloud 백업 시각, 이슈 #129).
    /// 기기 로케일과 무관하게 한국어 고정, 시간대는 기기 설정을 따른다
    /// (iOS DateFormatter ko_KR "M월 d일 a h:mm" — JVM·기기 로케일 데이터 차이를 피하려고 직접 조립한다)
    fun monthDayTime(date: Instant, zone: ZoneId): String {
        val t = date.atZone(zone)
        val amPm = if (t.hour < 12) "오전" else "오후"
        val hour12 = if (t.hour % 12 == 0) 12 else t.hour % 12
        return "${t.monthValue}월 ${t.dayOfMonth}일 $amPm $hour12:${String.format(Locale.ROOT, "%02d", t.minute)}"
    }

    /// "이번 주" / "지난주" / "3주 전" — 달력 주(ISO 8601, 월요일 시작) 기준 상대 표기.
    /// 두 날짜 사이의 7일 묶음 수가 아니라 **각자 속한 주의 시작일끼리** 비교한다 —
    /// 그래야 월요일에 본 지난주 일요일 세션이 "이번 주"가 아니라 "지난주"가 된다.
    fun relativeWeek(of: Instant, now: Instant, zone: ZoneId): String {
        val weeks = ChronoUnit.WEEKS.between(isoWeekStart(of, zone), isoWeekStart(now, zone)).toInt()
        return when {
            weeks < 1 -> "이번 주"
            weeks == 1 -> "지난주"
            else -> "${weeks}주 전"
        }
    }
}

/// `Calendar(identifier: .iso8601)` + `dateInterval(of: .weekOfYear, for:)?.start`의 날짜 — 그 시각이 속한 ISO 주(월요일 시작)의 월요일.
/// 같은 대응이 GrowthEngine·WalkRunEngine에도 필요해 엔진 내부 공용으로 둔다.
internal fun isoWeekStart(date: Instant, zone: ZoneId): LocalDate =
    date.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
