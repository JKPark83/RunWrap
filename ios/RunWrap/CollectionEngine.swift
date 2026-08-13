import Foundation

/// 도감에 수집되는 새 종 — 사이클 목표 수준이 종을 정한다 (기획서 §5).
///
/// 매핑은 초안이고 **"서브3 = 백조"만 확정 축**이다. 나머지 경계는 에셋 수급과 함께
/// 확정되므로(§12), 경계값을 `CollectionEngine.species(for:)` 한 곳에만 두고
/// 화면·저장 어디에서도 다시 판정하지 않는다.
enum BirdSpecies: String, Codable, CaseIterable {
    case sparrow    // 참새
    case swallow    // 제비
    case falcon     // 매
    case goose      // 기러기
    case crane      // 두루미
    case swan       // 백조

    var label: String {
        switch self {
        case .sparrow: "참새"
        case .swallow: "제비"
        case .falcon: "매"
        case .goose: "기러기"
        case .crane: "두루미"
        case .swan: "백조"
        }
    }

    /// 도감에서 이 종이 어떤 목표의 증표인지 한 줄로 설명한다 (미수집 칸의 힌트로도 쓴다)
    var goalHint: String {
        switch self {
        case .sparrow: "목표 없이 완주 습관"
        case .swallow: "5K · 10K"
        case .falcon: "하프"
        case .goose: "풀코스 완주"
        case .crane: "풀코스 sub-4"
        case .swan: "풀코스 서브3"
        }
    }
}

/// 도감에 수록된 새 한 마리 — 성조 이미지 + 당시 목표 + 수집일 (기획서 §5)
struct CollectedBird: Codable, Equatable, Identifiable {
    let id: UUID
    let species: BirdSpecies
    /// 수집 시점의 목표를 사람이 읽는 문장으로 굳혀 둔다.
    /// 목표 설정은 나중에 바뀌므로 rawValue가 아니라 **그때의 표기**를 남긴다 —
    /// 도감은 이력이라 과거 항목이 현재 설정을 따라 바뀌면 안 된다.
    let goalLabel: String
    let collectedAt: Date
    /// 이 사이클이 걸린 일수 — 도감에서 "27일 만에" 같은 이력 표기에 쓴다
    let cycleDays: Int

    init(id: UUID = UUID(), species: BirdSpecies, goalLabel: String,
         collectedAt: Date, cycleDays: Int) {
        self.id = id
        self.species = species
        self.goalLabel = goalLabel
        self.collectedAt = collectedAt
        self.cycleDays = cycleDays
    }
}

/// 성조 도달 → 수집 → 새 사이클 시작을 계산하는 순수 로직 (기획서 §5).
///
/// 엔진 계층 규칙 그대로 Foundation만 import하고 `now`를 주입받는다.
/// 저장·화면 전환은 `CollectionStore`와 화면이 맡고, 여기서는 **무엇을 수집하고
/// 다음 사이클을 어떤 값으로 시작할지**만 정한다.
enum CollectionEngine {
    /// 서브3 기준 — 풀코스 3시간. "서브3 = 백조"가 §5의 확정 축이다
    private static let sub3Seconds = 3 * 3_600
    /// sub-4 기준 — 풀코스 4시간. 두루미와 기러기의 경계
    private static let sub4Seconds = 4 * 3_600

    /// 사이클 목표 → 새 종 (기획서 §5 매핑표).
    ///
    /// - Parameters:
    ///   - distance: 그 사이클의 목표 종목. nil이면 목표 없음(완주 습관) → 참새
    ///   - goalSeconds: 목표 기록(초). 0 이하는 미입력으로 본다 — 풀코스에서만 종을 가른다
    static func species(for distance: RaceDistance?, goalSeconds: Int) -> BirdSpecies {
        guard let distance else { return .sparrow }
        switch distance {
        case .fiveK, .tenK: return .swallow
        case .half: return .falcon
        case .full:
            // 목표 기록 미입력이면 "완주"로 본다 — 기러기.
            // 기록을 적어 낸 경우에만 sub-4 / 서브3로 승격한다
            guard goalSeconds > 0 else { return .goose }
            if goalSeconds < sub3Seconds { return .swan }
            if goalSeconds < sub4Seconds { return .crane }
            return .goose
        }
    }

    /// 목표를 도감에 남길 문장으로 굳힌다 — 종목 + (있으면) 목표 기록.
    /// 예: "풀코스 3:30:00" · "하프" · "목표 없이 완주 습관"
    static func goalLabel(for distance: RaceDistance?, goalSeconds: Int) -> String {
        guard let distance else { return BirdSpecies.sparrow.goalHint }
        guard goalSeconds > 0 else { return distance.label }
        return "\(distance.label) \(Format.duration(Double(goalSeconds)))"
    }

    /// 성조에 도달했는지 — 수집 세러모니를 띄울 조건.
    ///
    /// 미노출 가드와 같은 결의 판정이다: 단계가 실제로 `flying`일 때만 true다.
    static func hasReachedAdult(stage: GrowthStage) -> Bool {
        stage.next == nil
    }

    /// 수집할 새를 만든다. 사이클 시작 시각과 지금으로 소요 일수를 함께 굳힌다.
    static func collect(distance: RaceDistance?, goalSeconds: Int,
                        cycleStartedAt: Date, now: Date,
                        id: UUID = UUID()) -> CollectedBird {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let days = calendar.dateComponents([.day],
                                            from: calendar.startOfDay(for: cycleStartedAt),
                                            to: calendar.startOfDay(for: now)).day ?? 0
        return CollectedBird(id: id,
                             species: species(for: distance, goalSeconds: goalSeconds),
                             goalLabel: goalLabel(for: distance, goalSeconds: goalSeconds),
                             collectedAt: now,
                             cycleDays: max(0, days))
    }

    /// 다음 목표 추천 — 직전 목표를 한 칸 올린다 (기획서 §5 "직전 목표·최근 기록 기반").
    ///
    /// 풀코스에 이미 도달한 뒤에는 종목을 더 올릴 수 없으므로 **기록 단축**으로 방향을 튼다.
    /// 추천일 뿐이라 화면에서 직접 설정으로 바꿀 수 있다.
    ///
    /// - Returns: 추천 종목과 추천 목표 기록(초, 0이면 기록 목표 없이 완주). 더 올릴 곳이
    ///   없으면 nil — 그 경우 화면은 추천 대신 직접 설정만 안내한다
    static func recommendedGoal(after distance: RaceDistance?,
                                 goalSeconds: Int) -> (distance: RaceDistance, seconds: Int)? {
        switch distance {
        case .none: return (.fiveK, 0)
        case .fiveK: return (.tenK, 0)
        case .tenK: return (.half, 0)
        case .half: return (.full, 0)
        case .full:
            // 종목은 끝 — 기록을 당긴다. 미입력이면 우선 sub-4를 제안하고,
            // 이미 목표가 있으면 30분씩 당기되 서브3 아래로는 내리지 않는다.
            guard goalSeconds > 0 else { return (.full, sub4Seconds) }
            let tightened = goalSeconds - 30 * 60
            guard tightened >= sub3Seconds else { return nil }
            return (.full, tightened)
        }
    }
}
