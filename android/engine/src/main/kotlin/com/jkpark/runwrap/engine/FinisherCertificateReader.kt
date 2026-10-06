package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.ZoneId
import java.util.regex.Pattern

/// 완주증 OCR (이슈 #192) — 완주증(기록증) 사진에서 종목·기록·날짜를 읽어 대회 기록 폼을 채운다.
///
/// 역할 분담은 자연어 파서(#35)와 같다: Vision은 줄 단위 텍스트까지만, 필드 해석은 순수 코드
/// (`interpret`)가 하고 시·분·초 범위·연도 창·종목 매칭은 `RaceResultParser.interpret`에 위임한다.
/// Vision은 온디바이스라 이미지가 기기 밖으로 나가지 않고, 이미지는 저장하지도 않는다.
/// 결과는 폼 프리필까지만 — 저장은 사용자가 값을 확인하고 누른다 (#35와 같은 규칙).
/// (Android: 텍스트 인식(recognizeLines·read)은 플랫폼 OCR이라 :app 몫이다 — 순수 해석 `interpret`만 옮긴다)
object FinisherCertificateReader {
    /// 줄 텍스트 → 폼 프리필 (순수 로직 — 테스트는 이 함수를 겨눈다). 세 필드 모두 못 읽으면 nil.
    /// 종목·기록이 둘 다 있는데 페이스가 비현실적이면 기록만 버린다 — 구간 기록·페이스 오독 방어
    fun interpret(lines: List<String>, now: Instant, zone: ZoneId): RaceResultParser.Parsed? {
        val time = recordTime(lines)
        val date = raceDate(lines)
        val parsed = RaceResultParser.interpret(
            distanceKm = distanceKm(lines),
            hours = time?.hours, minutes = time?.minutes, seconds = time?.seconds,
            year = date?.year, month = date?.month, day = date?.day, now = now, zone = zone)
            ?: return null
        val race = parsed.race
        val sec = parsed.timeSec
        if (race != null && sec != null && !RaceRecord.isPlausible(timeSec = sec, km = race.km)) {
            return RaceResultParser.Parsed(race = race, timeSec = null, date = parsed.date)
        }
        return parsed
    }

    // MARK: - 종목

    private data class DistancePattern(val pattern: String, val km: Double)

    /// 우선순위 순 종목 패턴 — 우선순위가 줄 순서보다 먼저다 ("서울하프마라톤"은 '마라톤'보다 '하프')
    private val distanceTiers: List<List<DistancePattern>> = listOf(
        // 1. 공인 거리 숫자 표기
        listOf(DistancePattern("""(?<![\d.])42\.(195|2)(?!\d)""", 42.195),
               DistancePattern("""(?<![\d.])21\.(0975|1)(?!\d)""", 21.0975)),
        // 2. 하프 — '마라톤'이 같이 있어도 하프
        listOf(DistancePattern("""하프|half""", 21.0975)),
        // 3. 10K·5K — 앞이 숫자면 제외(15km·2.5km), 뒤가 영문자면 제외(10kg)
        listOf(DistancePattern("""(?<![\d.])10\s?(km|k)(?![a-z])""", 10.0),
               DistancePattern("""(?<![\d.])5\s?(km|k)(?![a-z])""", 5.0)),
        // 4. 풀코스 — 가장 넓은 표현이라 맨 뒤
        listOf(DistancePattern("""풀코스|풀|full|마라톤|marathon""", 42.195)),
    )

    private data class DistanceHit(val tier: Int, val km: Double, val withTime: Boolean)

    /// 풀코스 완주증의 구간 기록표("Half 01:42:10", "10K 00:50:30")에는 종목 라벨이 여럿 찍힌다.
    /// 서로 다른 거리가 둘 이상 보이면 기록이 같이 적힌 줄(구간 행)의 라벨은 버리고,
    /// 대회명처럼 기록 없는 줄에서만 고른다 — 그래야 '서울마라톤' 완주증이 하프로 읽히지 않는다.
    /// 거리가 하나뿐이면("하프 01:45:30") 그대로 쓴다
    private fun distanceKm(lines: List<String>): Double? {
        val hits = mutableListOf<DistanceHit>()
        for ((tierIndex, tier) in distanceTiers.withIndex()) {
            for (line in lines) {
                val hit = tier.firstOrNull { matches(it.pattern, line) }
                if (hit != null) {
                    hits.add(DistanceHit(tierIndex, hit.km, hasTime(line)))
                }
            }
        }
        val distinct = hits.map { it.km }.toSet()
        if (distinct.size > 1 && hits.any { !it.withTime }) {
            hits.removeAll { it.withTime }
        }
        // minByOrNull은 동률에서 앞 원소를 돌려준다 — Swift min(by:)와 같다
        return hits.minByOrNull { it.tier }?.km
    }

    /// 줄에 기록(H:MM:SS·MM:SS·N시간 N분)이 있는지 — 구간 기록표 행 판별용
    private fun hasTime(line: String): Boolean =
        matches("""\d{1,2}:\d{2}|\d{1,2}\s?시간\s?\d{1,2}\s?분""", line)

    // MARK: - 기록

    /// (Android: Swift는 line을 뺀 (hours, minutes, seconds) 튜플을 돌려준다 — 여기서는 후보를 그대로 돌려준다)
    private data class TimeCandidate(
        val hours: Int?,
        val minutes: Int,
        val seconds: Int?,
        val line: Int,
    )

