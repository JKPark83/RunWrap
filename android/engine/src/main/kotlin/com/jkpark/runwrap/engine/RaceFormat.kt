package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/// 대회 화면 공용 날짜·기간 표기 — 목록·상세가 같이 쓴다.
/// 대회는 전부 국내 개최라 표기도 RaceEngine의 KST 달력을 따른다.
/// (iOS는 `RaceListScreen.swift` 안의 enum이다. Android는 순수 표기라 엔진 모듈로 뺐다.
/// DateFormatter 상수는 같은 결과를 직접 조립하는 함수로 옮긴다 — 로케일 패턴은 기기와 JVM이 다르다)
object RaceFormat {
    /// "M/d"
    fun monthDay(date: Instant): String = kst(date).let { "${it.monthValue}/${it.dayOfMonth}" }

    /// "(E)" — ko_KR 요일
    fun weekdayParen(date: Instant): String = "(${weekday(kst(date))})"

    /// "yyyy년 M월 d일 (E)"
    fun fullDate(date: Instant): String =
        kst(date).let { "${year(it)}년 ${it.monthValue}월 ${it.dayOfMonth}일 (${weekday(it)})" }

    /// "yyyy. M. d."
    fun dotDate(date: Instant): String = kst(date).let { "${year(it)}. ${it.monthValue}. ${it.dayOfMonth}." }

    fun dDay(days: Int): String = if (days == 0) "D-DAY" else "D-$days"

    /// 목록용 "접수 6/1 ~ 8/15" — 한쪽만 알면 그쪽만 표기, 둘 다 모르면 nil (미노출 가드)
    fun registerPeriod(race: Race): String? {
        val start = RaceEngine.day(race.registerStart)?.let(::monthDay)
        val end = RaceEngine.day(race.registerEnd)?.let(::monthDay)
        return when {
            start != null && end != null -> "접수 $start ~ $end"
            start != null -> "접수 $start 시작"
            end != null -> "접수 ~$end 마감"
            else -> null
        }
    }

    /// 상세용 "2026. 3. 26. ~ 2026. 7. 30."
    fun registerPeriodLong(race: Race): String? {
        val start = RaceEngine.day(race.registerStart)?.let(::dotDate)
        val end = RaceEngine.day(race.registerEnd)?.let(::dotDate)
        return when {
            start != null && end != null -> "$start ~ $end"
            start != null -> "$start 시작"
            end != null -> "$end 마감"
            else -> null
        }
    }

    /// 목록 헤더 "8월 12일" — generatedAt(ISO8601)을 못 읽으면 nil
    fun updatedLabel(iso: String): String? {
        val date = iso8601(iso) ?: return null
        return kst(date).let { "${it.monthValue}월 ${it.dayOfMonth}일" }
    }

    /// 자료 신선도 (#146) — generatedAt이 maxDays일보다 오래됐으면 true.
    /// 배치가 며칠째 실패해도 앱이 조용히 옛 목록을 보여주지 않게 한다.
    /// generatedAt은 races 내용이 바뀐 날만 커밋되므로(race-info.yml) 대회 변동이 없는 며칠은
    /// 정상이다 — 3일이면 헛경고가 잦아 7일로 잡았다. 못 읽으면 false — 모르는 걸 경고하지 않는다.
    fun isStale(generatedAt: String, now: Instant, maxDays: Int = 7): Boolean {
        val date = iso8601(generatedAt) ?: return false
        return now.timeIntervalSince1970 - date.timeIntervalSince1970 > (maxDays * 86_400).toDouble()
    }

    /// 목록 헤더 캡션 (#145 #146) — 평소엔 "지금 접수받는 대회 N곳 · 8월 12일 갱신".
    /// 새로고침 실패·자료 오래됨이면 둘째 줄에 존댓말 안내와 자료 날짜를 붙인다.
    fun caption(openCount: Int, updatedAt: String, refreshFailed: Boolean, stale: Boolean): String {
        val count = "지금 접수받는 대회 ${openCount}곳"
        val updated = updatedLabel(updatedAt)
        val notice: String? = when {
            refreshFailed && stale -> "새로 받지 못해 자료가 조금 오래됐어요"
            refreshFailed -> "방금 새로 받진 못했어요"
            stale -> "자료가 조금 오래됐어요"
            else -> null
        }
        if (notice == null) {
            return updated?.let { "$count · $it 갱신" } ?: count
        }
        return "$count\n" + (updated?.let { "$notice · $it 자료" } ?: notice)
    }

    private fun kst(date: Instant): ZonedDateTime = date.atZone(KST)

    /// ko_KR "E" — 월…일
    private fun weekday(date: ZonedDateTime): Char = "월화수목금토일"[date.dayOfWeek.value - 1]

    /// "yyyy" — 4자리 0 채움 (DateFormatter는 999년을 "0999"로 찍는다)
    private fun year(date: ZonedDateTime): String = date.year.toString().padStart(4, '0')

    private const val offset = "([+-])(\\d{1,2})(?::?(\\d{2}))?(?::?(\\d{2}))?"

    /// iOS `ISO8601DateFormatter()` 기본 옵션이 받아들이는 주요 형태 (실측 2026-10-05):
    /// 앞뒤 공백, 한 자리 월·일·시·분·초, 시각 뒤 공백, 시간대 Z·±HH[:MM[:SS]]·UTC/UT/GMT(±오프셋),
    /// 시간대 뒤 잔여 글자는 무시. 날짜 넘침(2월 31일 → 3월 3일)·24시는 다음 날로 넘긴다.
    /// 소수 초·시간대 없음·분 단위까지만·T 대신 공백은 nil
    private val isoPattern = Regex(
        "^\\s*(\\d+)-(\\d+)-(\\d+)T(\\d+):(\\d+):(\\d+)\\s*(?:[Zz]|(?i:UTC|UT|GMT)(?:$offset)?|$offset)"
    )

    private fun iso8601(text: String): Instant? {
        val match = isoPattern.find(text) ?: return null
        val g = match.groupValues
        val n = (1..6).map { g[it].toIntOrNull() ?: return null }
        val year = n[0]; val month = n[1]; val day = n[2]; val hour = n[3]; val minute = n[4]; val second = n[5]
        if (month !in 1..12 || day !in 1..31 || hour !in 0..24 || minute !in 0..59 || second !in 0..59) return null
        val sign = g[7].ifEmpty { g[11] }
        val zoneOffset = try {
            if (sign.isEmpty()) {
                ZoneOffset.UTC
            } else {
                val parts = (if (g[7].isEmpty()) 12..14 else 8..10).map { g[it].ifEmpty { "0" }.toInt() }
                val factor = if (sign == "-") -1 else 1
                ZoneOffset.ofHoursMinutesSeconds(factor * parts[0], factor * parts[1], factor * parts[2])
            }
        } catch (_: java.time.DateTimeException) {
            return null
        }
        return try {
            LocalDate.of(year, month, 1).plusDays((day - 1).toLong()).atStartOfDay()
                .plusHours(hour.toLong()).plusMinutes(minute.toLong()).plusSeconds(second.toLong())
                .toInstant(zoneOffset)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }
}
