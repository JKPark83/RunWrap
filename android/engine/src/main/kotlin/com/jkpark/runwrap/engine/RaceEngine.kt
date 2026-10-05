package com.jkpark.runwrap.engine

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/// 마라톤 대회 한 건 — tools/race-info 크롤러가 만드는 Races.json의 원소 (기획서 §4.14).
/// 참가비·기념품은 로드런에 구조화 필드가 없어 뽑지 않는다 — note(기타소개)에
/// 자유 텍스트로 실린 경우만 그대로 보여준다 (없으면 미표시).
@Serializable
data class Race(
    val id: Int,                          // 로드런 대회 번호 (view.php?no=)
    val name: String,
    val date: String,                     // 대회일 "yyyy-MM-dd" (KST)
    val startTime: String? = null,        // 출발 시각 "09:30"
    val region: String? = null,           // 대회지역 ("서울", "경남" …)
    val place: String? = null,            // 대회장소
    val host: String? = null,             // 주최단체
    val categories: List<String>? = null, // 종목 ("풀", "하프", "10km" …)
    val registerStart: String? = null,    // 접수 시작일 "yyyy-MM-dd"
    val registerEnd: String? = null,      // 접수 마감일 "yyyy-MM-dd"
    val homepage: String? = null,         // 대회 홈페이지 — 참가하기 버튼 링크
    val imageUrl: String? = null,         // 홈페이지 대표 이미지(og:image) — 카드 썸네일 (#32)
    val lat: Double? = null,              // 대회장 좌표 (로드런 지도 스크립트에서 추출)
    val lon: Double? = null,
    val note: String? = null,             // 기타소개 자유 텍스트
)

/// Races.json 최상위 — 갱신 시각을 화면에 표기하기 위해 generatedAt을 함께 담는다
@Serializable(with = RaceFileSerializer::class)
data class RaceFile(
    val generatedAt: String,  // ISO8601 "+09:00"
    val source: String,       // "roadrun.co.kr"
    val schemaVersion: Int,   // 없으면 1 — 앱이 지원하는 것보다 크면 RaceStore가 원격 파일을 버린다 (#144)
    val races: List<Race>,
)

/// 관대 디코딩 (#144) — races 원소 하나가 깨져도(타입 불일치·필수 필드 누락) 그 원소만 건너뛴다.
/// 크롤 결과 한 건의 오류로 파일 전체가 디코드에 실패해 대회 탭이 통째로 비는 것을 막는다.
/// generatedAt·source는 그대로 필수다.
/// (Android: 디코드 전용 — iOS RaceFile도 Decodable뿐이다. JSON 트리로 받아 원소마다 따로 디코드한다)
object RaceFileSerializer : KSerializer<RaceFile> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RaceFile")

    /// Race의 숫자 필드 — 문자열 값이면 원소를 건너뛴다
    private val numberKeys = listOf("id", "lat", "lon")

    override fun deserialize(decoder: Decoder): RaceFile {
        val json = decoder as? JsonDecoder ?: throw SerializationException("RaceFile은 JSON으로만 읽는다")
        val root = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("최상위가 객체가 아니다")
        fun requiredString(key: String): String {
            val value = root[key] as? JsonPrimitive
            if (value == null || !value.isString) throw SerializationException("$key 누락·타입 불일치")
            return value.content
        }
        val generatedAt = requiredString("generatedAt")
        val source = requiredString("source")
        val schemaVersion = when (val value = root["schemaVersion"]) {
            null, JsonNull -> 1
            // Swift Int 디코드처럼 정수로 떨어지는 수(2, 2.0, 1e2)만 받는다
            is JsonPrimitive -> value.takeUnless { it.isString }?.doubleOrNull
                ?.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
                ?: throw SerializationException("schemaVersion 타입 불일치")
            else -> throw SerializationException("schemaVersion 타입 불일치")
        }
        val races = root["races"] as? JsonArray ?: throw SerializationException("races 누락·타입 불일치")
        // 원소 단위 실패를 nil로 삼킨다 — 디코드 자체는 항상 성공해 다음 원소로 넘어간다
        return RaceFile(generatedAt, source, schemaVersion, races.mapNotNull { element ->
            // kotlinx 트리 디코드는 "37.5"처럼 따옴표 친 숫자도 Double·Int로 읽는다 — Swift는 타입 불일치로 거른다
            if (numberKeys.any { ((element as? JsonObject)?.get(it) as? JsonPrimitive)?.isString == true }) {
                return@mapNotNull null
            }
            try {
                json.json.decodeFromJsonElement(Race.serializer(), element)
            } catch (_: IllegalArgumentException) {
                null  // SerializationException은 IllegalArgumentException의 하위 타입이다
            }
        })
    }

    override fun serialize(encoder: Encoder, value: RaceFile) {
        throw SerializationException("RaceFile은 디코드 전용이다")
    }
}

