import Foundation

/// 마라톤 대회 한 건 — tools/race-info 크롤러가 만드는 Races.json의 원소 (기획서 §4.14).
/// 참가비·기념품은 로드런에 구조화 필드가 없어 뽑지 않는다 — note(기타소개)에
/// 자유 텍스트로 실린 경우만 그대로 보여준다 (없으면 미표시).
struct Race: Decodable, Identifiable, Equatable {
    let id: Int                       // 로드런 대회 번호 (view.php?no=)
    let name: String
    let date: String                  // 대회일 "yyyy-MM-dd" (KST)
    var startTime: String? = nil      // 출발 시각 "09:30"
    var region: String? = nil         // 대회지역 ("서울", "경남" …)
    var place: String? = nil          // 대회장소
    var host: String? = nil           // 주최단체
    var categories: [String]? = nil   // 종목 ("풀", "하프", "10km" …)
    var registerStart: String? = nil  // 접수 시작일 "yyyy-MM-dd"
    var registerEnd: String? = nil    // 접수 마감일 "yyyy-MM-dd"
    var homepage: String? = nil       // 대회 홈페이지 — 참가하기 버튼 링크
    var imageUrl: String? = nil       // 홈페이지 대표 이미지(og:image) — 카드 썸네일 (#32)
    var lat: Double? = nil            // 대회장 좌표 (로드런 지도 스크립트에서 추출)
    var lon: Double? = nil
    var note: String? = nil           // 기타소개 자유 텍스트
}

/// Races.json 최상위 — 갱신 시각을 화면에 표기하기 위해 generatedAt을 함께 담는다
struct RaceFile: Decodable {
    let generatedAt: String   // ISO8601 "+09:00"
    let source: String        // "roadrun.co.kr"
    let schemaVersion: Int    // 없으면 1 — 앱이 지원하는 것보다 크면 RaceStore가 원격 파일을 버린다 (#144)
    let races: [Race]
}

/// 관대 디코딩 (#144) — races 원소 하나가 깨져도(타입 불일치·필수 필드 누락) 그 원소만 건너뛴다.
/// 크롤 결과 한 건의 오류로 파일 전체가 디코드에 실패해 대회 탭이 통째로 비는 것을 막는다.
/// generatedAt·source는 그대로 필수다.
extension RaceFile {
    private enum CodingKeys: String, CodingKey {
        case generatedAt, source, schemaVersion, races
    }

    /// 원소 단위 실패를 nil로 삼키는 래퍼 — 디코드 자체는 항상 성공해 배열 커서가 다음 원소로 넘어간다.
    /// (빈 struct로 커서를 넘기는 패턴은 원소가 객체가 아니면(null·문자열) 커서가 멈춰 무한 루프가 된다)
    private struct LenientRace: Decodable {
        let race: Race?
        init(from decoder: Decoder) throws {
            race = try? Race(from: decoder)
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        generatedAt = try container.decode(String.self, forKey: .generatedAt)
        source = try container.decode(String.self, forKey: .source)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        races = try container.decode([LenientRace].self, forKey: .races).compactMap(\.race)
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
enum RaceEngine {
    enum RegisterStatus: Equatable {
        case notYet(start: Date)   // 접수예정
        case open(end: Date?)      // 접수중 — 마감일을 모르면 nil (대회 D-30 이상일 때만)
        case closed                // 접수완료
    }

    struct Entry: Identifiable, Equatable {
        let race: Race
        let raceDate: Date          // 대회일 자정 (KST)
        let dDay: Int               // 오늘 기준 대회까지 남은 날 (0 = 오늘)
        let status: RegisterStatus? // 접수기간 미상이면 nil
        let deadlineDDay: Int?      // 접수중일 때 마감까지 남은 날 (0 = 오늘 마감)
        var id: Int { race.id }
    }

    /// 마감일을 모를 때 '접수중'으로 볼 수 있는 대회까지 최소 남은 날 (#45).
    /// dev Races.json(2026-09-29)에서 마감일이 있는 270건의 (대회일 − 마감일) 중앙값 31일,
    /// 25%분위 19일 — D-14에서 실제로 접수중인 대회는 약 18%뿐이라, 마감일을 모르는 채
    /// 가까운 대회를 접수중으로 보면 대부분 틀린다 ("틀린 인사이트는 없느니만 못하다").
    static let unknownEndOpenDays = 30

    /// 한국 달력 — 대회는 전부 국내 개최라 사용자 시간대와 무관하게 KST로 고정
    static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return c
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = calendar.timeZone
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /// "yyyy-MM-dd" → KST 자정. 형식이 어긋나면 nil
    static func day(_ text: String?) -> Date? {
        guard let text else { return nil }
        return dayFormatter.date(from: text)
    }

    static func entries(from races: [Race], now: Date) -> [Entry] {
        let today = calendar.startOfDay(for: now)
        return races.compactMap { race -> Entry? in
            guard let raceDay = day(race.date), raceDay >= today else { return nil }
            let dDay = calendar.dateComponents([.day], from: today, to: raceDay).day ?? 0
            let status = registerStatus(of: race, today: today)
            var deadlineDDay: Int?
            if case .open(let end?) = status {
                deadlineDDay = calendar.dateComponents([.day], from: today, to: end).day
            }
            return Entry(race: race, raceDate: raceDay, dDay: dDay,
                         status: status, deadlineDDay: deadlineDDay)
        }
        .sorted { ($0.raceDate, $0.race.id) < ($1.raceDate, $1.race.id) }
    }

    /// 오늘(자정) 기준 접수 상태. 시작·마감 어느 쪽도 모르면 nil
    static func registerStatus(of race: Race, today: Date) -> RegisterStatus? {
        let start = day(race.registerStart)
        let end = day(race.registerEnd)
        if start == nil && end == nil { return nil }
        if let end, today > end { return .closed }
        if let start, today < start { return .notYet(start: start) }
        // 마감일 미상 + 대회가 30일 안 → 이미 마감됐을 공산이 커서 상태 미상 (#45)
        if end == nil, let raceDay = day(race.date),
           (calendar.dateComponents([.day], from: today, to: raceDay).day ?? 0) < unknownEndOpenDays {
            return nil
        }
        return .open(end: end)
    }
}
