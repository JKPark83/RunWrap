import Foundation

/// 번들 CoursePOI.json(전국 급수·화장실·편의점 약 5.6만 건) 로더 — 코스 탭 전용.
/// HealthKit과 무관하지만 스토어 계층 규칙(@MainActor + ObservableObject + enum State)을 따른다.
/// 데이터는 tools/course-poi 파이프라인이 만들어 앱에 내장한다 — 조회는 전부 온디바이스 (기획서 §4.13).
@MainActor
final class CoursePOIStore: ObservableObject {
    enum State {
        case idle
        case loading
        case loaded(CoursePOIFile)
        case failed
    }

    @Published private(set) var state: State = .idle

    func load() async {
        if case .loaded = state { return }
        state = .loading
        // 수 MB JSON 디코드는 백그라운드에서 — 메인 스레드 프레임 드랍 방지
        let file = await Task.detached(priority: .userInitiated) { () -> CoursePOIFile? in
            guard let url = Bundle.main.url(forResource: "CoursePOI", withExtension: "json"),
                  let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(CoursePOIFile.self, from: data)
        }.value
        if let file {
            state = .loaded(file)
        } else {
            state = .failed
        }
    }
}
