import Foundation

/// 온보딩 설문 원답 — 카드 플로우(기획서 §2) Q1~Q9의 답을 그대로 보존한다.
/// 재진단("다시 진단받기", §3·§7) 시 이전 답을 프리필하는 데 쓰고,
/// `LevelEngine.decide`의 입력이 되어 판정 근거로도 남는다.
struct OnboardingAnswers: Codable, Equatable {
    /// Q1 — 러닝, 해보신 적 있나요? (플로우 분기)
    enum Q1: String, Codable, CaseIterable {
        case novice, experienced

        var label: String {
            switch self {
            case .novice: "이제 시작해요"
            case .experienced: "좀 달려봤어요"
            }
        }
    }

    /// Q2a — 요즘 몸은 좀 움직이고 계세요? (무경험 분기 전용, 걷뛰 시작 강도)
    enum Q2A: String, Codable, CaseIterable {
        case sedentary, walking, otherSport

        var label: String {
            switch self {
            case .sedentary: "주로 앉아 지내요"
            case .walking: "걷기는 챙겨 해요"
            case .otherSport: "다른 운동을 하고 있어요"
            }
        }
    }

    /// Q2 — 지금까지 가장 멀리 달려본 거리는요? (레벨 판정 ①, Q3 분기)
    enum Q2Longest: String, Codable, CaseIterable {
        case under5, fiveToTen, tenToHalf, halfToFull, fullFinisher

        var label: String {
            switch self {
            case .under5: "5km 미만"
            case .fiveToTen: "5~10km"
            case .tenToHalf: "10km~하프"
            case .halfToFull: "하프~풀"
            case .fullFinisher: "풀코스 완주"
            }
        }
    }

    /// Q3 — 풀 완주자는 풀코스 기록, 그 외는 10km 기록을 묻는다 (런친놈 조건① / 중급 판정)
    enum Q3Record: String, Codable, CaseIterable {
        // 풀코스 완주자 분기
        case fullUnder430, full430to5, fullOver5
        // 10km 분기
        case tenUnder60, tenOver60, tenUnknown

        var label: String {
            switch self {
            case .fullUnder430: "4시간 30분 이내"
            case .full430to5: "4:30~5시간"
            case .fullOver5: "5시간 넘게"
            case .tenUnder60: "1시간 이내"
            case .tenOver60: "1시간 조금 넘게"
            case .tenUnknown: "재본 적 없어요"
            }
        }
    }

    /// Q4 — 한 달에 보통 얼마나 달리세요? (런친놈 조건②)
    enum Q4Monthly: String, Codable, CaseIterable {
        case under50, fiftyTo100, hundredTo200, over200

        var label: String {
            switch self {
            case .under50: "50km 미만"
            case .fiftyTo100: "50~100km"
            case .hundredTo200: "100~200km"
            case .over200: "200km 이상"
            }
        }
    }

    /// Q5 — 일주일에 몇 번 나가세요? (주간 러닝 목표 초기값 — §5 XP 연동)
    enum Q5Frequency: String, Codable, CaseIterable {
        case rare, twoOrThree, fourPlus

        var label: String {
            switch self {
            case .rare: "한 번 나갈까 말까"
            case .twoOrThree: "2~3번"
            case .fourPlus: "4번 이상"
            }
        }

        /// 이 답이 뜻하는 주간 러닝 목표(회) — 기획서 §2 "1·3·4회"
        var weeklyGoal: Int {
            switch self {
            case .rare: 1
            case .twoOrThree: 3
            case .fourPlus: 4
            }
        }
    }

    /// Q6 — 대회는 나가보셨어요? (대회 탭 안내·가이드 톤)
    enum Q6Race: String, Codable, CaseIterable {
        case finished, registered, notYet

        var label: String {
            switch self {
            case .finished: "나가봤어요"
            case .registered: "접수해 뒀어요"
            case .notYet: "아직요"
            }
        }
    }

    var q1Experience: Q1
    var q2aActivity: Q2A?
    var q2Longest: Q2Longest?
    var q3Record: Q3Record?
    var q4Monthly: Q4Monthly?
    var q5Frequency: Q5Frequency?
    var q6Race: Q6Race?
    /// Q7 — 다음 목표는 어떤 거리인가요? (nil = "아직 없어요")
    var q7Target: RaceDistance?
    /// Q8 — 목표 기록(초). 스킵("일단 완주부터요")이면 nil
    var q8GoalSec: Int?
    /// Q9 — 달리는 이유, 최대 2개
    var q9Purposes: [RunPurpose]
}

/// 온보딩 답변 영속화 — Application Support에 JSON으로 저장/로드 (ReportCache와 동일 패턴)
enum OnboardingAnswersStore {
    static let filename = "onboarding-answers.json"

    static func save(_ answers: OnboardingAnswers, in directory: URL? = nil) {
        guard let url = fileURL(in: directory),
              let data = try? JSONEncoder().encode(answers) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func load(from directory: URL? = nil) -> OnboardingAnswers? {
        guard let url = fileURL(in: directory),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(OnboardingAnswers.self, from: data)
    }

    /// directory 주입은 테스트용 — 기본은 Application Support/RunWrap (없으면 만든다)
    private static func fileURL(in directory: URL?) -> URL? {
        if let directory { return directory.appendingPathComponent(filename) }
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                                  in: .userDomainMask).first else { return nil }
        let dir = base.appendingPathComponent("RunWrap", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(filename)
    }
}
