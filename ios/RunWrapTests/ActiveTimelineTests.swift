import Foundation
import Testing
@testable import RunWrap

/// 활동 타임라인 엔진 — 정지 구간 짝맞춤, 활동 초·벽시계 변환, 정지를 뺀 km 스플릿을 검증한다 (이슈 #47).
struct ActiveTimelineTests {
    /// 고정 시각 — 모든 시각은 여기서부터의 상대 초로 만든다
    private let start = ISO8601DateFormatter().date(from: "2026-08-10T07:00:00+09:00")!

    private func at(_ sec: TimeInterval) -> Date { start.addingTimeInterval(sec) }

    private func interval(_ from: TimeInterval, _ to: TimeInterval) -> DateInterval {
        DateInterval(start: at(from), end: at(to))
    }

    /// from초부터 1km/300초 등속으로 meters만큼 10초 간격 거리 샘플을 깐다 (샘플당 1000/30 ≈ 33.33m)
    private func constantSamples(from: TimeInterval, meters: Double)
        -> [(start: Date, end: Date, meters: Double)] {
        let perSample = 1_000.0 / 30
        let count = Int((meters / perSample).rounded())
        return (0..<count).map { i in
            (start: at(from + Double(i) * 10), end: at(from + Double(i + 1) * 10), meters: perSample)
        }
    }

    @Test("사용자 정지와 모션 정지 표식이 각각 짝지어 정지 구간이 된다")
    func pairsUserAndMotionMarkers() {
        let markers: [(date: Date, kind: ActiveTimeline.Marker)] = [
            (date: at(1_560), kind: .motionResume),  // 순서가 섞여 들어와도 날짜순으로 정렬한다
            (date: at(600), kind: .pause),
            (date: at(1_500), kind: .motionPause),
            (date: at(690), kind: .resume),
        ]

        let pauses = ActiveTimeline.pauses(markers: markers, start: start, end: at(3_600))

        // [600–690] 90초 + [1500–1560] 60초 = 정지 합 150초
        #expect(pauses == [interval(600, 690), interval(1_500, 1_560)])
        #expect(pauses.reduce(0) { $0 + $1.duration } == 150)
    }

    @Test("사용자 정지 중 모션 정지가 끼어도 사용자 재개 때까지 정지로 본다")
    func overlappingUserAndMotionPauseStaysPaused() {
        let markers: [(date: Date, kind: ActiveTimeline.Marker)] = [
            (date: at(100), kind: .pause),
            (date: at(110), kind: .motionPause),
            (date: at(150), kind: .motionResume),  // 아직 사용자 정지 중 — 여기서 닫히면 50초를 덜 뺀다
            (date: at(200), kind: .resume),
        ]

        let pauses = ActiveTimeline.pauses(markers: markers, start: start, end: at(3_600))

        #expect(pauses == [interval(100, 200)])
    }

    @Test("짝 없는 재개·중복 정지는 무시하고, 닫히지 않은 정지는 종료 시각에서 닫는다")
    func irregularMarkers() {
        let markers: [(date: Date, kind: ActiveTimeline.Marker)] = [
            (date: at(100), kind: .resume),   // 짝 없음 → 무시
            (date: at(1_000), kind: .pause),
            (date: at(1_050), kind: .pause),  // 이미 정지 중 → 무시
            (date: at(1_100), kind: .resume),
            (date: at(3_500), kind: .pause),  // 재개 없음 → end(3600)에서 닫는다
        ]

        let pauses = ActiveTimeline.pauses(markers: markers, start: start, end: at(3_600))

        // [1000–1100] 100초 + [3500–3600] 100초
        #expect(pauses == [interval(1_000, 1_100), interval(3_500, 3_600)])
    }

