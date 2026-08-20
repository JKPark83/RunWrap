package com.jkpark.runwrap.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToLong

/// 수치 포맷 공통 헬퍼 — iOS `Theme.swift`의 `Format` 이식.
/// 주(週) 라벨은 ISO 주차 번호("Week 33")를 노출하지 않고 "8월 2째주" 표기를 쓴다.
/// 숫자 포맷은 기기 로케일에 흔들리지 않게 Locale을 고정한다.
object Format {
    /// "1:52:34" 또는 "48:10"
    fun duration(seconds: Double): String {
        val s = seconds.roundToLong()
        return if (s >= 3_600) {
            "%d:%02d:%02d".format(Locale.ROOT, s / 3_600, (s % 3_600) / 60, s % 60)
        } else {
            "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
        }
    }

    /// "5′20″"
    fun pace(secPerKm: Double): String {
        val s = secPerKm.roundToLong()
        return "${s / 60}′${"%02d".format(Locale.ROOT, s % 60)}″"
    }

    /// "5′20″/km"
    fun paceKm(secPerKm: Double): String = pace(secPerKm) + "/km"

    fun km(value: Double): String = "%.1f".format(Locale.ROOT, value)

    /// 걷뛰 분 — 정수는 "2", 반 분은 "3.5". 사다리에 0.5분 단위가 있어 나눠 찍는다 (WalkRunEngine)
    fun walkRunMinutes(value: Double): String =
        if (value == round(value)) "%.0f".format(Locale.ROOT, value)
        else "%.1f".format(Locale.ROOT, value)

    /// "4,120" — 천 단위 구분 정수 (칼로리 카드)
    fun kcal(value: Double): String = NumberFormat.getIntegerInstance(Locale.KOREA).format(value)

    /// "8월 2째주" — 차트 주 라벨. 두 달에 걸친 주는 그 주의 목요일이 속한 달로
    /// 정하고(ISO 8601 관행), 째주 순번도 목요일 날짜 기준(1–7일 → 1째주)이다.
    /// withYear를 켜면 "2026년 8월 2째주" — 연도도 목요일 기준(연말·연초에 걸친 주 대응).
    /// weekStart는 월요일 날짜 — 시각·시간대 판정은 호출부(스토어 계층, KST 고정)가 끝낸다.
    fun weekLabel(weekStart: LocalDate, withYear: Boolean = false): String {
        val thursday = weekStart.plusDays(3)
        val label = "${thursday.monthValue}월 ${(thursday.dayOfMonth + 6) / 7}째주"
        return if (withYear) "${thursday.year}년 $label" else label
    }
}
