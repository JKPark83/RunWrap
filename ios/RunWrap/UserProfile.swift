import Foundation

/// 사용자 프로필 — 러닝 목적·레벨 (기획서 §4.5, 계획서 M2)
///
/// 값은 UserDefaults(@AppStorage)에 rawValue로 저장한다. 엔진은 프로필을 모른다 —
/// 카드 포함 여부·순서는 화면이 분기하고, 문장 난이도만 level 값 입력으로 전달한다.

/// 러닝 목적 — 리포트 홈의 카드 구성이 바뀐다 (다이어트: 칼로리·몸무게·streak 우선)
enum RunGoal: String, CaseIterable {
    case diet, training

    var label: String {
        switch self {
        case .diet: "다이어트"
        case .training: "훈련"
        }
    }

    var caption: String {
        switch self {
        case .diet: "칼로리·몸무게 변화를 앞세워 보여드려요"
        case .training: "훈련 부하와 회복 균형을 앞세워 보여드려요"
        }
    }
}

/// 러닝 레벨 — 인사이트 문장의 난이도가 바뀐다 (초보: 지표 용어 대신 쉬운 표현)
/// 노출 라벨은 런미새 위트 톤(기획서 §4.11)에 맞춘 별칭 — 저장값(rawValue)은 그대로 둔다
enum RunLevel: String, CaseIterable {
    case beginner, experienced

    var label: String {
        switch self {
        case .beginner: "런린이"
        case .experienced: "런친놈"
        }
    }

    var caption: String {
        switch self {
        case .beginner: "용어 대신 쉬운 문장으로 설명해요"
        case .experienced: "ACWR·EF 같은 지표 용어를 그대로 써요"
        }
    }
}

/// 목표 레이스 종목 — 훈련 가이드(계획서 M7)의 진단 대상. rawValue로 저장한다
enum RaceDistance: String, CaseIterable {
    case fiveK, tenK, half, full

    var label: String {
        switch self {
        case .fiveK: "5K"
        case .tenK: "10K"
        case .half: "하프"
        case .full: "풀"
        }
    }

    /// 목적격 조사 — "풀"만 받침이 있어 "을"이 붙는다. 새 종목 추가 시 함께 지정
    var objectParticle: String {
        switch self {
        case .fiveK, .tenK, .half: "를"
        case .full: "을"
        }
    }

    /// 공인 거리 (km) — PersonalRecords.targets와 같은 값
    var km: Double {
        switch self {
        case .fiveK: 5.0
        case .tenK: 10.0
        case .half: 21.0975
        case .full: 42.195
        }
    }
}

/// @AppStorage 키 모음 — 화면마다 문자열을 다시 치지 않도록 한곳에 둔다
enum ProfileKey {
    static let goal = "profile.goal"
    static let level = "profile.level"
    /// 온보딩 프로필 스텝을 마쳤는지 — M2 이전 사용자는 false라 다음 실행에 한 번 노출된다
    static let didSetProfile = "profile.didSet"
    /// 목표 레이스 종목 (RaceDistance rawValue) — 빈 문자열이면 미설정 → 훈련 가이드 카드 미노출
    static let raceGoal = "profile.raceGoal"
    /// 목표 기록 (초) — 0이면 미입력으로 취급해 예측만 보여준다
    static let raceGoalSec = "profile.raceGoalSec"
}