    @Test("활동 초는 정지 구간과 겹친 부분만 뺀다")
    func activeSecondsSubtractsPartialOverlap() {
        // 벽시계 0–1000 중 정지 900–1100과 겹친 900–1000(100초)만 뺀다 → 1000 − 100 = 900
        let active = ActiveTimeline.activeSeconds(from: start, to: at(1_000), pauses: [interval(900, 1_100)])
        #expect(active == 900)
    }

    @Test("활동 경과 시간은 앞선 정지 길이만큼 벽시계로 밀린다")
    func wallTimeSkipsPrecedingPause() {
        // 활동 1800초 = 정지 전 600초 + 정지(90초) 뒤 1200초 → 벽시계 600 + 90 + 1200 = 1890
        let wall = ActiveTimeline.wallTime(afterActive: 1_800, from: start, pauses: [interval(600, 690)])
        #expect(wall == at(1_890))
    }

    @Test("정지가 없으면 스플릿은 경계 사이 벽시계 시간 그대로다")
    func splitsWithoutPausesMatchWallClock() {
        let samples = constantSamples(from: 0, meters: 3_000)  // 1km/300초 × 3km

        let splits = ActiveTimeline.splits(distanceSamples: samples, pauses: [])

        // 33.33m 누적의 부동소수점 오차로 경계가 이웃 샘플로 밀릴 수 있어 허용오차로 비교한다
        #expect(splits.map(\.index) == [1, 2, 3])
        for split in splits {
            #expect(abs(split.paceSecPerKm - 300) < 0.01)
        }
    }

    @Test("스플릿에서 정지 구간을 빼면 신호 대기가 있던 km도 제 페이스로 나온다")
    func splitsSubtractPause() {
        // 1km/300초 등속, 벽시계 450초(1.5km 지점)부터 90초 정지 — 정지 중에는 샘플이 없다
        let samples = constantSamples(from: 0, meters: 1_500) + constantSamples(from: 540, meters: 1_500)
        let pause = interval(450, 540)

        let splits = ActiveTimeline.splits(distanceSamples: samples, pauses: [pause])

        // 2km 통과 벽시계 = 540 + 500m×0.3초/m = 690. 1km 통과는 300.
        // 벽시계 차이 690 − 300 = 390에서 정지 90초를 빼 300
        #expect(splits.map(\.index) == [1, 2, 3])
        for split in splits {
            #expect(abs(split.paceSecPerKm - 300) < 0.01)
        }

        // 비교: 정지 구간을 모르면(수정 전 동작) 2km가 390초로 튄다
        let naive = ActiveTimeline.splits(distanceSamples: samples, pauses: [])
        #expect(abs(naive[1].paceSecPerKm - 390) < 0.01)
    }

    @Test("60초/km 미만 구간은 데이터 오류로 빼되 km 번호는 밀리지 않는다")
    func splitsDropImplausibleKmKeepingIndex() {
        let samples: [(start: Date, end: Date, meters: Double)] = [
            (start: at(0), end: at(50), meters: 1_000),     // 50초에 1km — 데이터 오류
            (start: at(50), end: at(350), meters: 1_000),   // 300초에 1km
        ]

        let splits = ActiveTimeline.splits(distanceSamples: samples, pauses: [])

        // 1km는 50초 ≤ 60초라 제외, 2km는 350 − 50 = 300초
        #expect(splits.map(\.index) == [2])
        #expect(abs(splits[0].paceSecPerKm - 300) < 0.01)
    }

    @Test("이벤트가 없을 때는 10초 이상 벌어진 거리 샘플 공백만 정지로 본다")
    func gapPausesFallback() {
        let samples: [(start: Date, end: Date, meters: Double)] = [
            (start: at(0), end: at(10), meters: 30),
            (start: at(12), end: at(22), meters: 30),     // 공백 2초 → 정상
            (start: at(112), end: at(122), meters: 30),   // 공백 90초 → 정지
        ]

        #expect(ActiveTimeline.gapPauses(samples) == [interval(22, 112)])
    }
}