/// 대회 접수 상태 판정·정렬 — Foundation만 쓰는 순수 로직 (계획서 M13-2)
///
/// 날짜는 전부 한국 달력(Asia/Seoul)의 '일' 단위로 판정한다:
/// - 접수중: 시작일 ≤ 오늘 ≤ 마감일 (마감일 당일 포함)
/// - 접수예정: 오늘 < 시작일
/// - 접수완료: 오늘 > 마감일
/// - 접수기간 정보가 없으면 nil — 모르는 상태를 지어내지 않는다 (미노출 가드)
/// - 마감일 미상 접수중은 대회 30일 전(D-30 포함)까지만 — 그보다 가까우면 상태 미상(nil).
///   크롤러가 잘못된 마감일(원문 "9월31일")을 버린 경우의 방어 (#45)
///
/// 대회일이 지난 대회는 목록에서 뺀다 (크롤 사이에 날짜가 지날 수 있다).
/// 정렬은 대회일이 가까운 순 (기획서 §4.14 "오늘 기준 최근 순").
object RaceEngine {
    @Serializable  // Entry가 화면 route 인자(JSON 문자열)라서
    sealed interface RegisterStatus {
        @Serializable data class notYet(@Serializable(with = ReferenceDateInstantSerializer::class) val start: Instant) : RegisterStatus   // 접수예정
        @Serializable data class open(@Serializable(with = ReferenceDateInstantSerializer::class) val end: Instant?) : RegisterStatus      // 접수중 — 마감일을 모르면 nil (대회 D-30 이상일 때만)
        @Serializable data object closed : RegisterStatus                      // 접수완료
    }

    @Serializable  // 화면 route 인자(JSON 문자열)용
    data class Entry(
        val race: Race,
        @Serializable(with = ReferenceDateInstantSerializer::class) val raceDate: Instant,          // 대회일 자정 (KST)
        val dDay: Int,                  // 오늘 기준 대회까지 남은 날 (0 = 오늘)
        val status: RegisterStatus?,    // 접수기간 미상이면 nil
        val deadlineDDay: Int?,         // 접수중일 때 마감까지 남은 날 (0 = 오늘 마감)
    ) {
        val id: Int get() = race.id
    }

    /// 마감일을 모를 때 '접수중'으로 볼 수 있는 대회까지 최소 남은 날 (#45).
    /// dev Races.json(2026-09-29)에서 마감일이 있는 270건의 (대회일 − 마감일) 중앙값 31일,
    /// 25%분위 19일 — D-14에서 실제로 접수중인 대회는 약 18%뿐이라, 마감일을 모르는 채
    /// 가까운 대회를 접수중으로 보면 대부분 틀린다 ("틀린 인사이트는 없느니만 못하다").
    const val unknownEndOpenDays = 30

    /// 한국 달력 — 대회는 전부 국내 개최라 사용자 시간대와 무관하게 KST로 고정
    /// (Android: `calendar` 대신 EngineSupport의 `KST`를 쓴다)

    /// iOS DateFormatter("yyyy-MM-dd", en_US_POSIX)가 받아들이는 형태 — ICU가 관대하다(실측 2026-10-05):
    /// 자릿수 자유("2026-8-1"), 구분자 -/.,와 공백·탭·NBSP, 앞뒤 공백 허용, 유니코드 십진 숫자 허용.
    /// 끝의 줄바꿈·잔여 글자, 부호, 연속 구분자, 없는 날짜(2월 30일), 0년은 거른다
    private const val blank = "[ \\t\\u00A0]"
    private const val separator = "(?:$blank*[-/.,]$blank*|$blank+)"
    private val dayPattern = Regex("$blank*(\\p{Nd}+)$separator(\\p{Nd}+)$separator(\\p{Nd}+)$blank*")

    /// "yyyy-MM-dd" → KST 자정. 형식이 어긋나면 nil
    fun day(text: String?): Instant? {
        if (text == null) return null
        val match = dayPattern.matchEntire(text) ?: return null
        val (year, month, day) = match.destructured.toList().map { digits(it) ?: return null }
        if (year < 1) return null
        return try {
            LocalDate.of(year, month, day).atStartOfDay(KST).toInstant()
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /// 유니코드 십진 숫자열 → Int. 9자리를 넘으면(연도 범위 밖) nil
    private fun digits(text: String): Int? {
        var value = 0L
        for (codePoint in text.codePoints()) {
            value = value * 10 + Character.digit(codePoint, 10)
            if (value > 999_999_999L) return null
        }
        return value.toInt()
    }

    fun entries(races: List<Race>, now: Instant): List<Entry> {
        val today = startOfDay(now)
        return races.mapNotNull { race ->
            val raceDay = day(race.date)
            if (raceDay == null || raceDay < today) return@mapNotNull null
            val dDay = days(today, raceDay)
            val status = registerStatus(race, today)
            var deadlineDDay: Int? = null
            if (status is RegisterStatus.open && status.end != null) {
                deadlineDDay = days(today, status.end)
            }
            Entry(race = race, raceDate = raceDay, dDay = dDay, status = status, deadlineDDay = deadlineDDay)
        }
            .sortedWith(compareBy<Entry>({ it.raceDate }, { it.race.id }))
    }

    /// 오늘(자정) 기준 접수 상태. 시작·마감 어느 쪽도 모르면 nil
    fun registerStatus(race: Race, today: Instant): RegisterStatus? {
        val start = day(race.registerStart)
        val end = day(race.registerEnd)
        if (start == null && end == null) return null
        if (end != null && today > end) return RegisterStatus.closed
        if (start != null && today < start) return RegisterStatus.notYet(start)
        // 마감일 미상 + 대회가 30일 안 → 이미 마감됐을 공산이 커서 상태 미상 (#45)
        val raceDay = day(race.date)
        if (end == null && raceDay != null && days(today, raceDay) < unknownEndOpenDays) {
            return null
        }
        return RegisterStatus.open(end)
    }

    /// `calendar.startOfDay(for:)` — KST 자정
    private fun startOfDay(date: Instant): Instant = date.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant()

    /// `calendar.dateComponents([.day], from:to:).day` — KST 기준 날 수
    private fun days(from: Instant, to: Instant): Int = ChronoUnit.DAYS.between(from.atZone(KST), to.atZone(KST)).toInt()
}
