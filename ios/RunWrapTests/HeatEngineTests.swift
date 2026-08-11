import Foundation
import Testing
@testable import RunWrap

/// 열 보정 페이스 엔진 — Magnus 이슬점 → 열 점수 → 보정량 산식과 미노출 가드 검증
struct HeatEngineTests {
    @Test("서늘한 날 — 15°C/50%는 열 점수 20 안팎이라 보정 없이 nil")
    func coolDayReturnsNil() {
        // 15°C/50% → 이슬점 ≈4.65 → 열 점수 ≈19.65 (38 이하) → 보정 없음
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: 15, humidityPct: 50) == nil)
    }

    @Test("전형적 여름 — 28°C/70%는 이슬점 22.0·열 점수 50.0으로 24초/km 보정")
    func typicalSummerDay() throws {
        // 28°C/70% → 이슬점 ≈22.0 → 열 점수 ≈50.0
        // → (46−38)×1.5 + (50.0−46)×3.0 = 12 + 12.0 = 24.0초/km
        let result = try #require(HeatEngine.adjustment(paceSecPerKm: 370, tempC: 28, humidityPct: 70))
        #expect(abs(result.heatScore - 50.0) < 0.5)
        #expect(abs(result.deltaSecPerKm - 24.0) < 0.5)
        #expect(abs(result.adjustedPaceSecPerKm - 346.0) < 0.5)
    }

    @Test("폭염 — 38°C/95%는 보정량이 상한 90초/km에 클램프된다")
    func extremeHeatClamps() throws {
        // 38°C/95% → 이슬점 ≈37.0 → 열 점수 ≈75.0 → 원 보정량 ≈99초 → 90으로 클램프
        let result = try #require(HeatEngine.adjustment(paceSecPerKm: 300, tempC: 38, humidityPct: 95))
        #expect(result.deltaSecPerKm == 90.0)
        #expect(abs(result.adjustedPaceSecPerKm - 210.0) < 0.001)
    }

    @Test("습도 이상치·결측 — 0%·110%, 기온·습도 nil은 모두 nil")
    func invalidOrMissingInputsReturnNil() {
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: 25, humidityPct: 0) == nil)
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: 25, humidityPct: 110) == nil)
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: nil, humidityPct: 70) == nil)
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: 25, humidityPct: nil) == nil)
    }

    @Test("경계 노이즈 — 24°C/60%는 열 점수 38 언저리라 보정량 3초 미만으로 nil")
    func boundaryNoiseReturnsNil() {
        // 24°C/60% → 이슬점 ≈15.75 → 열 점수 ≈39.75 → 보정량 ≈2.63초/km (< 3) → nil
        #expect(HeatEngine.adjustment(paceSecPerKm: 360, tempC: 24, humidityPct: 60) == nil)
    }

    @Test("보정 페이스 관계 — adjustedPace는 항상 pace − delta")
    func adjustedPaceEqualsRawMinusDelta() throws {
        let pace = 400.0
        let result = try #require(HeatEngine.adjustment(paceSecPerKm: pace, tempC: 30, humidityPct: 75))
        #expect(abs(result.adjustedPaceSecPerKm - (pace - result.deltaSecPerKm)) < 0.0001)
    }
}
