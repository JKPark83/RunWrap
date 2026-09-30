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
    /// 직전 원격 갱신이 실패했는지 — 헤더 캡션이 "방금 새로 받진 못했어요"로 알린다 (#145)
    @Published private(set) var lastRefreshFailed = false
    /// 마지막 원격 갱신 성공 시각 — 탭 복귀·포그라운드 복귀 때 6시간 규칙으로 다시 받을지 정한다 (#145)
    private(set) var lastRefreshedAt: Date?

    /// 앱이 읽을 수 있는 Races.json 스키마 버전 — 원격이 이보다 크면 캐시하지 않고 버린다 (#144)
    nonisolated static let supportedSchemaVersion = 1

    /// 원격을 다시 받을 때인가 — 받은 적 없거나 maxAge(기본 6시간)보다 오래됐으면 true (#145).
    /// 배치는 하루 한 번이라 6시간이면 새벽 갱신을 당일 안에 따라잡는다.
    /// 스토어는 테스트하지 않으므로 판정만 순수 함수로 뺀다.
    nonisolated static func needsRefresh(lastRefreshedAt: Date?, now: Date,
                                         maxAge: TimeInterval = 21_600) -> Bool {
        guard let lastRefreshedAt else { return true }
        return now.timeIntervalSince(lastRefreshedAt) > maxAge
    }

    func load() async {
        if case .loaded = state {
            // 이미 목록이 있으면 오래됐을 때만 원격을 다시 받는다 — 앱을 며칠 켜 둬도 어제 자료에 머물지 않게 (#145)
            if Self.needsRefresh(lastRefreshedAt: lastRefreshedAt, now: Date()) { await refresh() }
            return
        }
        state = .loading
        // 로컬(캐시 → 번들)을 먼저 보여주고 원격은 뒤에서 갱신 — 첫 화면이 네트워크를 기다리지 않는다
        if let local = Self.readLocal() {
            state = .loaded(local)
        }
        await refresh()
        if case .loading = state { state = .failed }
    }

    /// 원격 갱신 — 실패하면 로컬 상태를 유지하고 실패만 표시한다 (당겨서 새로고침에서도 호출)
    func refresh() async {
        guard let file = await Self.fetchRemote() else {
            // 탭을 떠나 .task가 취소된 요청은 실패가 아니다 — 다음 진입에서 다시 받으니 표시하지 않는다
            if !Task.isCancelled { lastRefreshFailed = true }
            return
        }
        state = .loaded(file)
        lastRefreshedAt = Date()
        lastRefreshFailed = false
    }

    /// GitHub Actions가 매일 크롤해 커밋하는 원본 파일의 raw URL (계획서 M13-4).
    /// main이 아니라 dev를 읽는다 — main은 보호 규칙(PR 필수) 때문에 크롤 봇이 직접
    /// 커밋할 수 없어서, 새벽 크롤이 실제로 쌓이는 브랜치는 dev다. 스키마가 앱보다
    /// 앞서가 디코드에 실패하거나 schemaVersion이 지원 범위를 넘으면 fetchRemote가 nil을
    /// 돌려줘 로컬 데이터로 안전하게 남는다.
    private nonisolated static let remoteURL =
        URL(string: "https://raw.githubusercontent.com/JKPark83/RunWrap/dev/ios/RunWrap/Races.json")!

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
              let file = try? JSONDecoder().decode(RaceFile.self, from: data),
              file.schemaVersion <= supportedSchemaVersion else { return nil }
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
