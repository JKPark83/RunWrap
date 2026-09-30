import Foundation
import Testing
@testable import RunWrap

/// 대회 접수 상태 판정·정렬·필터 검증 — now = 2026-08-12 09:00 KST 고정 (계획서 M13-2)
struct RaceEngineTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-12T09:00:00+09:00")!

    private func race(id: Int = 1, date: String,
                      registerStart: String? = nil,
                      registerEnd: String? = nil) -> Race {
        Race(id: id, name: "테스트 대회", date: date,
             registerStart: registerStart, registerEnd: registerEnd)
    }

    @Test("접수중 판정 — 시작일과 마감일 사이면 open, 마감 D-day를 함께 계산한다")
    func openStatus() throws {
        // 8/12 기준: 접수 8/1~8/20 → 접수중, 마감까지 8일. 대회 9/1 → D-20
        let entries = RaceEngine.entries(
            from: [race(date: "2026-09-01", registerStart: "2026-08-01", registerEnd: "2026-08-20")],
            now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .open(end: RaceEngine.day("2026-08-20")))
        #expect(entry.deadlineDDay == 8)
        #expect(entry.dDay == 20)
    }

    @Test("마감일 당일 포함 — registerEnd가 오늘이면 아직 접수중이다")
    func deadlineDayInclusive() throws {
        let entries = RaceEngine.entries(
            from: [race(date: "2026-09-01", registerStart: "2026-08-01", registerEnd: "2026-08-12")],
            now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .open(end: RaceEngine.day("2026-08-12")))
        #expect(entry.deadlineDDay == 0)
    }

    @Test("접수예정 판정 — 시작일이 미래면 notYet")
    func notYetStatus() throws {
        let entries = RaceEngine.entries(
            from: [race(date: "2026-10-01", registerStart: "2026-09-01", registerEnd: "2026-09-20")],
            now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .notYet(start: RaceEngine.day("2026-09-01")!))
    }

    @Test("접수완료 판정 — 마감일이 지나면 closed (기획서 §4.14 '지나간 대회는 접수완료')")
    func closedStatus() throws {
        let entries = RaceEngine.entries(
            from: [race(date: "2026-09-01", registerStart: "2026-07-01", registerEnd: "2026-08-11")],
            now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .closed)
        #expect(entry.deadlineDDay == nil)
    }

    @Test("접수기간 미상 가드 — 시작·마감 둘 다 없으면 상태를 지어내지 않는다(nil)")
    func unknownPeriodGuard() throws {
        let entries = RaceEngine.entries(from: [race(date: "2026-09-01")], now: now)
        #expect(try #require(entries.first).status == nil)
    }

    @Test("마감일 미상 접수중 — 시작일만 있고 지났으며 대회까지 30일 이상이면 open(end: nil)")
    func openWithoutEnd() throws {
        // 대회 10/1 → D-50 (8월 19일 + 9월 30일 + 1) ≥ 30
        let entries = RaceEngine.entries(
            from: [race(date: "2026-10-01", registerStart: "2026-08-01")], now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .open(end: nil))
        #expect(entry.deadlineDDay == nil)
    }

    @Test("마감일 미상 가드 — 대회가 30일 안으로 다가오면 접수중으로 보지 않는다(nil)")
    func unknownEndNearRaceGuard() throws {
        // 대회 9/1 → D-20 (8/12→8/31 19일 + 1) < 30. 크롤러가 "9월31일" 마감을 버린 경우 (#45)
        let entries = RaceEngine.entries(
            from: [race(date: "2026-09-01", registerStart: "2026-08-01")], now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == nil)
        #expect(entry.deadlineDDay == nil)
    }

    @Test("마감일 미상 가드 경계 — D-30은 접수중, D-29는 상태 미상")
    func unknownEndBoundary() throws {
        // 대회 9/11 → D-30 (8월 남은 19일 + 9월 11일), 9/10 → D-29
        let entries = RaceEngine.entries(
            from: [race(id: 1, date: "2026-09-11", registerStart: "2026-08-01"),
                   race(id: 2, date: "2026-09-10", registerStart: "2026-08-01")],
            now: now)
        let d30 = try #require(entries.first { $0.race.id == 1 })
        let d29 = try #require(entries.first { $0.race.id == 2 })
        #expect(d30.dDay == 30)
        #expect(d30.status == .open(end: nil))
        #expect(d29.dDay == 29)
        #expect(d29.status == nil)
    }

    @Test("마감일 미상이어도 시작 전이면 notYet — 가드는 접수예정 판정 뒤에 온다")
    func notYetWithoutEndNearRace() throws {
        // 대회 8/30 → D-18, 접수 시작 8/20 > 오늘 8/12 → 접수예정 유지
        let entries = RaceEngine.entries(
            from: [race(date: "2026-08-30", registerStart: "2026-08-20")], now: now)
        let entry = try #require(entries.first)
        #expect(entry.status == .notYet(start: RaceEngine.day("2026-08-20")!))
    }

    @Test("지난 대회 필터 — 대회일이 어제면 빠지고, 오늘이면 D-0으로 남는다")
    func pastRaceFilter() throws {
        let entries = RaceEngine.entries(
            from: [race(id: 1, date: "2026-08-11"), race(id: 2, date: "2026-08-12")],
            now: now)
        #expect(entries.count == 1)
        let entry = try #require(entries.first)
        #expect(entry.race.id == 2)
        #expect(entry.dDay == 0)
    }

    @Test("정렬 — 대회일이 가까운 순, 같은 날은 id 순")
    func sorting() {
        let entries = RaceEngine.entries(from: [
            race(id: 3, date: "2026-10-01"),
            race(id: 2, date: "2026-08-20"),
            race(id: 5, date: "2026-08-20"),
            race(id: 1, date: "2026-09-01"),
        ], now: now)
        #expect(entries.map(\.race.id) == [2, 5, 1, 3])
    }

    @Test("날짜 형식 오류 가드 — 대회일을 못 읽는 대회는 목록에서 뺀다")
    func malformedDateGuard() {
        let entries = RaceEngine.entries(from: [race(date: "2026년 9월 1일")], now: now)
        #expect(entries.isEmpty)
    }

    @Test("Races.json 디코딩 — 전체 필드와 최소 필드 모두 읽힌다")
    func decoding() throws {
        let json = Data("""
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","races":[
          {"id":41504,"name":"2026 인사이더런 S","date":"2026-08-01","startTime":"09:30",
           "region":"서울","place":"일산 킨텍스","host":"러너블","categories":["10km"],
           "registerStart":"2026-03-26","registerEnd":"2026-07-30",
           "homepage":"http://insiderun.me","imageUrl":"http://insiderun.me/og.png",
           "lat":37.6646954,"lon":126.7420642,"note":"10Km 레이스"},
          {"id":1,"name":"최소 대회","date":"2026-09-01"}
        ]}
        """.utf8)
        let file = try JSONDecoder().decode(RaceFile.self, from: json)
        #expect(file.source == "roadrun.co.kr")
        #expect(file.races.count == 2)
        let full = try #require(file.races.first)
        #expect(full.categories == ["10km"])
        #expect(full.registerEnd == "2026-07-30")
        #expect(full.imageUrl == "http://insiderun.me/og.png")
        let minimal = file.races[1]
        #expect(minimal.startTime == nil && minimal.homepage == nil)
        #expect(minimal.imageUrl == nil)
        #expect(file.schemaVersion == 1)   // 필드가 없으면 1로 기본 (#144)
    }

    @Test("관대 디코딩 — lat이 문자열인 원소 하나만 건너뛰고 나머지는 읽는다")
    func lenientDecodingTypeMismatch() throws {
        let json = Data("""
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","races":[
          {"id":1,"name":"첫 대회","date":"2026-09-01"},
          {"id":2,"name":"깨진 대회","date":"2026-09-02","lat":"37.5"},
          {"id":3,"name":"셋째 대회","date":"2026-09-03"}
        ]}
        """.utf8)
        let file = try JSONDecoder().decode(RaceFile.self, from: json)
        #expect(file.races.map(\.id) == [1, 3])
    }

    @Test("관대 디코딩 — 필수 필드(name)가 빠진 원소와 객체가 아닌 원소는 건너뛴다")
    func lenientDecodingMissingField() throws {
        // null 원소는 빈 struct로 커서를 넘기는 패턴이면 무한 루프가 나는 경우 — 래퍼 방식 회귀 방지
        let json = Data("""
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","schemaVersion":1,"races":[
          {"id":1,"date":"2026-09-01"},
          null,
          {"id":2,"name":"정상 대회","date":"2026-09-02"}
        ]}
        """.utf8)
        let file = try JSONDecoder().decode(RaceFile.self, from: json)
        #expect(file.races.map(\.id) == [2])
    }

    @Test("관대 디코딩 — 최상위 필수 키(generatedAt)가 없으면 여전히 실패한다")
    func topLevelStillRequired() {
        let json = Data("""
        {"source":"roadrun.co.kr","races":[]}
        """.utf8)
        #expect(throws: DecodingError.self) { try JSONDecoder().decode(RaceFile.self, from: json) }
    }

    @Test("스키마 버전 — 파일 값을 그대로 읽는다 (지원 버전 비교는 RaceStore)")
    func schemaVersionDecoding() throws {
        let json = Data("""
        {"generatedAt":"2026-08-12T12:24:18+09:00","source":"roadrun.co.kr","schemaVersion":2,"races":[]}
        """.utf8)
        let file = try JSONDecoder().decode(RaceFile.self, from: json)
        #expect(file.schemaVersion == 2)
        #expect(file.schemaVersion > RaceStore.supportedSchemaVersion)
    }

    @Test("자동 갱신 판정 — 받은 적 없으면 받고, 6시간 넘게 지났을 때만 다시 받는다")
    func needsRefresh() {
        #expect(RaceStore.needsRefresh(lastRefreshedAt: nil, now: now))
        // 5시간 전 갱신 → 6시간(21_600초) 이내라 그대로
        #expect(!RaceStore.needsRefresh(lastRefreshedAt: now.addingTimeInterval(-5 * 3_600), now: now))
        // 7시간 전 갱신 → 다시 받는다
        #expect(RaceStore.needsRefresh(lastRefreshedAt: now.addingTimeInterval(-7 * 3_600), now: now))
    }

    @Test("신선도 판정 — 6일 전까지는 신선, 8일 전은 오래됨, 못 읽으면 경고하지 않는다")
    func staleness() {
        // now = 8/12 09:00 KST. maxDays 7 → 7일(604_800초) 초과면 오래됨
        #expect(!RaceFormat.isStale(generatedAt: "2026-08-10T09:00:00+09:00", now: now))   // 2일
        #expect(!RaceFormat.isStale(generatedAt: "2026-08-06T09:00:00+09:00", now: now))   // 6일
        #expect(RaceFormat.isStale(generatedAt: "2026-08-04T09:00:00+09:00", now: now))    // 8일
        #expect(!RaceFormat.isStale(generatedAt: "8월 8일", now: now))
    }

    @Test("헤더 캡션 — 정상·새로고침 실패·오래됨·둘 다 네 가지 문구")
    func caption() {
        let updated = "2026-08-12T05:10:00+09:00"
        #expect(RaceFormat.caption(openCount: 12, updatedAt: updated,
                                   refreshFailed: false, stale: false)
                == "지금 접수받는 대회 12곳 · 8월 12일 갱신")
        #expect(RaceFormat.caption(openCount: 12, updatedAt: updated,
                                   refreshFailed: true, stale: false)
                == "지금 접수받는 대회 12곳\n방금 새로 받진 못했어요 · 8월 12일 자료")
        #expect(RaceFormat.caption(openCount: 12, updatedAt: updated,
                                   refreshFailed: false, stale: true)
                == "지금 접수받는 대회 12곳\n자료가 조금 오래됐어요 · 8월 12일 자료")
        #expect(RaceFormat.caption(openCount: 12, updatedAt: updated,
                                   refreshFailed: true, stale: true)
                == "지금 접수받는 대회 12곳\n새로 받지 못해 자료가 조금 오래됐어요 · 8월 12일 자료")
    }
}
