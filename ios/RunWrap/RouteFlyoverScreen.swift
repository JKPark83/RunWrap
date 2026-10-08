import SwiftUI
import MapKit

/// 앱 안 경로 플라이오버 재생 (이슈 #224, #232) — 3D 지도 위를 카메라가 경로를 따라 거리 비례 길이(km당 6초, 15~60초)로 날아간다.
/// 카메라는 `mapCameraKeyframeAnimator`(iOS 17, WWDC23 10157)가 움직이고, 키프레임 애니메이터는
/// 카메라만 움직이므로 현재 위치 점·지나온 경로·HUD는 `TimelineView(.animation)`로 같은 재생 시계에서 그린다.
/// 키프레임 배치·보간은 `FlyoverEngine`이 정한다 — 이 화면은 그리기만 한다.
/// 앱 안 재생이라 본인만 본다 — 경로 가림(RoutePrivacy)은 저장·공유로 확장할 때 붙인다.
struct RouteFlyoverScreen: View {
    let track: FlyoverEngine.Track
    @Environment(\.dismiss) private var dismiss

    @State private var playID = 0
    @State private var startedAt = Date()
    /// 재생 중에는 지도 제스처를 막는다 — 제스처가 들어오면 키프레임 애니메이션이 제거된다
    @State private var playing = true
    @State private var position: MapCameraPosition

    private let keyframes: [FlyoverEngine.Keyframe]
    private let coordinates: [CLLocationCoordinate2D]
    /// 카메라 거리(m)·기울기(도) — 이슈 #224 설계(600m·60°)에서 거리만 늘렸다: 600m는 3D 건물이 점·선을 너무 가렸다(시뮬레이터 확인)
    private let cameraDistance: Double = 800
    private let cameraPitch: Double = 60
    /// 지나온 경로 선 굵기와 흰 테두리(halo) — 지도 위에서 도드라지게 (#232)
    private static let lineWidth: CGFloat = 6
    private static let haloWidth: CGFloat = 10

    init(track: FlyoverEngine.Track) {
        self.track = track
        let keyframes = FlyoverEngine.keyframes(track)
        self.keyframes = keyframes
        coordinates = track.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        let first = keyframes[0]
        _position = State(initialValue: .camera(MapCamera(
            centerCoordinate: CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon),
            distance: cameraDistance, heading: first.headingDeg, pitch: cameraPitch)))
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
        .overlay(alignment: .topLeading) { closeButton }
        .onAppear(perform: play)
        .task(id: playID) {
            // 재생 길이가 지나면 멈추고 제스처·"다시 재생"을 연다
            try? await Task.sleep(for: .seconds(track.playbackSec))
            guard !Task.isCancelled else { return }
            playing = false
        }
    }

    private func play() {
        startedAt = Date()
        playing = true
        playID += 1
    }

    private var map: some View {
        Map(position: $position, interactionModes: playing ? [] : .all) {
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
        .mapCameraKeyframeAnimator(trigger: playID) { _ in
            // Cubic — 키프레임마다 속도·회전이 꺾이지 않게 한다. 점은 지도 좌표를 화면으로 바꿔 그리므로
            // 카메라 중심이 경로에서 조금 벗어나도 점은 경로 위에 그대로 있다 (#232)
            KeyframeTrack(\MapCamera.centerCoordinate) {
                for k in keyframes {
                    CubicKeyframe(CLLocationCoordinate2D(latitude: k.lat, longitude: k.lon), duration: k.durationSec)
                }
            }
            KeyframeTrack(\MapCamera.heading) {
                for k in keyframes {
                    CubicKeyframe(k.headingDeg, duration: k.durationSec)
                }
            }
            // 거리·기울기는 고정 — 시작 시 바로 맞춘다(0초 키프레임). 애니메이터는 트리거 순간의 카메라에서
            // 출발하므로, 18초에 걸쳐 보간하면 처음 몇 초가 엉뚱한 줌으로 시작한다(시뮬레이터 확인)
            KeyframeTrack(\MapCamera.distance) {
                LinearKeyframe(cameraDistance, duration: 0)
                LinearKeyframe(cameraDistance, duration: track.playbackSec)
            }
            KeyframeTrack(\MapCamera.pitch) {
                LinearKeyframe(cameraPitch, duration: 0)
                LinearKeyframe(cameraPitch, duration: track.playbackSec)
            }
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
