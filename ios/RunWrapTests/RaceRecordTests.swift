import Foundation
import Testing
@testable import RunWrap

/// 직접 입력한 대회 기록의 예측 통합 검증 (이슈 #35) — 엔진 표본 경쟁·가드,
/// RaceOutlookEngine 통합, 캐시 왕복, 자연어 해석(순수 로직)까지.
/// now = 2026-08-26T09:00:00Z 고정.
struct RaceRecordTests {
    let now = ISO8601DateFormatter().date(from: "2026-08-26T09:00:00Z")!

    private func record(_ race: RaceDistance, timeSec: Double, daysAgo: Double) -> RaceRecord {
        RaceRecord(id: UUID(), race: race, timeSec: timeSec,
                   date: now.addingTimeInterval(-daysAgo * 86_400))
    }

    private func run(km: Double, timeSec: Double, daysAgo: Double) -> RunSummary {
        RunSummary(id: UUID(), start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: timeSec, distanceMeters: km * 1_000, avgHeartRate: 165)
    }

    private func ready(_ status: RaceOutlookEngine.Status) -> RaceOutlookEngine.Outlook? {
        if case .ready(let outlook) = status { outlook } else { nil }
    }

    // MARK: - 엔진 — racePrediction 표본 경쟁

    @Test("러닝 표본이 없어도 대회 기록만으로 예측한다 — 표본 창(84일) 밖 180일 전이라도")
    func recordAloneEnablesPrediction() throws {
        let best = try #require(TrainingGuideEngine.racePrediction(
            for: .half, runs: [], now: now,
            raceRecords: [record(.half, timeSec: 6_330, daysAgo: 180)]))
        // 같은 종목이라 Riegel 배율 1 → 기록 그대로. 대회 기록은 전력이라 EF 환산(빠른 끝) 없음
        #expect(best.riegelSec == 6_330)
        #expect(best.effortSec == nil)
        #expect(best.isRaceRecord)
        #expect(best.windowDays == 0)
    }

    @Test("대회 기록의 타 종목 외삽 — 하프 1:45:30으로 풀코스 Riegel")
    func recordExtrapolatesToOtherDistance() throws {
        let best = try #require(TrainingGuideEngine.racePrediction(
            for: .full, runs: [], now: now,
            raceRecords: [record(.half, timeSec: 6_330, daysAgo: 180)]))
        // 6,330 × (42.195/21.0975)^1.06 = 6,330 × 2^1.06 ≈ 13,197.6
        #expect(abs(best.riegelSec - 13_197.6) < 1)
    }

    @Test("외삽 상한 가드 — 5K 기록으로 풀코스(8.4배)는 예측하지 않는다")
    func recordRespectsExtrapolationCap() {
        #expect(TrainingGuideEngine.racePrediction(
            for: .full, runs: [], now: now,
            raceRecords: [record(.fiveK, timeSec: 1_200, daysAgo: 30)]) == nil)
    }

    @Test("최대 나이 가드 — 2년(730일) 넘은 기록은 표본이 아니다")
    func recordAgeGuard() {
        #expect(TrainingGuideEngine.racePrediction(
            for: .half, runs: [], now: now,
            raceRecords: [record(.half, timeSec: 6_330, daysAgo: 800)]) == nil)
    }

    @Test("훈련 표본과 경쟁 — 느린 끝(Riegel)이 빠른 쪽이 이긴다")
    func recordCompetesWithTrainingSamples() throws {
        // 훈련: 3일 전 10km 3,000초(5:00/km) → 10K Riegel 3,000초
        let runs = [run(km: 10, timeSec: 3_000, daysAgo: 3)]
        // 100일 전 10K 대회 2,700초가 더 빠르다 → 기록이 근거
        let recordWins = try #require(TrainingGuideEngine.racePrediction(
            for: .tenK, runs: runs, now: now,
            raceRecords: [record(.tenK, timeSec: 2_700, daysAgo: 100)]))
        #expect(recordWins.isRaceRecord)
        #expect(recordWins.riegelSec == 2_700)
        // 3,300초 대회 기록은 훈련 표본에 진다 → 훈련 근거(4주 창) 유지
        let trainingWins = try #require(TrainingGuideEngine.racePrediction(
            for: .tenK, runs: runs, now: now,
            raceRecords: [record(.tenK, timeSec: 3_300, daysAgo: 100)]))
        #expect(!trainingWins.isRaceRecord)
        #expect(trainingWins.windowDays == 28)
    }

    @Test("VO₂max 추세 배율이 대회 기록에도 적용된다")
    func recordGetsFitnessAdjustment() throws {
        // 기록 시점(180일 전) 평균 44.0, 현재 46.0 → 배율 44/46 ≈ 0.9565
        let recordDate = now.addingTimeInterval(-180 * 86_400)
        let vo2: [(date: Date, value: Double)] = [
            (recordDate.addingTimeInterval(-5 * 86_400), 44.0),
            (recordDate.addingTimeInterval(5 * 86_400), 44.0),
            (now.addingTimeInterval(-7 * 86_400), 46.0),
            (now.addingTimeInterval(-2 * 86_400), 46.0),
        ]
        let best = try #require(TrainingGuideEngine.racePrediction(
            for: .half, runs: [], now: now, vo2MaxSamples: vo2,
            raceRecords: [record(.half, timeSec: 6_330, daysAgo: 180)]))
        // 6,330 × 44/46 ≈ 6,054.8 — 그때보다 좋아졌으니 예측이 빨라진다
        #expect(abs(best.riegelSec - 6_054.8) < 0.5)
    }

    @Test("타당성 가드 — 비현실 페이스 기록은 후보에서 빠지고 정상 기록이 근거가 된다 (이슈 #93)")
    func implausibleRecordExcluded() throws {
        // '1시간 45분'을 1분 45초로 오독한 하프 105초 = 약 5초/km — 150초/km 미만이라 제외
        #expect(TrainingGuideEngine.racePrediction(
            for: .half, runs: [], now: now,
            raceRecords: [record(.half, timeSec: 105, daysAgo: 30)]) == nil)
        // 휠 실수 5K 7:59:59(28,799초) = 5,759.8초/km — 1,200초/km 초과라 제외
        #expect(TrainingGuideEngine.racePrediction(
            for: .fiveK, runs: [], now: now,
            raceRecords: [record(.fiveK, timeSec: 28_799, daysAgo: 30)]) == nil)
        // 오독 기록이 섞여 있어도 Riegel 최솟값으로 이기지 못하고 정상 기록(6,330초)이 근거
        let best = try #require(TrainingGuideEngine.racePrediction(
            for: .half, runs: [], now: now,
            raceRecords: [record(.half, timeSec: 105, daysAgo: 30),
                          record(.half, timeSec: 6_330, daysAgo: 180)]))
        #expect(best.riegelSec == 6_330)
    }

    @Test("타당성 판정 — 페이스 150~1,200초/km 안만 통과, 0초·0km는 실패 (이슈 #93)")
    func plausibilityBounds() {
        // 5K: 750초(2′30″/km)·6,000초(20′00″/km)가 양 끝
        #expect(RaceRecord.isPlausible(timeSec: 750, km: 5))
        #expect(!RaceRecord.isPlausible(timeSec: 749, km: 5))
        #expect(RaceRecord.isPlausible(timeSec: 6_000, km: 5))
        #expect(!RaceRecord.isPlausible(timeSec: 6_001, km: 5))
        #expect(RaceRecord.isPlausible(timeSec: 6_330, km: RaceDistance.half.km))
        #expect(!RaceRecord.isPlausible(timeSec: 0, km: 5))
        #expect(!RaceRecord.isPlausible(timeSec: 1_500, km: 0))
    }

    // MARK: - RaceOutlookEngine 통합

    @Test("기록만으로 ready — 근거가 대회 기록임을 Outlook이 밝힌다")
    func outlookFromRecordOnly() throws {
        // 10월 대회 — 평년 15.0°C/63%는 열 점수 23 < 38이라 보정 없음 (더위 변수 제거)
        let raceDate = ISO8601DateFormatter().date(from: "2026-10-18T00:00:00Z")!
        let status = RaceOutlookEngine.status(
            race: .tenK, goalSec: 2_700, raceDate: raceDate, runs: [], now: now,
            raceRecords: [record(.tenK, timeSec: 2_700, daysAgo: 100)])
        let outlook = try #require(ready(status))
        #expect(outlook.predictedSec == 2_700)
        #expect(outlook.predictedFastSec == nil)
        #expect(outlook.isRaceRecord)
        #expect(outlook.tone == .improving)   // 느린 끝이 목표 안 → 달성권
    }

    // MARK: - 대회 기록 열 중립 환산 (이슈 #100)

    /// 7월 17일 10K 3,000초(5:00/km) 대회 기록 — 7월 평년 25.3°C/76%.
    /// 이슬점(Magnus) ≈ 20.76°C → 열 점수 ≈ 46.06 → 보정량 8×1.5 + 0.06×3 ≈ 12.19초/km
    /// → 중립 환산 3,000 − 12.19×10 ≈ 2,878.1초
    private var julyTenKRecord: RaceRecord {
        RaceRecord(id: UUID(), race: .tenK, timeSec: 3_000,
                   date: ISO8601DateFormatter().date(from: "2026-07-17T09:00:00Z")!)
    }

    @Test("7월 대회 기록 → 10월 대회 — 기록 월 더위를 빼 예측이 원본보다 빨라진다")
    func julyRecordNeutralizedForOctoberRace() throws {
        // 10월 평년 15.0°C/63%는 열 점수 ≈ 23 < 38 → 대회 월 보정 0
        let raceDate = ISO8601DateFormatter().date(from: "2026-10-18T00:00:00Z")!
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 2_700, raceDate: raceDate, runs: [], now: now,
            raceRecords: [julyTenKRecord])))
        #expect(outlook.isRaceRecord)
        #expect(abs(outlook.sampleHeatDeltaSecPerKm - 12.19) < 0.05)
        #expect(outlook.heatDeltaSecPerKm == 0)
        // 중립 2,878.1초 + 대회 월 보정 0 = 2,878.1초 (수정 전: 원본 3,000초 그대로)
        #expect(abs(outlook.predictedSec - 2_878.1) < 0.5)
    }

    @Test("7월 대회 기록 → 8월 대회 — 중립화 뒤 대회 월 더위가 다시 붙어 원본에 가깝다")
    func julyRecordNeutralizedThenAugustHeatAdded() throws {
        // 8월 평년 26.1°C/74% → 이슬점 ≈ 21.10°C → 열 점수 ≈ 47.20 → 보정량 12 + 1.20×3 ≈ 15.60초/km
        // 예측 = (300 − 12.19 + 15.60) × 10 ≈ 3,034.1초 — 원본 3,000초보다 8월이 조금 더 더운 만큼만 느리다
        // (수정 전: 3,000 + 15.60×10 = 3,156초 — 7월 더위가 한 번 더 실렸다)
        let raceDate = ISO8601DateFormatter().date(from: "2026-08-30T00:00:00Z")!
        let outlook = try #require(ready(RaceOutlookEngine.status(
            race: .tenK, goalSec: 3_000, raceDate: raceDate, runs: [], now: now,
            raceRecords: [julyTenKRecord])))
        #expect(abs(outlook.sampleHeatDeltaSecPerKm - 12.19) < 0.05)
        #expect(abs(outlook.heatDeltaSecPerKm - 15.60) < 0.05)
        #expect(abs(outlook.predictedSec - 3_034.1) < 0.5)
    }

    // MARK: - 캐시

    @Test("캐시 왕복 — 저장한 기록을 그대로 복원한다")
    func cacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("race-record-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let records = [record(.half, timeSec: 6_330, daysAgo: 100),
                       record(.tenK, timeSec: 2_700, daysAgo: 30)]
        RaceRecordCache.save(records, in: dir)
        #expect(RaceRecordCache.load(from: dir) == records)
    }

    // 이슈 #66 — 디코딩 실패가 전체 기록 유실로 번지지 않게. 격리 파일 시각은 now 고정

    private func makeTempDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("race-record-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func corruptFiles(in dir: URL) throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasPrefix("race-records.corrupt-") }
    }

    @Test("깨진 원소 하나는 건너뛰고 나머지 2건을 살린다 — 원본은 corrupt 파일로 격리")
    func lenientLoadSalvagesAndQuarantines() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let records = [record(.half, timeSec: 6_330, daysAgo: 100),
                       record(.tenK, timeSec: 2_700, daysAgo: 30)]
        // 정상 2건 사이에 raw value가 바뀐 종목("marathon") 원소 1건을 끼운다
        var array = try #require(JSONSerialization.jsonObject(
            with: JSONEncoder().encode(records)) as? [Any])
        array.insert(["id": UUID().uuidString, "race": "marathon",
                      "timeSec": 12_000, "date": 0], at: 1)
        let url = dir.appendingPathComponent(RaceRecordCache.filename)
        let original = try JSONSerialization.data(withJSONObject: array)
        try original.write(to: url)

        #expect(RaceRecordCache.load(from: dir, now: now) == records)
        // 격리 파일 = race-records.corrupt-<now ISO8601>.json, 내용은 원본 그대로
        let corrupt = dir.appendingPathComponent("race-records.corrupt-2026-08-26T09:00:00Z.json")
        #expect(try Data(contentsOf: corrupt) == original)
        #expect(try corruptFiles(in: dir).count == 1)
        // 살린 2건은 다시 저장돼 다음 실행에도 남고, 이번엔 격리가 일어나지 않는다
        #expect(RaceRecordCache.load(from: dir, now: now.addingTimeInterval(60)) == records)
        #expect(try corruptFiles(in: dir).count == 1)
    }

    @Test("파일 전체가 깨져도 원본을 격리한 뒤 빈 목록을 돌려준다")
    func wholeFileCorruptionQuarantines() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = Data("{ not json".utf8)
        try original.write(to: dir.appendingPathComponent(RaceRecordCache.filename))

        #expect(RaceRecordCache.load(from: dir, now: now) == [])
        let corrupt = dir.appendingPathComponent("race-records.corrupt-2026-08-26T09:00:00Z.json")
        #expect(try Data(contentsOf: corrupt) == original)
    }

    @Test("파일이 없으면 nil — 첫 실행은 격리 파일을 만들지 않는다")
    func missingFileReturnsNil() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(RaceRecordCache.load(from: dir, now: now) == nil)
        #expect(try corruptFiles(in: dir).isEmpty)
    }

    @Test("모두 정상이면 그대로 읽고 격리 파일을 만들지 않는다")
    func validFileLoadsUnchanged() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let records = [record(.fiveK, timeSec: 1_200, daysAgo: 10),
                       record(.full, timeSec: 14_400, daysAgo: 200)]
        RaceRecordCache.save(records, in: dir)
        let url = dir.appendingPathComponent(RaceRecordCache.filename)
        let before = try Data(contentsOf: url)

        #expect(RaceRecordCache.load(from: dir, now: now) == records)
        #expect(try Data(contentsOf: url) == before)   // 다시 저장하지도 않는다
        #expect(try corruptFiles(in: dir).isEmpty)
    }

    // MARK: - 자연어 해석 (RaceResultParser.interpret — 순수 로직)

    @Test("시·분·초 합산은 코드가 한다 — 1시간 45분 30초 = 6,330초, 105분 = 6,300초")
    func interpretAssemblesTime() throws {
        let parsed = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: 1, minutes: 45, seconds: 30,
            year: nil, month: nil, day: nil, now: now))
        #expect(parsed.timeSec == 6_330)
        let minutesOnly = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: 105, seconds: nil,
            year: nil, month: nil, day: nil, now: now))
        #expect(minutesOnly.timeSec == 6_300)
    }

    @Test("거리 → 종목 매칭 — 공인 거리 ±15% 안이면 해당 종목, 밖이면 실패")
    func interpretMapsDistance() {
        #expect(RaceResultParser.interpret(
            distanceKm: 21.1, hours: nil, minutes: nil, seconds: nil,
            year: nil, month: nil, day: nil, now: now)?.race == .half)
        #expect(RaceResultParser.interpret(
            distanceKm: 10, hours: nil, minutes: nil, seconds: nil,
            year: nil, month: nil, day: nil, now: now)?.race == .tenK)
        // 3km는 어느 공인 종목과도 15% 밖 — 다른 필드도 없으면 파싱 실패(nil)
        #expect(RaceResultParser.interpret(
            distanceKm: 3, hours: nil, minutes: nil, seconds: nil,
            year: nil, month: nil, day: nil, now: now) == nil)
    }

    @Test("연도 없는 월은 가장 가까운 과거 — 지금 8월에 '10월'은 작년, '3월'은 올해")
    func interpretResolvesRelativeYear() throws {
        let october = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: nil, month: 10, day: nil, now: now)?.date)
        let octoberComps = Calendar.current.dateComponents([.year, .month, .day], from: october)
        #expect(octoberComps.year == 2025 && octoberComps.month == 10 && octoberComps.day == 15)
        let march = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: nil, month: 3, day: nil, now: now)?.date)
        let marchComps = Calendar.current.dateComponents([.year, .month], from: march)
        #expect(marchComps.year == 2026 && marchComps.month == 3)
    }

    @Test("미래 연도는 오독 — 날짜만 버리고 종목·기록은 살린다")
    func interpretDropsFutureYear() throws {
        let parsed = try #require(RaceResultParser.interpret(
            distanceKm: 10, hours: nil, minutes: 50, seconds: nil,
            year: 2027, month: 3, day: nil, now: now))
        #expect(parsed.date == nil)
        #expect(parsed.race == .tenK)
        #expect(parsed.timeSec == 3_000)
    }

    @Test("두 자리 연도는 2000년대 — '25년'은 2025년, 최근 2년 밖 연도는 날짜만 버린다 (이슈 #93)")
    func interpretValidatesYear() throws {
        // now 2026-08-26 → 허용 연도 2024...2026
        let twoDigit = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: 25, month: 10, day: 3, now: now)?.date)
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: twoDigit)
        #expect(comps.year == 2025 && comps.month == 10 && comps.day == 3)
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: 2024, month: 3, day: nil, now: now)?.date != nil)
        // 2023년(3년 전)·'23년'은 범위 밖 → 날짜 nil, 종목은 살린다
        for year in [2023, 23] {
            let parsed = try #require(RaceResultParser.interpret(
                distanceKm: 10, hours: nil, minutes: nil, seconds: nil,
                year: year, month: 10, day: nil, now: now))
            #expect(parsed.date == nil)
            #expect(parsed.race == .tenK)
        }
    }

    @Test("연도가 있으면 그 달 실제 일수로 검증 — '9월 31일'은 10월 1일이 아니라 9월 15일 (이슈 #93)")
    func interpretValidatesDayInMonth() throws {
        let september = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: 2025, month: 9, day: 31, now: now)?.date)
        let sepComps = Calendar.current.dateComponents([.month, .day], from: september)
        #expect(sepComps.month == 9 && sepComps.day == 15)
        // 윤년 2024년 2월 29일은 실재하는 날 — 그대로 둔다
        let leap = try #require(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: nil, seconds: nil,
            year: 2024, month: 2, day: 29, now: now)?.date)
        let leapComps = Calendar.current.dateComponents([.month, .day], from: leap)
        #expect(leapComps.month == 2 && leapComps.day == 29)
    }

    @Test("시·분·초 범위 밖·음수 필드는 버린다 — 시 0~7, 분·초 0~59 (이슈 #93)")
    func interpretValidatesTimeFields() throws {
        // 1시간 −5분 → 분을 버리고 3,600초 (예전엔 3,300초)
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: 1, minutes: -5, seconds: nil,
            year: nil, month: nil, day: nil, now: now)?.timeSec == 3_600)
        // 1시간 60분 75초 → 분·초를 버리고 3,600초
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: 1, minutes: 60, seconds: 75,
            year: nil, month: nil, day: nil, now: now)?.timeSec == 3_600)
        // 8시간은 휠 범위(0..<8) 밖 → 시를 버리고 30분 = 1,800초
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: 8, minutes: 30, seconds: nil,
            year: nil, month: nil, day: nil, now: now)?.timeSec == 1_800)
        // 분만 오면 8시간 미만(479분)까지 허용 — 480분은 버린다
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: 479, seconds: nil,
            year: nil, month: nil, day: nil, now: now)?.timeSec == 28_740)
        // 셋 다 버려지면 timeSec nil — 다른 필드도 없으면 파싱 실패(nil)
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: -1, minutes: nil, seconds: -3,
            year: nil, month: nil, day: nil, now: now) == nil)
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: 480, seconds: nil,
            year: nil, month: nil, day: nil, now: now) == nil)
        let parsed = try #require(RaceResultParser.interpret(
            distanceKm: 10, hours: 9, minutes: nil, seconds: nil,
            year: nil, month: nil, day: nil, now: now))
        #expect(parsed.timeSec == nil)
        #expect(parsed.race == .tenK)
    }

    @Test("거대값도 트랩 없이 버린다 — 범위 검사가 곱셈보다 먼저다 (이슈 #93)")
    func interpretHugeValuesDoNotOverflow() {
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: Int.max, minutes: Int.max, seconds: Int.max,
            year: Int.max, month: nil, day: nil, now: now) == nil)
        #expect(RaceResultParser.interpret(
            distanceKm: nil, hours: nil, minutes: Int.max, seconds: 30,
            year: nil, month: nil, day: nil, now: now)?.timeSec == 30)
    }
}
