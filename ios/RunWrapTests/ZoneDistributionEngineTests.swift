import Foundation
import Testing
@testable import RunWrap

/// 기간별 심박존 분포 + 80/20 강도 배분 검증 (이슈 #165) — 미노출 가드·이지 비율 톤 경계·
/// 주 막대·심박 기준별 존 재적용·히스토그램 가중·캐시 왕복.
@Suite("기간별 심박존 분포")
struct ZoneDistributionEngineTests {
    /// 목요일 — 이번 달력 주(ISO, 월요일 시작)는 8월 10일부터
    let now = ISO8601DateFormatter().date(from: "2026-08-13T09:00:00Z")!

    /// HRmax 200 %HRmax — 110→0.55(Z1) · 130→0.65(Z2) · 150→0.75(Z3) · 170→0.85(Z4) · 190→0.95(Z5)
    let percentMax200 = HeartRateProfile(hrMax: 200, hrMaxSource: .manual,
                                         restingHR: nil, zoneMethod: .percentMax)

    private func run(daysAgo: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: 1_800, distanceMeters: 5_000, avgHeartRate: 145)
    }

    /// daysAgo마다 러닝 하나 + 같은 히스토그램
    private func sessions(_ days: [Double], histogram: [Int: Double])
        -> (runs: [RunSummary], histograms: [UUID: ZoneHistogram]) {
        let runs = days.map { run(daysAgo: $0) }
        let histograms = Dictionary(uniqueKeysWithValues: runs.map {
            ($0.id, ZoneHistogram(secondsByBpm: histogram))
        })
        return (runs, histograms)
    }

    // MARK: 미노출 가드

    @Test("표본 가드 — 심박 기록 세션 7회면 nil, 8회면 값")
    func minimumSessions() throws {
        let seven = sessions([1, 2, 3, 4, 5, 6, 7], histogram: [130: 600])
        #expect(ZoneDistributionEngine.compute(histograms: seven.histograms, runs: seven.runs,
                                               profile: percentMax200, now: now) == nil)

        let eight = sessions([1, 2, 3, 4, 5, 6, 7, 8], histogram: [130: 600])
        let result = try #require(ZoneDistributionEngine.compute(histograms: eight.histograms,
                                                                 runs: eight.runs,
                                                                 profile: percentMax200, now: now))
        #expect(result.sessionCount == 8)
    }

    @Test("표본 가드 — 빈 히스토그램(샘플 없는 세션)과 28일 창 밖 세션은 세지 않는다")
    func emptyAndOutOfWindowDoNotCount() {
        var s = sessions([1, 2, 3, 4, 5, 6, 7], histogram: [130: 600])
        // 8번째: 샘플 없는 세션 → 빈 히스토그램
        let empty = run(daysAgo: 8)
        s.runs.append(empty)
        s.histograms[empty.id] = ZoneHistogram(secondsByBpm: [:])
        // 9번째: 30일 전 — 창(28일 전 자정 ~ now) 밖
        let old = run(daysAgo: 30)
        s.runs.append(old)
        s.histograms[old.id] = ZoneHistogram(secondsByBpm: [130: 600])
        #expect(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                               profile: percentMax200, now: now) == nil)
    }

    // MARK: 80/20 톤 경계

    @Test("이지 비율 경계 — 0.80 이상 유지, 0.70 이상 주의, 그 밑은 과부하")
    func easyShareToneBoundaries() throws {
        // 세션마다 130bpm(Z2) x초 + 150bpm(Z3) (100−x)초 × 8회 → 이지 비율 = x / 100
        func tone(easy: Double) throws -> (RRTone, Double) {
            let s = sessions([1, 2, 3, 4, 5, 6, 7, 8], histogram: [130: easy, 150: 100 - easy])
            let r = try #require(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                                                profile: percentMax200, now: now))
            return (r.tone, r.easyShare)
        }
        // 640 / 800 = 0.80 정확히 → 유지
        let steady = try tone(easy: 80)
        #expect(steady.0 == .steady)
        #expect(abs(steady.1 - 0.80) < 1e-9)
        #expect(try tone(easy: 79).0 == .caution)
        // 560 / 800 = 0.70 정확히 → 주의
        #expect(try tone(easy: 70).0 == .caution)
        #expect(try tone(easy: 69).0 == .overload)
        #expect(try tone(easy: 100).0 == .steady)
    }

    @Test("누적 비율 — Z1~Z5 비율 합 1, Z1+Z2가 이지 비율")
    func zoneShareSumsToOne() throws {
        // 세션마다 110(Z1) 20초 · 130(Z2) 50초 · 150(Z3) 10초 · 170(Z4) 10초 · 190(Z5) 10초 = 100초
        let s = sessions([1, 2, 3, 4, 5, 6, 7, 8],
                         histogram: [110: 20, 130: 50, 150: 10, 170: 10, 190: 10])
        let r = try #require(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                                            profile: percentMax200, now: now))
        let expected = [0.2, 0.5, 0.1, 0.1, 0.1]
        for (actual, want) in zip(r.zoneShare, expected) { #expect(abs(actual - want) < 1e-9) }
        #expect(abs(r.zoneShare.reduce(0, +) - 1) < 1e-9)
        #expect(abs(r.easyShare - 0.7) < 1e-9)   // 0.2 + 0.5
    }

    // MARK: 주 막대

    @Test("주 막대 — 최근 4개 달력 주, 오래된 → 최신, 세션 없는 주는 0")
    func weekBars() throws {
        // 이번 주(8/10~): 0.2·1·2일 전 3회 / 지난주(8/3~9): 없음 / 2주 전(7/27~8/2): 14·15·16일 전 3회 /
        // 3주 전(7/20~26): 21·22일 전 2회 → 합 8회, 세션당 130bpm 600초
        // (주 경계에서 하루 이상 떨어진 날만 골라 시뮬레이터 시간대가 달라도 같은 주에 든다)
        let s = sessions([0.2, 1, 2, 14, 15, 16, 21, 22], histogram: [130: 600])
        let r = try #require(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                                            profile: percentMax200, now: now))
        #expect(r.weeks.count == 4)
        #expect(r.weeks.map { $0.zoneSeconds.reduce(0, +) } == [1_200, 1_800, 0, 1_800])
        #expect(r.weeks[2].zoneSeconds == [0, 0, 0, 0, 0])
        #expect(r.weeks[3].zoneSeconds == [0, 1_800, 0, 0, 0])   // 130bpm = Z2
        // 주 시작일이 7일씩 오르고, 라벨은 Format.weekLabel과 같다
        for pair in zip(r.weeks, r.weeks.dropFirst()) {
            #expect(pair.1.weekStart.timeIntervalSince(pair.0.weekStart) == 7 * 86_400)
        }
        for week in r.weeks { #expect(week.label == Format.weekLabel(weekStart: week.weekStart)) }
        #expect(r.weeks[3].label == "8월 2째주")   // 8/10 주의 목요일 8/13 → 2째주
    }

    // MARK: 심박 기준 재적용

    @Test("같은 히스토그램도 심박 기준에 따라 존이 달라진다 — Karvonen vs %HRmax")
    func profileChangesZones() throws {
        let s = sessions([1, 2, 3, 4, 5, 6, 7, 8], histogram: [140: 600])
        // %HRmax(190): 140 / 190 = 0.737 → Z3 → 이지 0% 과부하
        let percentMax = HeartRateProfile(hrMax: 190, hrMaxSource: .manual,
                                          restingHR: 50, zoneMethod: .percentMax)
        let byMax = try #require(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                                                profile: percentMax, now: now))
        #expect(byMax.zoneShare == [0, 0, 1, 0, 0])
        #expect(byMax.tone == .overload)
        // Karvonen(190·안정 50): (140 − 50) / 140 = 0.643 → Z2 → 이지 100% 유지
        let karvonen = HeartRateProfile(hrMax: 190, hrMaxSource: .manual,
                                        restingHR: 50, zoneMethod: .karvonen)
        let byHRR = try #require(ZoneDistributionEngine.compute(histograms: s.histograms, runs: s.runs,
                                                                profile: karvonen, now: now))
        #expect(byHRR.zoneShare == [0, 1, 0, 0, 0])
        #expect(byHRR.tone == .steady)
    }

    // MARK: 히스토그램 생성

    @Test("히스토그램 가중 — 다음 샘플까지 간격(15초 캡), 마지막 5초, 230 초과 제외, bpm 반올림")
    func histogramWeighting() {
        let samples: [(time: Date, bpm: Double)] = [
            (now, 150),                          // 다음까지 10초 → 150: 10
            (now.addingTimeInterval(10), 150.4), // 다음까지 30초 → 15초 캡, 반올림 150 → 150: +15
            (now.addingTimeInterval(40), 160),   // 다음까지 5초 → 160: 5
            (now.addingTimeInterval(45), 240),   // 230 초과 스파이크 → 버린다
            (now.addingTimeInterval(50), 170.6), // 마지막 → 5초, 반올림 171
        ]
        #expect(ZoneHistogram.make(samples: samples).secondsByBpm == [150: 25, 160: 5, 171: 5])
        #expect(ZoneHistogram.make(samples: []).secondsByBpm.isEmpty)
    }

    // MARK: 캐시

    @Test("캐시 왕복 — 저장·복원·가지치기, JSON 키는 UUID 문자열, 파일이 없으면 빈 딕셔너리")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runwrap-zone-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        #expect(ZoneTimeCache.load(from: dir).isEmpty)

        let keep = UUID(), drop = UUID()
        let histograms = [keep: ZoneHistogram(secondsByBpm: [130: 600, 150: 120]),
                          drop: ZoneHistogram(secondsByBpm: [:])]
        ZoneTimeCache.save(histograms, in: dir)
        #expect(ZoneTimeCache.load(from: dir) == histograms)

        // 저장 형식은 [UUID 문자열: ZoneHistogram]
        let raw = try Data(contentsOf: dir.appendingPathComponent(ZoneTimeCache.filename))
        let stored = try JSONDecoder().decode([String: ZoneHistogram].self, from: raw)
        #expect(Set(stored.keys) == [keep.uuidString, drop.uuidString])

        // 28일 창 밖(drop)을 버린다
        let pruned = ZoneTimeCache.prune(histograms, keepingIDs: [keep])
        #expect(pruned == [keep: histograms[keep]!])
        ZoneTimeCache.save(pruned, in: dir)
        #expect(ZoneTimeCache.load(from: dir) == pruned)
    }

    // MARK: 차트 문자열

    @MainActor
    @Test("주 막대 콜아웃·VoiceOver 수치 — 총 시간 · 이지 비율, 달리지 않은 주는 '기록 없음'")
    func chartValueText() {
        let start = now
        // 600 + 1,800 + 600 = 3,000초(50:00), 이지 (600 + 1,800) / 3,000 = 80%
        let week = ZoneDistribution.WeekBar(weekStart: start, label: "8월 2째주",
                                            zoneSeconds: [600, 1_800, 600, 0, 0])
        #expect(ZoneStackedBarsChart.valueText(week) == "50:00 · 이지 80%")
        let empty = ZoneDistribution.WeekBar(weekStart: start, label: "8월 1째주",
                                             zoneSeconds: [0, 0, 0, 0, 0])
        #expect(ZoneStackedBarsChart.valueText(empty) == "기록 없음")
    }
}
