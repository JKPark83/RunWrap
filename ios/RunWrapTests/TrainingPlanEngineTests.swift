import Foundation
import Testing
@testable import RunWrap

/// 훈련 계획 캘린더 엔진 검증 (이슈 #189) — 표본·지평 가드, 주 구성, guide()와의 일치,
/// 10% 룰 점증·피크 정지, 테이퍼, 실제 거리, 주 라벨.
/// now = 2026-08-10T09:00:00Z = KST 2026-08-10(월) 18:00 고정 —
/// 월요일이라 대회까지의 달력 주 수가 daysToRace / 7 + 1과 같다.
struct TrainingPlanEngineTests {
    private let now = ISO8601DateFormatter().date(from: "2026-08-10T09:00:00Z")!

    /// 주 경계가 시각에 민감한 테스트용 — KST 고정 (이번 주 = 08-10 00:00 ~ 08-17 00:00 KST)
    private var kst: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }

    private func run(daysAgo: Double, km: Double, minPerKm: Double = 6) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: 150)
    }

    /// 28일 동안 10km × 12회 = 총 120km → chronic(4주 주평균) = 30km.
    /// daysAgo 1~26.3 — 최고령이 21일을 넘어 가드를 통과한다 (TrainingGuideEngineTests와 같은 표본)
    private var baseRuns: [RunSummary] {
        (0..<12).map { run(daysAgo: Double($0) * 2.3 + 1, km: 10) }
    }

    private func raceDate(inDays days: Int) -> Date {
        now.addingTimeInterval(TimeInterval(days * 86_400))
    }

    private func plan(runs: [RunSummary]? = nil, race: RaceDistance = .full,
                      level: RunnerLevel = .intermediate, days: Int,
                      calendar: Calendar = .current) -> TrainingPlan? {
        TrainingPlanEngine.plan(runs: runs ?? baseRuns, race: race, level: level,
                                raceDate: raceDate(inDays: days), now: now, calendar: calendar)
    }

    private func approx(_ a: Double?, _ b: Double) -> Bool {
        guard let a else { return false }
        return abs(a - b) < 1e-6
    }

    // MARK: - ① 가드

    @Test("표본 부족 가드 — 기록이 21일 미만이면 계획을 내지 않는다")
    func guardShortHistory() {
        // 최고령 20일 전 — guide()와 같은 3주 가드에 걸린다
        let runs = (0..<10).map { run(daysAgo: Double($0) * 2 + 1, km: 10) }
        #expect(plan(runs: runs, days: 70) == nil)
    }

    @Test("표본 부족 가드 — 만성 부하가 주 3km 미만이면 계획을 내지 않는다")
    func guardLowChronic() {
        // 28일 합 2 + 5 = 7km → 7 / 4 = 1.75km < 3
        let runs = [run(daysAgo: 25, km: 2), run(daysAgo: 22, km: 5)]
        #expect(plan(runs: runs, days: 70) == nil)
    }

    @Test("지평 가드 — 대회가 지났으면 nil, 대회 당일은 계획을 낸다")
    func guardRacePassed() {
        #expect(plan(days: -1) == nil)
        #expect(plan(days: 0) != nil)
    }

    @Test("지평 가드 — 대회가 25주 뒤면 nil, 24주 6일 뒤까지는 낸다")
    func guardHorizon() {
        // 175 / 7 = 25 > 24 → nil. 174 / 7 = 24 → 통과
        #expect(plan(days: 175) == nil)
        #expect(plan(days: 174) != nil)
    }

    // MARK: - ② 주 구성

    @Test("주 구성 — 지난 주(기록 시작 이후) + 이번 주부터 대회 주간까지, 마지막 주가 대회 주간")
    func weekLayout() throws {
        let p = try #require(plan(days: 70, calendar: kst))
        // 지난 주: 최고령 26.3일 전(07-14 KST) → 07-13 주는 기록 시작 전이라 빠지고
        // 07-20·07-27·08-03 3개. 미래 주: 70 / 7 + 1 = 11개 → 총 14개
        #expect(p.daysToRace == 70)
        #expect(p.weeks.count == 3 + 11)
        #expect(p.weeks.prefix(3).allSatisfy { $0.phase == nil && !$0.isCurrent })
        #expect(p.weeks.filter(\.isCurrent).count == 1)
        #expect(p.weeks[3].isCurrent)

        let last = try #require(p.weeks.last)
        #expect(last.phase == .raceWeek)
        let raceDay = raceDate(inDays: 70)
        #expect(last.weekStart <= raceDay)
        #expect(raceDay < last.weekStart.addingTimeInterval(7 * 86_400))
        // 주는 정확히 7일 간격으로 이어진다
        for (a, b) in zip(p.weeks, p.weeks.dropFirst()) {
            #expect(b.weekStart.timeIntervalSince(a.weekStart) == 7 * 86_400)
        }
    }

    // MARK: - ③ guide()와 일치

    @Test("이번 주 처방 — 단계·주간 거리·LSD·퀄리티가 guide()의 이번 주 처방과 같다",
          arguments: [140, 70, 35, 10, 3, 0])
    func currentWeekMatchesGuide(days: Int) throws {
        let prescription = try #require(TrainingGuideEngine(now: now, level: .intermediate)
            .guide(runs: baseRuns, race: .full, goalSec: nil,
                   raceDate: raceDate(inDays: days), batteryTone: nil)?.prescription)
        let current = try #require(plan(days: days)?.weeks.first(where: { $0.isCurrent }))
        #expect(current.phase == prescription.phase)
        #expect(approx(current.weeklyKmLow, prescription.weeklyKmLow))
        #expect(approx(current.weeklyKmHigh, prescription.weeklyKmHigh))
        #expect(approx(current.lsdKmLow, prescription.lsdKmLow))
        #expect(approx(current.lsdKmHigh, prescription.lsdKmHigh))
        #expect(current.tempoCount == prescription.tempoCount)
        #expect(current.intervalCount == prescription.intervalCount)
    }

    // MARK: - ④ 점증

    @Test("10% 룰 점증 — 만성 30km에서 주마다 ×1.1, 피크 65km(풀·런잘알)에서 멈춘다")
    func progressiveBuild() throws {
        // 140일 = 20주 → 미래 주 k = 0…20. 단계: 남은 주 20−k —
        // 기초 k 0…10, 강화 k 11…14, 피크 k 15…17, 테이퍼 k 18·19, 대회 주간 k 20
        let p = try #require(plan(days: 140))
        let future = Array(p.weeks.drop(while: { !$0.isCurrent }))
        #expect(future.count == 21)
        #expect(p.peakWeeklyKm == 65)

        // 주 0: 30 … 33, 주 1: 33 … 36.3, 주 2: 36.3 … 39.93
        #expect(approx(future[0].weeklyKmLow, 30) && approx(future[0].weeklyKmHigh, 33))
        #expect(approx(future[1].weeklyKmLow, 33) && approx(future[1].weeklyKmHigh, 36.3))
        #expect(approx(future[2].weeklyKmLow, 36.3) && approx(future[2].weeklyKmHigh, 39.93))
        // 30 × 1.1^9 = 70.74 ≥ 65 → 주 8: 30 × 1.1^8 = 64.3077 … 65 (상한에 처음 닿음)
        #expect(approx(future[8].weeklyKmLow, 30 * pow(1.1, 8)))
        #expect(approx(future[8].weeklyKmHigh, 65))
        // 주 9부터 피크 단계 끝(주 17)까지 65 … 65에서 정지
        for week in future[9...17] {
            #expect(approx(week.weeklyKmLow, 65) && approx(week.weeklyKmHigh, 65))
        }
        // 전 구간 low ≤ high
        #expect(future.allSatisfy { ($0.weeklyKmLow ?? 0) <= ($0.weeklyKmHigh ?? 0) })
        // LSD는 하한 × 25% … 상한 × 35% — 주 1: 8.25 … 12.705
        #expect(approx(future[1].lsdKmLow, 33 * 0.25) && approx(future[1].lsdKmHigh, 36.3 * 0.35))
    }

    // MARK: - ⑤ 테이퍼

    @Test("풀코스 테이퍼 — 2주는 쌓은 볼륨의 60~70%, 대회 주간은 40~50%·LSD 0")
    func fullTaper() throws {
        // 위 140일 계획: 쌓은 볼륨(마지막 피크 주 상한) = 65
        // → 테이퍼 39 … 45.5, 대회 주간 26 … 32.5
        let p = try #require(plan(days: 140))
        let future = Array(p.weeks.drop(while: { !$0.isCurrent }))
        for week in future[18...19] {
            #expect(week.phase == .taper)
            #expect(approx(week.weeklyKmLow, 39) && approx(week.weeklyKmHigh, 45.5))
        }
        let raceWeek = future[20]
        #expect(raceWeek.phase == .raceWeek)
        #expect(approx(raceWeek.weeklyKmLow, 26) && approx(raceWeek.weeklyKmHigh, 32.5))
        #expect(raceWeek.lsdKmLow == 0 && raceWeek.lsdKmHigh == 0)
        #expect(raceWeek.tempoCount == 0 && raceWeek.intervalCount == 0)

        // 70일 계획은 피크에 못 닿는다 — 마지막 피크 주(k 7) 상한 30 × 1.1^8 = 64.3077이 기준
        let short = try #require(plan(days: 70))
        let taper = try #require(short.weeks.first(where: { $0.phase == .taper }))
        #expect(approx(taper.weeklyKmLow, 30 * pow(1.1, 8) * 0.6))
        #expect(approx(taper.weeklyKmHigh, 30 * pow(1.1, 8) * 0.7))
    }


    @Test("대회 주간 단일 — 대회가 오늘보다 이른 요일(일요일에 본 토요일 대회)이어도 대회 주간은 마지막 주 하나뿐")
    func singleRaceWeek() throws {
        // now = KST 2026-08-16(일) 18:00, 대회 = 2026-08-29(토) → daysToRace 13.
        // 달력 주: 이번 주 8/10~16(k0), 8/17~23(k1), 8/24~30(k2, 대회 주간).
        // k1은 굴러가는 식으로 13−7 = 6일이라 대회 주간이 되지만, 마지막 주가 아니므로 7일로 받쳐 테이퍼여야 한다
        let sunday = ISO8601DateFormatter().date(from: "2026-08-16T09:00:00Z")!
        let runs = (0..<12).map { i -> RunSummary in
            RunSummary(id: UUID(), start: sunday.addingTimeInterval(-(Double(i) * 2.3 + 1) * 86_400),
                       durationSec: 3_600, distanceMeters: 10_000, avgHeartRate: 150)
        }
        let p = try #require(TrainingPlanEngine.plan(runs: runs, race: .full, level: .intermediate,
                                                     raceDate: sunday.addingTimeInterval(13 * 86_400),
                                                     now: sunday))
        let future = Array(p.weeks.drop(while: { !$0.isCurrent }))
        #expect(future.count == 3)
        #expect(future.map(\.phase) == [.taper, .taper, .raceWeek])
        #expect(future.filter { $0.phase == .raceWeek }.count == 1)
    }

    // MARK: - ⑥ 실제 거리

    @Test("실제 거리 — 지난 주는 합계, 이번 주는 지금까지 합계, 미래 주는 nil, 기록 전 주는 빠진다")
    func actualKm() throws {
        // KST 주 시작: 이번 주 08-10(0.75일 전), −1주 08-03(7.75일 전),
        // −2주 07-27(14.75일 전), −3주 07-20(21.75일 전), −4주 07-13(28.75일 전)
        // 최고령 22일 전 → −4주는 기록 시작 전이라 목록에서 빠진다 (그 주 10km는 표시 안 함)
        let runs = [run(daysAgo: 22, km: 10),                              // −4주
                    run(daysAgo: 20, km: 8), run(daysAgo: 16, km: 6),      // −3주 = 14
                    run(daysAgo: 10, km: 12),                              // −2주 = 12
                    run(daysAgo: 3, km: 9), run(daysAgo: 1, km: 5),        // −1주 = 14
                    run(daysAgo: 0.5, km: 4)]                              // 이번 주 = 4
        // 28일 합 54km → chronic 13.5 ≥ 3 → 가드 통과
        let p = try #require(plan(runs: runs, days: 35, calendar: kst))
        let past = p.weeks.filter { $0.phase == nil }
        #expect(past.map(\.actualKm) == [14, 12, 14])
        #expect(past.allSatisfy { $0.weeklyKmLow == nil && $0.lsdKmHigh == nil && $0.tempoCount == 0 })
        let current = try #require(p.weeks.first(where: { $0.isCurrent }))
        #expect(current.actualKm == 4)
        #expect(p.weeks.drop(while: { !$0.isCurrent }).dropFirst().allSatisfy { $0.actualKm == nil })
    }

    // MARK: - ⑦ 라벨

    @Test("주 라벨 — Format.weekLabel(weekStart:)과 같다")
    func labels() throws {
        let p = try #require(plan(days: 70))
        #expect(p.weeks.allSatisfy { $0.label == Format.weekLabel(weekStart: $0.weekStart) })
    }
}
