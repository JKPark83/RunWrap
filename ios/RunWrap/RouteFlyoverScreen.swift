import SwiftUI
import MapKit

/// 앱 안 경로 플라이오버 재생 (이슈 #224, #232) — 3D 지도 위를 카메라가 경로를 따라 거리 비례 길이(km당 10초, 20~180초)로 날아간다.
/// 카메라는 매 프레임 현재 위치 점 위에 직접 놓는다(`position = .camera`) — `mapCameraKeyframeAnimator`는 자기 시계로
/// 움직여 점(TimelineView 시계)과 어긋나고, 제스처 한 번에 애니메이션이 통째로 사라져 카메라가 멈췄다(실기기 확인, #232).
/// 점·지나온 경로·HUD는 `TimelineView(.animation)`로 같은 재생 시계에서 그린다.
/// 키프레임 배치·방위 보간은 `FlyoverEngine`이 정한다 — 이 화면은 그리기만 한다.
/// 앱 안 재생이라 본인만 본다 — 경로 가림(RoutePrivacy)은 저장·공유로 확장할 때 붙인다.
struct RouteFlyoverScreen: View {
    let track: FlyoverEngine.Track
    @Environment(\.dismiss) private var dismiss

    @State private var playID = 0
    @State private var startedAt = Date()
    @State private var playing = true
    @State private var position: MapCameraPosition
    /// 재생 중 핀치로 바꾼 줌을 따라간다 — 카메라를 매 프레임 다시 놓아도 사용자가 고른 거리는 유지 (#232)
    @State private var cameraDistance: Double

    private let keyframes: [FlyoverEngine.Keyframe]
    private let coordinates: [CLLocationCoordinate2D]
    /// 기본 카메라 거리(m)·기울기(도) — 이슈 #224 설계(600m·60°)에서 거리만 늘렸다: 600m는 3D 건물이 점·선을 너무 가렸다(시뮬레이터 확인)
    private static let defaultDistance: Double = 800
    private static let cameraPitch: Double = 60
    /// 지나온 경로 선 굵기와 흰 테두리(halo) — 지도 위에서 도드라지게 (#232)
    private static let lineWidth: CGFloat = 6
    private static let haloWidth: CGFloat = 10

