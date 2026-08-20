package com.jkpark.runwrap.engine

/// 리포트 레벨 게이트 (기획서 v0.7 §4) — iOS `ReportGate.swift` 이식.
/// 어떤 카드를 레벨에 따라 보여줄지 판정하는 순수 로직.
///
/// **가드 우선순위** — 미노출 가드(표본 부족)가 레벨 게이트보다 항상 위다 (§4).
/// 이 파일은 "레벨이 허용하는가"만 답한다. 엔진이 null을 냈으면 화면은 애초에 그리지 않으므로
/// 두 판정은 AND로 결합된다: `report.acwr?.takeIf { ReportGate.shows(ACWR, level) }` 순서.
enum class ReportCard {
    /// 주간 거리 — 런린이는 수치 없이 문장만 (`showsNumbers`로 구분)
    DISTANCE,
    /// 체력 배터리 — 전 레벨 공통 (문장 난이도만 다르다)
    BATTERY,
    /// 훈련 부하 (ACWR)
    ACWR,
    /// 심박 효율 (EF)
    EFFICIENCY,
    /// 심폐 체력 (VO₂max) 추세
    VO2_MAX,
    /// 주법 (케이던스 추이 — Android는 세션별 3종이 없어 이 카드만)
    FORM,
    /// 크로스 트레이닝 요약 — 전 레벨 공통
    CROSS_TRAINING,
    /// 훈련 가이드 — 전 레벨 공통 (처방 내용은 TrainingGuideEngine이 레벨로 조정)
    TRAINING_GUIDE,
    /// 걷뛰(걷기-뛰기) 프로그램 — 런린이 전용 입문 카드 (§4 "더하는 차별화")
    WALK_RUN,
}

object ReportGate {
    /// 기획서 §4 "지표 노출 매트릭스" 그대로. 표에 없는 조합이 생기면 여기부터 고친다.
    ///
    /// | 카드 | 런린이 | 런잘알 | 런친놈 |
    /// |---|---|---|---|
    /// | 거리·체력 배터리·크로스·가이드 | ● | ● | ● |
    /// | 주간 거리 수치 | 문장만 | ● | ● |
    /// | ACWR · EF · VO₂max · 주법 | – | ● | ● |
    /// | 걷뛰 | ● | – | – |
    fun shows(card: ReportCard, level: RunnerLevel): Boolean = when (card) {
        ReportCard.DISTANCE, ReportCard.BATTERY,
        ReportCard.CROSS_TRAINING, ReportCard.TRAINING_GUIDE -> true
        ReportCard.ACWR, ReportCard.EFFICIENCY,
        ReportCard.VO2_MAX, ReportCard.FORM -> level != RunnerLevel.BEGINNER
        ReportCard.WALK_RUN -> level == RunnerLevel.BEGINNER
    }

    /// 카드 안에서 **수치**를 노출해도 되는 레벨인가.
    /// 런린이의 주간 거리는 카드 자체는 나오되 숫자 없이 문장만 나간다 (§4 "문장만").
    fun showsNumbers(card: ReportCard, level: RunnerLevel): Boolean {
        if (!shows(card, level)) return false
        if (card == ReportCard.DISTANCE && level == RunnerLevel.BEGINNER) return false
        return true
    }
}
