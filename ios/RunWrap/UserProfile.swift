import Foundation

/// 사용자 프로필 — 러너 레벨·러닝 목적 (기획서 v0.7 §3, §9)
///
/// 값은 UserDefaults(@AppStorage)에 rawValue로 저장한다. 엔진은 프로필을 모른다 —
/// 카드 포함 여부는 `ReportGate`가 정하고, 문장 난이도만 level 값 입력으로 전달한다.

/// 러너 레벨 3단계 (기획서 §3) — 온보딩 설문 결과로 판정하고, 실기록으로 승급만 한다.
///
/// 노출 라벨은 런미새 위트 톤(§4.11)의 별칭이다. 저장값(rawValue)은 영문 그대로 둬서
/// 라벨 문구를 바꿔도 기존 사용자의 저장값이 깨지지 않게 한다.
enum RunnerLevel: String, CaseIterable {
    case beginner, intermediate, advanced

    var label: String {
        switch self {
        case .beginner: "런린이"
        case .intermediate: "런잘알"
        case .advanced: "런친놈"
        }
    }

    /// 레벨 비교용 서열 — 승급 판정(`LevelEngine.promotionCandidate`)에서 쓴다.
    /// CaseIterable 순서에 기대지 않도록 명시값으로 둔다.
    var rank: Int {
        switch self {
        case .beginner: 0
        case .intermediate: 1
        case .advanced: 2
        }
    }
}

/// 러닝 목적 (기획서 §2 Q9) — 복수 선택. 리포트 문장의 강조점에 쓴다.
///
/// 목적은 카드를 켜고 끄지 않는다 (그건 레벨 게이트의 몫) — 어떤 목적을 골라도
/// 보이는 카드는 같고, 문장이 무엇을 앞세우는지만 달라진다.
enum RunPurpose: String, Codable, CaseIterable {
    case health, weight, record, race, mood

    var label: String {
        switch self {
        case .health: "건강 관리"
        case .weight: "체중 관리"
        case .record: "기록 향상"
        case .race: "대회 준비"
        case .mood: "기분 전환"
        }
    }

    /// 복수 선택이라 @AppStorage에 넣으려면 한 문자열로 접어야 한다.
    /// 쉼표 구분 — rawValue에 쉼표가 없으므로 안전하다.
    static func encode(_ purposes: [RunPurpose]) -> String {
        purposes.map(\.rawValue).joined(separator: ",")
    }

    static func decode(_ raw: String) -> [RunPurpose] {
        raw.split(separator: ",").compactMap { RunPurpose(rawValue: String($0)) }
    }
}

/// 목표 레이스 종목 — 훈련 가이드·목표 기록 프리셋의 기준
enum RaceDistance: String, Codable, CaseIterable {
    case fiveK, tenK, half, full

    var label: String {
        switch self {
        case .fiveK: "5K"
        case .tenK: "10K"
        case .half: "하프"
        case .full: "풀코스"
        }
    }

    /// label 뒤에 붙는 목적격 조사 — "하프를", "5K를"처럼 문장을 자연스럽게 잇는다.
    /// 종결 음절의 받침 유무로 갈린다: 5K·10K는 "케이"(받침 없음), 하프도 받침 없음,
    /// 풀코스는 "스"로 끝나 받침이 없다 — 현재 4종은 모두 "를"이지만,
    /// 종목이 늘어날 때 호출부가 아니라 이곳만 고치도록 케이스로 남겨 둔다.
    var objectParticle: String {
        switch self {
        case .fiveK, .tenK, .half, .full: "를"
        }
    }

    /// 공식 거리(km) — Riegel 예측·목표 페이스 환산에 쓴다
    var km: Double {
        switch self {
        case .fiveK: 5
        case .tenK: 10
        case .half: 21.0975
        case .full: 42.195
        }
    }
}

/// @AppStorage 키 모음 — 화면마다 문자열을 다시 치지 않도록 한곳에 둔다.
///
/// v0.7에서 레벨 체계가 바뀌어 `levelV2`를 새로 쓴다. v1 키(`profile.level`·`profile.goal`)는
/// 더 쓰지 않지만, `profile.didSet`은 RootView가 "기존 사용자 재온보딩" 판정에 한 번 읽고 지운다.
enum ProfileKey {
    /// 러너 레벨 (RunnerLevel rawValue) — 빈 문자열이면 온보딩 미완료
    static let levelV2 = "profile.levelV2"
    /// 러닝 목적 복수 선택 (쉼표 구분 rawValue)
    static let purposes = "profile.purposes"
    /// 주간 러닝 목표 횟수 — 성장 XP의 주간 보너스 분모이자 홈 목표 칩의 기준
    static let weeklyGoal = "profile.weeklyGoal"
    /// 온보딩 완료 시각 (timeIntervalSince1970)
    static let onboardedAt = "profile.onboardedAt"
    /// 승급 제안을 거절한 시각 — 4주간 다시 묻지 않는다 (§3)
    static let promotionDeclinedAt = "profile.promotionDeclinedAt"
    /// 목표 레이스 종목 (RaceDistance rawValue) — 빈 문자열이면 미설정 → 훈련 가이드 카드 미노출
    /// v1과 같은 키를 그대로 쓴다 — 재온보딩해도 기존 목표가 보존된다
    static let raceGoal = "profile.raceGoal"
    /// 목표 기록 (초) — 0이면 미입력으로 취급해 예측만 보여준다
    static let raceGoalSec = "profile.raceGoalSec"
    /// 대회 날짜 (timeIntervalSince1970) — 0이면 미설정. 있으면 훈련 가이드가
    /// D-day 주기화(기초→강화→피크→테이퍼)로 주간 처방을 조절한다
    static let raceDate = "profile.raceDate"
}

/// 성장 시스템 저장 키 (기획서 §5).
///
/// XP 원장은 저장하지 않는다 — 사이클 시작 이후 HealthKit 이력에서 매번 재계산한다.
/// 저장할 값은 사이클 시작 시각과 이번 사이클 최고 단계뿐이다.
enum GrowthKey {
    /// 현재 성장 사이클 시작 시각 (timeIntervalSince1970). 첫 사이클 = 온보딩 시각
    static let cycleStartedAt = "growth.cycleStartedAt"
    /// 이번 사이클에서 도달한 최고 단계 — "성장은 되돌리지 않는다"의 구현 장치
    static let maxStage = "growth.maxStage"
    /// 사이클 식별자 (UUID 문자열) — CloudKit 스냅샷 병합에서 같은 사이클인지 판정한다 (이슈 #29).
    /// 온보딩·사이클 전환 때 새로 발급하고, 없으면 백업 시점에 최초 1회 만든다
    static let cycleID = "growth.cycleID"
}
