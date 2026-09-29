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
    }
}
