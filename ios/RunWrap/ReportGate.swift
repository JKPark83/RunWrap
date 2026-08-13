import Foundation

/// 리포트 레벨 게이트 (기획서 v0.7 §4) — 어떤 카드를 레벨에 따라 보여줄지 판정하는 순수 로직.
///
/// **왜 엔진에 두는가.** §4는 B안(모든 레벨이 같은 화면, 엔진이 콘텐츠 수준을 차별화)을
/// 채택했다. "무엇을 보여줄지"는 3계층 규칙상 엔진 소관이므로, 화면에서 `if level == ...`을
/// 흩뿌리는 대신 이 한곳에서 매트릭스를 표로 관리한다. 승급하면 같은 화면에서 카드가 해금된다.
///
/// **가드 우선순위** — 미노출 가드(표본 부족)가 레벨 게이트보다 항상 위다 (§4).
/// 이 파일은 "레벨이 허용하는가"만 답한다. 엔진이 nil을 냈으면 화면은 애초에 그리지 않으므로
/// 두 판정은 AND로 결합된다: `if let acwr = report.acwr, ReportGate.shows(.acwr, level: level)`.
/// 순서를 뒤집어 게이트만 보고 그리는 코드를 쓰지 않는다.
enum ReportCard: CaseIterable {
    /// 주간 거리 — 런린이는 수치 없이 문장만 (`showsNumbers`로 구분)
    case distance
    /// 체력 배터리 — 전 레벨 공통 (문장 난이도만 다르다)
    case battery
    /// 훈련 부하 (ACWR)
    case acwr
    /// 심박 효율 (EF)
    case efficiency
    /// 심폐 체력 (VO₂max) 추세
    case vo2Max
    /// 주법 (케이던스·보폭·상하 진폭)
    case form
    /// 크로스 트레이닝 요약 — 전 레벨 공통
    case crossTraining
    /// 훈련 가이드 — 전 레벨 공통 (처방 내용은 TrainingGuideEngine이 레벨로 조정)
    case trainingGuide
    /// 걷뛰(걷기-뛰기) 프로그램 — 런린이 전용 입문 카드 (§4 "더하는 차별화")
    case walkRun
}

enum ReportGate {
    /// 기획서 §4 "지표 노출 매트릭스" 그대로. 표에 없는 조합이 생기면 여기부터 고친다.
    ///
    /// | 카드 | 런린이 | 런잘알 | 런친놈 |
    /// |---|---|---|---|
    /// | 거리·체력 배터리·크로스·가이드 | ● | ● | ● |
    /// | 주간 거리 수치 | 문장만 | ● | ● |
    /// | ACWR · EF · VO₂max · 주법 | – | ● | ● |
    /// | 걷뛰 | ● | – | – |
    static func shows(_ card: ReportCard, level: RunnerLevel) -> Bool {
        switch card {
        case .distance, .battery, .crossTraining, .trainingGuide:
            true
        case .acwr, .efficiency, .vo2Max, .form:
            level != .beginner
        case .walkRun:
            level == .beginner
        }
    }

    /// 카드 안에서 **수치**를 노출해도 되는 레벨인가.
    /// 런린이의 주간 거리는 카드 자체는 나오되 숫자 없이 문장만 나간다 (§4 "문장만").
    static func showsNumbers(_ card: ReportCard, level: RunnerLevel) -> Bool {
        guard shows(card, level: level) else { return false }
        if card == .distance, level == .beginner { return false }
        return true
    }
}