    init(track: FlyoverEngine.Track) {
        self.track = track
        let keyframes = FlyoverEngine.keyframes(track)
        self.keyframes = keyframes
        coordinates = track.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        let first = keyframes[0]
        _cameraDistance = State(initialValue: Self.defaultDistance)
        _position = State(initialValue: .camera(MapCamera(
            centerCoordinate: CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon),
            distance: Self.defaultDistance, heading: first.headingDeg, pitch: Self.cameraPitch)))
    }

    var body: some View {
        // 지도는 TimelineView 밖에 둔다 — 지도 내용(MapPolyline·Annotation)을 매 프레임 다시 만들면 MapKit이
        // 오버레이를 매 틱 지웠다 다시 올려 재생 중 선이 보이지 않았다(시뮬레이터 확인).
        // 재생 중 지도 내용은 고정하고, 움직이는 지나온 경로·점·HUD는 지도 위 SwiftUI 층이
        // `MapProxy.convert`로 화면 좌표를 구해 매 프레임 그린다 — 지도 콘텐츠를 다시 올리는 끊김이 없고,
        // 3D 건물 위에 그려져 가려지지 않는다 (#232)
        MapReader { proxy in
            ZStack(alignment: .bottom) {
                map
                if playing {
                    TimelineView(.animation) { context in
                        let progress = context.date.timeIntervalSince(startedAt) / track.playbackSec
                        let frame = FlyoverEngine.frame(track, progress: progress)
                        let here = CLLocationCoordinate2D(latitude: frame.lat, longitude: frame.lon)
                        ZStack(alignment: .bottom) {
                            trail(frame, to: here, proxy: proxy)
                            if let point = proxy.convert(here, to: .local) {
                                dot.position(point).allowsHitTesting(false)
                            }
                            hud(frame)
                        }
                    }
                } else {
                    // 멈춘 뒤에는 지도를 움직일 수 있다 — 경로·점은 지도 안 콘텐츠가 따라간다
                    hud(FlyoverEngine.frame(track, progress: 1))
                }
            }
        }
        .ignoresSafeArea(edges: .top)
        .background(RR.bg.ignoresSafeArea())
        .background(GeometryReader { geo in Color.clear.onAppear { viewSize = geo.size } })
        .overlay(alignment: .topLeading) { closeButton }
        .onAppear(perform: play)
        .task(id: playID) {
            // 재생 시계 — 매 틱 카메라를 현재 위치 점 위에 놓고, 재생 길이가 지나면 멈춰 제스처·"다시 재생"을 연다
            while !Task.isCancelled {
                let elapsed = Date().timeIntervalSince(startedAt)
                guard elapsed < track.playbackSec else { break }
                let frame = FlyoverEngine.frame(track, progress: elapsed / track.playbackSec)
                position = .camera(MapCamera(
                    centerCoordinate: CLLocationCoordinate2D(latitude: frame.lat, longitude: frame.lon),
                    distance: cameraDistance,
                    heading: FlyoverEngine.heading(keyframes, at: elapsed),
                    pitch: Self.cameraPitch))
                try? await Task.sleep(for: .milliseconds(16))
            }
            guard !Task.isCancelled else { return }
            playing = false
            // 끝나면 천천히 빠져나와 뛰어온 코스 전체를 보여준다 — 카메라→카메라 보간이라 끊김 없이 이어진다
            // (`.rect`로 바꾸면 기울기·방위가 한 번에 꺾여 실기기에서 덜컥거렸다)
            withAnimation(.easeInOut(duration: Self.outroSec)) { position = .camera(fullCourseCamera) }
        }
    }

    /// 마무리 — 전체 코스로 빠지는 카메라 시간(초)
    private static let outroSec: Double = 6
    /// 화면 크기 — 전체 코스 카메라 거리 계산용
    @State private var viewSize = CGSize(width: 402, height: 874)
    /// 전체 코스가 들어오는 수직 카메라 — 사방 25% 여백, 아래는 HUD 몫으로 20% 더 둔다.
    /// MapKit 카메라의 세로 시야각은 30°라 보이는 세로 폭 = 거리 × 2·tan(15°) (시뮬레이터에서 측정, #232)
    private var fullCourseCamera: MapCamera {
        let rect = coordinates.map { MKMapRect(origin: MKMapPoint($0), size: MKMapSize(width: 1, height: 1)) }
            .reduce(MKMapRect.null) { $0.union($1) }
        let dx = rect.width * 0.25, dy = rect.height * 0.25
        let fit = MKMapRect(x: rect.minX - dx, y: rect.minY - dy, width: rect.width + dx * 2, height: rect.height + dy * 2 + rect.height * 0.2)
        let metersPerPoint = MKMetersPerMapPointAtLatitude(fit.origin.coordinate.latitude)
        let heightM = max(fit.height, fit.width * viewSize.height / viewSize.width) * metersPerPoint
        return MapCamera(
            centerCoordinate: MKMapPoint(x: fit.midX, y: fit.midY).coordinate,
            distance: heightM / (2 * tan(15 * .pi / 180)), heading: 0, pitch: 0)
    }

    private func play() {
        startedAt = Date()
        playing = true
        playID += 1
    }

    private var map: some View {
        // 재생 중엔 줌만 허용 — 핀치로 멀리서 보더라도 카메라는 계속 점을 따라간다. 이동·회전은 카메라가 매 틱 덮어쓰므로 막는다
        Map(position: $position, interactionModes: playing ? .zoom : .all) {
            MapPolyline(coordinates: coordinates)
                .stroke(RR.brand.opacity(0.35), style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round, lineJoin: .round))
            if !playing {
                // 지나온 경로 — #222 RoutePaceEngine 머지 후 페이스 색 구간(MapPolyline 여러 개)으로 교체한다
                MapPolyline(coordinates: coordinates)
                    .stroke(RR.onBrand, style: StrokeStyle(lineWidth: Self.haloWidth, lineCap: .round, lineJoin: .round))
                MapPolyline(coordinates: coordinates)
                    .stroke(RR.brand, style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round, lineJoin: .round))
                if let end = coordinates.last {
                    Annotation("", coordinate: end, anchor: .center) { dot }
                }
            }
        }
        // muted — 도로·건물 색을 낮춰 경로가 도드라지게 (#232)
        .mapStyle(.standard(elevation: .realistic, emphasis: .muted, pointsOfInterest: .excludingAll))
        .mapControls {}   // 나침반이 닫기 버튼 줄과 겹친다 — 방향은 카메라가 알아서 돈다
        .onMapCameraChange(frequency: .continuous) { context in
            cameraDistance = context.camera.distance
        }
    }

    /// 지나온 꼬리(최근 `FlyoverEngine.trailM` ~ 현재 위치) — 지도 위 SwiftUI 층에 매 프레임 그린다. 흰 테두리 위에 브랜드 색.
    /// 시작점부터 전부 그리면 카메라 뒤쪽 점의 화면 좌표가 반대편으로 튀어 긴 직선이 생긴다(실기기 확인, #232)
    private func trail(_ frame: FlyoverEngine.Frame, to here: CLLocationCoordinate2D, proxy: MapProxy) -> some View {
        let coords = coordinates[frame.trailStart..<frame.passedCount] + [here]
        let points = coords.compactMap { proxy.convert($0, to: .local) }
        let path = Path { $0.addLines(points) }
        return ZStack {
            path.stroke(RR.onBrand, style: StrokeStyle(lineWidth: Self.haloWidth, lineCap: .round, lineJoin: .round))
            path.stroke(RR.brand, style: StrokeStyle(lineWidth: Self.lineWidth, lineCap: .round, lineJoin: .round))
        }
        .allowsHitTesting(false)
    }

    /// 현재 위치 점 — 재생 중에는 지도 위 SwiftUI 층에, 멈춘 뒤에는 지도 안 Annotation으로 놓는다
    private var dot: some View {
        Circle()
            .fill(RR.brand)
            .frame(width: 18, height: 18)
            .overlay(Circle().stroke(RR.onBrand, lineWidth: 3))
            .shadow(color: .black.opacity(0.35), radius: 3, y: 1)
    }

    // MARK: HUD

    private func hud(_ frame: FlyoverEngine.Frame) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 0) {
                stat("거리", "\(Format.km(frame.distanceM / 1_000)) km")
                stat("시간", Format.duration(frame.elapsedSec))
                stat("페이스", frame.paceSecPerKm.map(Format.paceKm) ?? "—")
            }
            if !playing {
                Button(action: play) {
                    Label("다시 재생", systemImage: "arrow.counterclockwise")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(RR.onBrand)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(RR.brand, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .rrCard()
        .padding(.horizontal, 18)
        .padding(.bottom, 12)
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(RR.text3)
            Text(value)
                .font(.system(size: 17, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity)
    }

    private var closeButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "xmark")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
                .frame(width: 34, height: 34)
                .background(RR.surface, in: Circle())
                .rrTapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel("닫기")
        .padding(.leading, 14)
        .padding(.top, 8)
    }
}
