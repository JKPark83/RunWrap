package com.jkpark.runwrap.engine

/// 온보딩 설문 원답 — 카드 플로우(기획서 §2) Q1~Q9의 답을 그대로 보존한다.
/// 재진단("다시 진단받기", §3·§7) 시 이전 답을 프리필하는 데 쓰고,
/// `LevelEngine.decide`의 입력이 되어 판정 근거로도 남는다.
/// iOS `OnboardingAnswers.swift` 이식 — 영속화(OnboardingAnswersStore)는 스토어 계층(M5) 몫.
data class OnboardingAnswers(
    val q1Experience: Q1,
    val q2aActivity: Q2A? = null,
    val q2Longest: Q2Longest? = null,
    val q3Record: Q3Record? = null,
    val q4Monthly: Q4Monthly? = null,
    val q5Frequency: Q5Frequency? = null,
    val q6Race: Q6Race? = null,
    /// Q7 — 다음 목표는 어떤 거리인가요? (null = "아직 없어요")
    val q7Target: RaceDistance? = null,
    /// Q8 — 목표 기록(초). 스킵("일단 완주부터요")이면 null
    val q8GoalSec: Int? = null,
    /// Q9 — 달리는 이유, 최대 2개
    val q9Purposes: List<RunPurpose> = emptyList(),
) {
    /// Q1 — 러닝, 해보신 적 있나요? (플로우 분기)
    enum class Q1(val storageValue: String) {
        NOVICE("novice"), EXPERIENCED("experienced");

        val label: String
            get() = when (this) {
                NOVICE -> "이제 시작해요"
                EXPERIENCED -> "좀 달려봤어요"
            }
    }

    /// Q2a — 요즘 몸은 좀 움직이고 계세요? (무경험 분기 전용, 걷뛰 시작 강도)
    enum class Q2A(val storageValue: String) {
        SEDENTARY("sedentary"), WALKING("walking"), OTHER_SPORT("otherSport");

        val label: String
            get() = when (this) {
                SEDENTARY -> "주로 앉아 지내요"
                WALKING -> "걷기는 챙겨 해요"
                OTHER_SPORT -> "다른 운동을 하고 있어요"
            }
    }

    /// Q2 — 지금까지 가장 멀리 달려본 거리는요? (레벨 판정 ①, Q3 분기)
    enum class Q2Longest(val storageValue: String) {
        UNDER_5("under5"), FIVE_TO_TEN("fiveToTen"), TEN_TO_HALF("tenToHalf"),
        HALF_TO_FULL("halfToFull"), FULL_FINISHER("fullFinisher");

        val label: String
            get() = when (this) {
                UNDER_5 -> "5km 미만"
                FIVE_TO_TEN -> "5~10km"
                TEN_TO_HALF -> "10km~하프"
                HALF_TO_FULL -> "하프~풀"
                FULL_FINISHER -> "풀코스 완주"
            }
    }

    /// Q3 — 풀 완주자는 풀코스 기록, 그 외는 10km 기록을 묻는다 (런친놈 조건① / 중급 판정)
    enum class Q3Record(val storageValue: String) {
        // 풀코스 완주자 분기
        FULL_UNDER_430("fullUnder430"), FULL_430_TO_5("full430to5"), FULL_OVER_5("fullOver5"),
        // 10km 분기
        TEN_UNDER_60("tenUnder60"), TEN_OVER_60("tenOver60"), TEN_UNKNOWN("tenUnknown");

        val label: String
            get() = when (this) {
                FULL_UNDER_430 -> "4시간 30분 이내"
                FULL_430_TO_5 -> "4:30~5시간"
                FULL_OVER_5 -> "5시간 넘게"
                TEN_UNDER_60 -> "1시간 이내"
                TEN_OVER_60 -> "1시간 조금 넘게"
                TEN_UNKNOWN -> "재본 적 없어요"
            }
    }

    /// Q4 — 한 달에 보통 얼마나 달리세요? (런친놈 조건②)
    enum class Q4Monthly(val storageValue: String) {
        UNDER_50("under50"), FIFTY_TO_100("fiftyTo100"),
        HUNDRED_TO_200("hundredTo200"), OVER_200("over200");

        val label: String
            get() = when (this) {
                UNDER_50 -> "50km 미만"
                FIFTY_TO_100 -> "50~100km"
                HUNDRED_TO_200 -> "100~200km"
                OVER_200 -> "200km 이상"
            }
    }

    /// Q5 — 일주일에 몇 번 나가세요? (주간 러닝 목표 초기값 — §5 XP 연동)
    enum class Q5Frequency(val storageValue: String) {
        RARE("rare"), TWO_OR_THREE("twoOrThree"), FOUR_PLUS("fourPlus");

        val label: String
            get() = when (this) {
                RARE -> "한 번 나갈까 말까"
                TWO_OR_THREE -> "2~3번"
                FOUR_PLUS -> "4번 이상"
            }

        /// 이 답이 뜻하는 주간 러닝 목표(회) — 기획서 §2 "1·3·4회"
        val weeklyGoal: Int
            get() = when (this) {
                RARE -> 1
                TWO_OR_THREE -> 3
                FOUR_PLUS -> 4
            }
    }

    /// Q6 — 대회는 나가보셨어요? (대회 탭 안내·가이드 톤)
    enum class Q6Race(val storageValue: String) {
        FINISHED("finished"), REGISTERED("registered"), NOT_YET("notYet");

        val label: String
            get() = when (this) {
                FINISHED -> "나가봤어요"
                REGISTERED -> "접수해 뒀어요"
                NOT_YET -> "아직요"
            }
    }
}