    /// 넷타임(칩) 우선 — 같은 줄(없으면 바로 위 줄)의 라벨로 후보 순위를 정한다.
    /// 넷·칩 라벨 → 맨 앞, 무라벨 → 가운데, 건·총 라벨만 → 맨 뒤. 같은 순위는 위→아래
    private fun recordTime(lines: List<String>): TimeCandidate? {
        val candidates = mutableListOf<TimeCandidate>()
        for ((index, line) in lines.withIndex()) {
            for (groups in captures("""(?<![\d:])(\d{1,2}):(\d{2}):(\d{2})(?![\d:])""", line)) {
                val h = swiftInt(groups[0])
                val m = swiftInt(groups[1])
                val s = swiftInt(groups[2])
                if (h != null && m != null && s != null) {
                    candidates.add(TimeCandidate(hours = h, minutes = m, seconds = s, line = index))
                }
            }
            for (groups in captures("""(?<!\d)(\d{1,2})\s?시간\s?(\d{1,2})\s?분(?:\s?(\d{1,2})\s?초)?""", line)) {
                val h = swiftInt(groups[0])
                val m = swiftInt(groups[1])
                if (h != null && m != null) {
                    candidates.add(TimeCandidate(hours = h, minutes = m, seconds = swiftInt(groups[2]), line = index))
                }
            }
        }
        // 8시간 이상은 휠 범위 밖 — 페이스·시각 표기 오독으로 보고 버린다
        candidates.removeAll { (it.hours ?: 0) >= 8 }
        // H:MM:SS가 하나도 없을 때만 MM:SS를 받는다. 시각(07:30 AM·오전 7:30)은 제외
        if (candidates.isEmpty()) {
            for ((index, line) in lines.withIndex()) {
                val pattern = """(?<![\d:])(?<!(?:오전|오후)\s?)(\d{1,2}):(\d{2})(?![\d:])(?!\s?(?:am|pm|오전|오후))"""
                for (groups in captures(pattern, line)) {
                    val m = swiftInt(groups[0])
                    val s = swiftInt(groups[1])
                    if (m != null && s != null) {
                        candidates.add(TimeCandidate(hours = null, minutes = m, seconds = s, line = index))
                    }
                }
            }
        }
        fun rank(candidate: TimeCandidate): Int {
            val own = lines[candidate.line]
            val above = if (candidate.line > 0) lines[candidate.line - 1] else ""
            val labelLine = if (hasLabel(own)) own else above
            if (matches(netLabel, labelLine)) return 0
            if (matches(gunLabel, labelLine)) return 2
            return 1
        }
        // min(by:)는 동순위에서 앞 원소를 돌려준다 — 위→아래 순서가 유지된다 (minByOrNull도 같다)
        return candidates.minByOrNull { rank(it) }
    }

    private const val netLabel = """넷|net|칩|chip"""
    private const val gunLabel = """gun|건|총"""

    private fun hasLabel(line: String): Boolean =
        matches(netLabel, line) || matches(gunLabel, line)

    // MARK: - 날짜

    private data class RaceDate(val year: Int?, val month: Int, val day: Int)

    /// 한국 완주증의 '2026.03.15'·'2026-3-15'·'2026년 3월 15일'. 없으면 연도 없는 '3월 15일'
    /// (연도 해석은 RaceResultParser.interpret이 가장 가까운 과거로 한다)
    private fun raceDate(lines: List<String>): RaceDate? {
        val full = """(?<!\d)(\d{4})\s?[.\-/년]\s?(\d{1,2})\s?[.\-/월]\s?(\d{1,2})(?!\d)"""
        for (line in lines) {
            val groups = captures(full, line).firstOrNull() ?: continue
            val y = swiftInt(groups[0])
            val m = swiftInt(groups[1])
            val d = swiftInt(groups[2])
            if (y != null && m != null && d != null) {
                return RaceDate(y, m, d)
            }
        }
        for (line in lines) {
            val groups = captures("""(?<!\d)(\d{1,2})\s?월\s?(\d{1,2})\s?일""", line).firstOrNull() ?: continue
            val m = swiftInt(groups[0])
            val d = swiftInt(groups[1])
            if (m != null && d != null) {
                return RaceDate(null, m, d)
            }
        }
        return null
    }

    // MARK: - 정규식 헬퍼 (대소문자 무시)

    private fun matches(pattern: String, line: String): Boolean =
        captures(pattern, line).isNotEmpty()

    /// 매칭마다 캡처 그룹 문자열 배열 — 매칭되지 않은 그룹은 빈 문자열
    /// (Android: NSRegularExpression(ICU)처럼 `\d`·`\s`가 유니코드 문자를 받도록 UNICODE_CHARACTER_CLASS를 켠다)
    private fun captures(pattern: String, line: String): List<List<String>> {
        val matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS)
            .matcher(line)
        val result = mutableListOf<List<String>>()
        while (matcher.find()) {
            result.add((1..matcher.groupCount()).map { matcher.group(it) ?: "" })
        }
        return result
    }

    /// Swift `Int(String)` — ASCII 숫자만 받는다. Kotlin toIntOrNull은 전각·아라비아 숫자도 받아서
    /// `\d`가 유니코드 숫자에 매칭된 경우 Swift(nil)와 갈린다
    private fun swiftInt(text: String): Int? =
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toIntOrNull() else null
}
