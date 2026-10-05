package com.jkpark.runwrap.engine

import java.io.File
import kotlinx.serialization.Serializable

/// 온보딩 설문 원답 — 카드 플로우(기획서 §2) Q1~Q9의 답을 그대로 보존한다.
/// 재진단("다시 진단받기", §3·§7) 시 이전 답을 프리필하는 데 쓰고,
/// `LevelEngine.decide`의 입력이 되어 판정 근거로도 남는다.
/// (Android: 케이스 이름을 iOS rawValue와 같게 두어 `rawValue`/`fromRawValue`가 이름 그대로다)
@Serializable
data class OnboardingAnswers(
    var q1Experience: Q1,
    var q2aActivity: Q2A? = null,
    var q2Longest: Q2Longest? = null,
    var q3Record: Q3Record? = null,
    var q4Monthly: Q4Monthly? = null,
    var q5Frequency: Q5Frequency? = null,
    var q6Race: Q6Race? = null,
    /// Q7 — 다음 목표는 어떤 거리인가요? (nil = "아직 없어요")
    var q7Target: RaceDistance? = null,
    /// Q8 — 목표 기록(초). 스킵("일단 완주부터요")이면 nil
    var q8GoalSec: Int? = null,
    /// Q9 — 달리는 이유, 최대 2개
    var q9Purposes: List<RunPurpose>,
) {
    /// Q1 — 러닝, 해보신 적 있나요? (플로우 분기)
    @Serializable
    enum class Q1 {
        novice, experienced;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                novice -> "이제 시작해요"
                experienced -> "좀 달려봤어요"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q1? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q2a — 요즘 몸은 좀 움직이고 계세요? (무경험 분기 전용, 걷뛰 시작 강도)
    @Serializable
    enum class Q2A {
        sedentary, walking, otherSport;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                sedentary -> "주로 앉아 지내요"
                walking -> "걷기는 챙겨 해요"
                otherSport -> "다른 운동을 하고 있어요"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q2A? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q2 — 지금까지 가장 멀리 달려본 거리는요? (레벨 판정 ①, Q3 분기)
    @Serializable
    enum class Q2Longest {
        under5, fiveToTen, tenToHalf, halfToFull, fullFinisher;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                under5 -> "5km 미만"
                fiveToTen -> "5~10km"
                tenToHalf -> "10km~하프"
                halfToFull -> "하프~풀"
                fullFinisher -> "풀코스 완주"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q2Longest? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q3 — 풀 완주자는 풀코스 기록, 그 외는 10km 기록을 묻는다 (런친놈 조건① / 중급 판정)
    @Serializable
    enum class Q3Record {
        // 풀코스 완주자 분기
        fullUnder430, full430to5, fullOver5,
        // 10km 분기
        tenUnder60, tenOver60, tenUnknown;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                fullUnder430 -> "4시간 30분 이내"
                full430to5 -> "4:30~5시간"
                fullOver5 -> "5시간 넘게"
                tenUnder60 -> "1시간 이내"
                tenOver60 -> "1시간 조금 넘게"
                tenUnknown -> "재본 적 없어요"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q3Record? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q4 — 한 달에 보통 얼마나 달리세요? (런친놈 조건②)
    @Serializable
    enum class Q4Monthly {
        under50, fiftyTo100, hundredTo200, over200;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                under50 -> "50km 미만"
                fiftyTo100 -> "50~100km"
                hundredTo200 -> "100~200km"
                over200 -> "200km 이상"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q4Monthly? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q5 — 일주일에 몇 번 나가세요? (주간 러닝 목표 초기값 — §5 XP 연동)
    @Serializable
    enum class Q5Frequency {
        rare, twoOrThree, fourPlus;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                rare -> "한 번 나갈까 말까"
                twoOrThree -> "2~3번"
                fourPlus -> "4번 이상"
            }

        /// 이 답이 뜻하는 주간 러닝 목표(회) — 기획서 §2 "1·3·4회"
        val weeklyGoal: Int
            get() = when (this) {
                rare -> 1
                twoOrThree -> 3
                fourPlus -> 4
            }

        companion object {
            fun fromRawValue(rawValue: String): Q5Frequency? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /// Q6 — 대회는 나가보셨어요? (대회 탭 안내·가이드 톤)
    @Serializable
    enum class Q6Race {
        finished, registered, notYet;

        val rawValue: String get() = name

        val label: String
            get() = when (this) {
                finished -> "나가봤어요"
                registered -> "접수해 뒀어요"
                notYet -> "아직요"
            }

        companion object {
            fun fromRawValue(rawValue: String): Q6Race? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}

/// 온보딩 원답 파일 정리 — 원답은 더 이상 저장하지 않는다.
/// 이전 버전(≤ 현재 마케팅 버전)이 남긴 자기 신고 훈련 이력 파일
/// (Application Support/RunWrap/onboarding-answers.json) — 기능에 쓰이지 않아 기동 시 한 번 지운다(이슈 #156).
/// 재진단은 의도적으로 프리필하지 않으므로(SettingsScreen 참조) 원답을 되살릴 곳도 없다
object OnboardingAnswersStore {
    const val filename = "onboarding-answers.json"

    /// directory 주입은 테스트용 — 기본은 Application Support/RunWrap. 디렉터리는 만들지 않고, 파일이 없으면 무시한다
    /// (Android: 기본값 없이 :app이 넘긴다)
    fun removeLegacyFile(directory: File) {
        try {
            File(directory, filename).delete()
        } catch (_: Exception) {
            // iOS `try?`와 같이 실패는 무시한다
        }
    }
}
