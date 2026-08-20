import Foundation

/// 현재 위치 대기질 스토어 — 최근접 측정소를 찾아 실시간 수치를 받아온다 (이슈 #8).
/// HealthKit과 무관하지만 스토어 계층 규칙(@MainActor + ObservableObject + enum State)을 따른다.
/// 위치는 직접 다루지 않는다 — TodayScreen이 위치 결론을 받은 시점에 좌표를 넘겨 호출한다.
///
/// 데이터를 내지 못하는 모든 경우(측정소 목록 없음·커버리지 밖·인증키 없음·통신장애·네트워크 실패)는
/// unavailable 하나로 접는다 — 화면은 이때 카드를 아예 그리지 않는다 (미노출 가드).
///
/// 호출량 방어: 응답을 Application Support에 1시간 캐시한다(AirQualityEngine.isFresh).
/// 측정값 자체가 시간 단위로 갱신되므로 정보 손실이 없고, 개발계정 트래픽 한도(500건/일) 안에 남는다.
@MainActor
final class AirQualityStore: ObservableObject {
    enum State: Equatable {
        case idle
        case loading
        case loaded(AirQuality)
        case unavailable
    }

    @Published private(set) var state: State = .idle

    private let client = AirQualityClient()

    func load(latitude: Double, longitude: Double) async {
        guard case .idle = state else { return }
        state = .loading
        state = await resolve(latitude: latitude, longitude: longitude)
    }

    /// 당겨서 새로고침 — 직전 카드를 유지한 채 다시 조회한다 (loading을 거치면 카드가 깜빡인다).
    /// 1시간 캐시는 그대로 적용되므로 잦은 새로고침이 트래픽 한도를 위협하지 않는다 —
    /// 측정값 자체가 시간 단위 갱신이라 정보 손실도 없다. 위치가 바뀌어 측정소가 달라졌을 때만 네트워크를 탄다
    func refresh(latitude: Double, longitude: Double) async {
        state = await resolve(latitude: latitude, longitude: longitude)
    }

    /// 최근접 측정소 탐색 → 캐시 확인 → 조회의 본체 — 결론(State)만 돌려준다
    private func resolve(latitude: Double, longitude: Double) async -> State {
        #if targetEnvironment(simulator)
        // 시뮬레이터 기본 위치(쿠퍼티노)는 측정소 커버리지 밖이라 항상 미노출이 된다 —
        // 합성 데이터로 카드 구성을 검증한다 (CLAUDE.md 빌드·검증 항목)
        return .loaded(DemoData.airQuality)
        #else
        guard let stations = Self.readStations(),
              let station = AirQualityEngine.nearestStation(
                  to: GeoPoint(lat: latitude, lon: longitude), stations: stations) else {
            return .unavailable
        }

        // 같은 측정소의 1시간 안 응답이면 재조회하지 않는다 — 한도 방어의 본체
        let now = Date()
        if let cached = Self.readCache(),
           cached.quality.stationName == station.name,
           AirQualityEngine.isFresh(fetchedAt: cached.fetchedAt, now: now) {
            return .loaded(cached.quality)
        }

        guard let serviceKey = Self.serviceKey() else { return .unavailable }

        do {
            let quality = try await client.current(stationName: station.name, serviceKey: serviceKey)
            // 통신장애 측정소는 응답은 정상인데 수치가 전부 "-"로 온다 — 지표를 내지 않는다
            guard AirQualityEngine.hasReading(quality) else { return .unavailable }
            Self.writeCache(Cache(fetchedAt: now, quality: quality))
            return .loaded(quality)
        } catch {
            // 낡은 캐시로 대체하지 않는다 — 시간 단위로 변하는 값이라 "지금 공기"로 내밀 수 없다
            return .unavailable
        }
        #endif
    }

    // MARK: - 번들 리소스

    /// 전국 측정소 목록 — air-stations.yml 배치가 갱신하는 번들 AirStations.json.
    /// 비어 있으면(아직 인증키로 한 번도 채우지 않은 상태) 기능 전체가 조용히 숨는다
    private nonisolated static func readStations() -> [AirStation]? {
        guard let url = Bundle.main.url(forResource: "AirStations", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(AirStationFile.self, from: data),
              !file.stations.isEmpty else { return nil }
        return file.stations
    }

    /// data.go.kr 인증키 — 번들 AirQualityKey.json(gitignore 대상, 커밋 금지)에서 읽는다.
    /// 형식: {"serviceKey": "<디코딩 인증키>"}. 파일이 없어도 앱은 성립한다 — 대기질만 숨는다
    private nonisolated static func serviceKey() -> String? {
        guard let url = Bundle.main.url(forResource: "AirQualityKey", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(KeyFile.self, from: data),
              !file.serviceKey.isEmpty else { return nil }
        return file.serviceKey
    }

    private struct KeyFile: Codable {
        let serviceKey: String
    }

    // MARK: - 응답 캐시 (Application Support, ReportCache와 같은 위치 정책)

    private struct Cache: Codable {
        let fetchedAt: Date
        let quality: AirQuality
    }

    private nonisolated static var cacheURL: URL? {
        try? FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask,
                 appropriateFor: nil, create: true)
            .appendingPathComponent("AirQuality.json")
    }

    private nonisolated static func readCache() -> Cache? {
        guard let cacheURL, let data = try? Data(contentsOf: cacheURL) else { return nil }
        return try? JSONDecoder().decode(Cache.self, from: data)
    }

    private nonisolated static func writeCache(_ cache: Cache) {
        guard let cacheURL, let data = try? JSONEncoder().encode(cache) else { return }
        try? data.write(to: cacheURL, options: .atomic)
    }
}
