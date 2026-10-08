import SwiftUI
import MapKit

/// 앱 안 경로 플라이오버 재생 (이슈 #224) — 3D 지도 위를 카메라가 경로를 따라 18초 동안 날아간다.
/// 카메라는 `mapCameraKeyframeAnimator`(iOS 17, WWDC23 10157)가 움직이고, 키프레임 애니메이터는
/// 카메라만 움직이므로 현재 위치 점·지나온 경로 꼬리·HUD는 `TimelineView(.animation)`로 같은 재생 시계에서 그린다.
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
    /// 지도에 그린 지나온 경로 단계 — 이 값이 바뀔 때만 지도 내용이 다시 만들어진다
    @State private var passedStep = 0
    /// 지나온 경로 갱신 단계 수 — 18초에 90단계(초당 약 5번)
    private static let passedSteps = 90
    /// 꼬리를 겹쳐 그리는 단계 수 — 약 0.6초 분량
    private static let tailOverlapSteps = 3

    private let keyframes: [FlyoverEngine.Keyframe]
    private let coordinates: [CLLocationCoordinate2D]
    /// 카메라 거리(m)·기울기(도) — 이슈 #224 설계(600m·60°)에서 거리만 늘렸다: 600m는 3D 건물이 점·선을 너무 가렸다(시뮬레이터 확인)
    private let cameraDistance: Double = 800
    private let cameraPitch: Double = 60

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
        // 지도는 지나온 경로 단계(passedStep)가 바뀔 때만 다시 그리고, 재생 중 매 프레임 움직이는
        // 점·꼬리·HUD는 지도 위 SwiftUI 층이 `MapProxy.convert`로 화면 좌표를 구해 그린다
        MapReader { proxy in
            ZStack(alignment: .bottom) {
                map
                if playing {
                    TimelineView(.animation) { context in
                        let progress = context.date.timeIntervalSince(startedAt) / FlyoverEngine.playbackSec
                        let frame = FlyoverEngine.frame(track, progress: progress)
                        let here = CLLocationCoordinate2D(latitude: frame.lat, longitude: frame.lon)
                        ZStack(alignment: .bottom) {
                            tail(to: here, passedCount: frame.passedCount, proxy: proxy)
                            if let point = proxy.convert(here, to: .local) {
                                dot.position(point).allowsHitTesting(false)
                            }
                            hud(frame)
                        }
                        .onChange(of: Self.step(frame.passedCount, of: coordinates.count), initial: true) { _, step in
                            passedStep = step
                        }
                    }
                } else {
                    // 멈춘 뒤에는 지도를 움직일 수 있다 — 점은 지도 안 Annotation이 끝 지점에 붙어 따라간다
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
            try? await Task.sleep(for: .seconds(FlyoverEngine.playbackSec))
            guard !Task.isCancelled else { return }
            passedStep = Self.passedSteps
            playing = false
        }
    }

    /// 지나온 경로 점 수 → 갱신 단계(0...passedSteps)
    private static func step(_ passedCount: Int, of total: Int) -> Int {
        passedCount * passedSteps / total
    }

    /// 갱신 단계 → 지도에 그리는 지나온 경로 점 수(1 이상, passedCount 이하)
    private static func passedEnd(_ step: Int, of total: Int) -> Int {
        max(1, total * step / passedSteps)
    }

    private func play() {
        startedAt = Date()
        passedStep = 0
        playing = true
        playID += 1
    }

    private var map: some View {
        // 단계 끝까지 — 그 뒤 현재 위치까지는 `tail`이 잇는다
        let passedEnd = Self.passedEnd(passedStep, of: coordinates.count)
        return Map(position: $position, interactionModes: playing ? [] : .all) {
            MapPolyline(coordinates: coordinates)
                .stroke(RR.brand.opacity(0.3), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
            // 지나온 경로 — #222 RoutePaceEngine 머지 후 페이스 색 구간(MapPolyline 여러 개)으로 교체한다
            MapPolyline(coordinates: Array(coordinates.prefix(passedEnd)))
                .stroke(RR.brand, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            if !playing, let end = coordinates.last {
                Annotation("", coordinate: end, anchor: .center) { dot }
            }
        }
        .mapStyle(.standard(elevation: .realistic, pointsOfInterest: .excludingAll))
        .mapControls {}   // 나침반이 닫기 버튼 줄과 겹친다 — 방향은 카메라가 알아서 돈다
        .mapCameraKeyframeAnimator(trigger: playID) { _ in
            // 위치는 선형 — 점(TimelineView 선형 보간)과 키프레임 사이에서도 어긋나지 않게 한다
            KeyframeTrack(\MapCamera.centerCoordinate) {
                for k in keyframes {
                    LinearKeyframe(CLLocationCoordinate2D(latitude: k.lat, longitude: k.lon), duration: k.durationSec)
                }
            }
            KeyframeTrack(\MapCamera.heading) {
                for k in keyframes {
                    LinearKeyframe(k.headingDeg, duration: k.durationSec)
                }
            }
            // 거리·기울기는 고정 — 시작 시 바로 맞춘다(0초 키프레임). 애니메이터는 트리거 순간의 카메라에서
            // 출발하므로, 18초에 걸쳐 보간하면 처음 몇 초가 엉뚱한 줌으로 시작한다(시뮬레이터 확인)
            KeyframeTrack(\MapCamera.distance) {
                LinearKeyframe(cameraDistance, duration: 0)
                LinearKeyframe(cameraDistance, duration: FlyoverEngine.playbackSec)
            }
            KeyframeTrack(\MapCamera.pitch) {
                LinearKeyframe(cameraPitch, duration: 0)
                LinearKeyframe(cameraPitch, duration: FlyoverEngine.playbackSec)
            }
        }
    }

    /// 지도에 그린 지나온 경로 끝 ~ 현재 위치 사이 꼬리 — 단계 사이에 점과 선이 끊겨 보이지 않게 매 프레임 이어 그린다.
    /// 단계가 바뀐 뒤 MapKit이 새 선을 올리기까지 몇 프레임 걸려 그 사이가 비므로, 몇 단계 앞에서부터 겹쳐 그린다
    private func tail(to here: CLLocationCoordinate2D, passedCount: Int, proxy: MapProxy) -> some View {
        let passedEnd = Self.passedEnd(max(0, passedStep - Self.tailOverlapSteps), of: coordinates.count)
        let coords = coordinates[(passedEnd - 1)..<max(passedEnd, passedCount)] + [here]
        let points = coords.compactMap { proxy.convert($0, to: .local) }
        return Path { $0.addLines(points) }
            .stroke(RR.brand, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
            .allowsHitTesting(false)
    }

    /// 현재 위치 점 — 재생 중에는 지도 위 SwiftUI 층에, 멈춘 뒤에는 지도 안 Annotation으로 놓는다
    private var dot: some View {
        Circle()
            .fill(RR.brand)
            .frame(width: 16, height: 16)
            .overlay(Circle().stroke(RR.onBrand, lineWidth: 3))
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
