import Foundation

/// 대회정보 스토어 — 원격 Races.json을 받아오고 캐시로 오프라인을 버틴다 (계획서 M13-3).
/// HealthKit과 무관하지만 스토어 계층 규칙(@MainActor + ObservableObject + enum State)을 따른다.
/// 네트워크는 수신 전용 — 건강 데이터를 포함해 어떤 사용자 데이터도 내보내지 않는다 (기획서 §6).
@MainActor
final class RaceStore: ObservableObject {
    enum State {
        case idle
        case loading
        case loaded(RaceFile)
        case failed
    }

    @Published private(set) var state: State = .idle

    func load() async {
        if case .loaded = state { return }
        state = .loading
        // 로컬(캐시 → 번들)을 먼저 보여주고 원격은 뒤에서 갱신 — 첫 화면이 네트워크를 기다리지 않는다
        if let local = Self.readLocal() {
            state = .loaded(local)
        }
        await refresh()
        if case .loading = state { state = .failed }
    }

    /// 원격 갱신 — 실패하면 조용히 로컬 상태를 유지한다 (당겨서 새로고침에서도 호출)
    func refresh() async {
        guard let file = await Self.fetchRemote() else { return }
        state = .loaded(file)
    }

    /// GitHub Actions가 매일 크롤해 커밋하는 원본 파일의 raw URL (계획서 M13-4)
    private nonisolated static let remoteURL =
        URL(string: "https://raw.githubusercontent.com/JKPark83/RunWrap/main/ios/RunWrap/Races.json")!

    private nonisolated static var cacheURL: URL? {
        try? FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask,
                 appropriateFor: nil, create: true)
            .appendingPathComponent("Races.json")
    }

    private nonisolated static func fetchRemote() async -> RaceFile? {
        var request = URLRequest(url: remoteURL)
        request.cachePolicy = .reloadIgnoringLocalCacheData   // 어제 자 URL 캐시 방지
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let file = try? JSONDecoder().decode(RaceFile.self, from: data) else { return nil }
        if let cacheURL { try? data.write(to: cacheURL, options: .atomic) }
        return file
    }

    /// 캐시와 번들 중 더 최신 것 — 앱 업데이트 직후엔 번들이 오래된 캐시보다 새것일 수 있다.
    /// generatedAt이 같은 형식(+09:00)의 ISO8601이라 문자열 비교가 곧 시간 비교다.
    private nonisolated static func readLocal() -> RaceFile? {
        let cached = cacheURL.flatMap(read)
        let bundled = Bundle.main.url(forResource: "Races", withExtension: "json").flatMap(read)
        switch (cached, bundled) {
        case (let c?, let b?): return c.generatedAt >= b.generatedAt ? c : b
        case (let c?, nil): return c
        case (nil, let b?): return b
        case (nil, nil): return nil
        }
    }

    private nonisolated static func read(_ url: URL) -> RaceFile? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(RaceFile.self, from: data)
    }
}
