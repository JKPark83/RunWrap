import SwiftUI
import MapKit
import Photos

/// 인스타그램 스토리 공유 카드 — 기획서 §4.4, 계획서 M5.
/// 9:16(360×640pt, @3x = 1080×1920px) 고정 카드 2종(미니멀 데이터형·사진 배경형)과
/// 경로 스냅샷·렌더 유틸. 라이브 Map은 ImageRenderer가 그리지 못해
/// 경로는 MKMapSnapshotter 정적 이미지 위에 polyline을 직접 그려 만든다.

// MARK: - 미니멀 데이터형 카드

struct ShareCardView: View {
    let run: RunSummary
    /// 세션 상세에서 지연 조회한 값 — 존·케이던스·고도 상승·1km 구간 페이스 (이슈 #221). 없는 항목은 숨긴다
    var detail: WorkoutDetail?
    var routeImage: UIImage?
    /// "최근 7일 3회 · 24.5 km" — 세션 목록에서 계산해 넘긴다 (기획서 §4.4 주간 요약)
    var weeklySummary: String?
    /// 날짜 줄에 시작~종료 시각을 적을지 — 끄면 시간대로 흐린다 (이슈 #191·#221)
    var showsTime = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "런미새 · " + dateLine)

            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text(run.distanceKm.map(Format.km) ?? "—")
                    .font(.system(size: 54, weight: .heavy, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text("km")
                    .font(.system(size: 18, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }
            .padding(.top, 8)

            statRow
                .padding(.top, 12)
            if !extraStats.isEmpty {
                HStack(spacing: 0) {
                    ForEach(extraStats, id: \.label) { stat($0.label, $0.value, $0.unit) }
                    // 3열 정렬 유지 — 빈 칸은 자리만 둔다 (값을 "—"로 채우지 않는다, 이슈 #221)
                    ForEach(extraStats.count..<3, id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                    }
                }
                .padding(.top, 10)
            }

            // 구간 표·존 막대가 늘면 지도를 줄여 넘치지 않게 한다 — 남는 높이를 지도가 먼저 가져간다
            Group {
                if let routeImage {
                    Image(uiImage: routeImage)
                        .resizable()
                        .scaledToFill()
                        .frame(maxWidth: .infinity, minHeight: Self.routeHeight(detail: detail),
                               maxHeight: Self.routeHeight(detail: detail))
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(RR.line))
                } else {
                    // 경로 이미지가 없으면(실내·경로 숨기기·트림 후 잔여 없음) 지도 대신 수치 강조 블록 (계획서 M5 완료 기준, 이슈 #84)
                    indoorBlock
                }
            }
            .layoutPriority(1)
            .padding(.top, 16)

            if !splitRows.isEmpty {
                splitTable
                    .padding(.top, 14)
            }

            if let zones = detail?.zones {
                ZoneBarView(fractions: zones)
                    .padding(.top, 14)
            }

            Spacer(minLength: 0)

            Divider().overlay(RR.line)
                .padding(.top, 14)

            HStack {
                Text(weeklySummary ?? "RUNNER REPORT")
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(RR.text2)
                Spacer()
                Text("런미새")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(RR.brand)
            }
            .padding(.top, 14)
        }
        .padding(28)
        .frame(width: 360, height: 640)
        .background(RR.bg)
    }

    private var statRow: some View {
        HStack(spacing: 0) {
            stat("평균 페이스", run.paceSecPerKm.map(Format.pace) ?? "—", "/km")
            stat("시간", Format.duration(run.durationSec), "h:m:s")
            stat("평균 심박", run.avgHeartRate.map { "\(Int($0.rounded()))" } ?? "—", "bpm")
        }
    }

    /// 케이던스·고도 상승 — 값이 있는 것만 (실내는 고도가 없고, 걸음 수가 없으면 케이던스가 없다)
    private var extraStats: [(label: String, value: String, unit: String)] {
        var stats: [(label: String, value: String, unit: String)] = []
        if let cadence = detail?.cadenceSpm ?? run.cadenceSpm {
            stats.append(("케이던스", "\(Int(cadence.rounded()))", "spm"))
        }
        if let elevation = detail?.elevationM {
            stats.append(("고도 상승", "\(Int(elevation.rounded()))", "m"))
        }
        return stats
    }

    /// 지도 높이 — 구간 표가 길수록 줄여 640pt 카드 안에 맞춘다 (이슈 #221).
    /// 지도 스냅샷은 scaledToFill로 깔려 비율이 다르면 경로가 잘리므로 ShareSheetView가 이 높이로 스냅샷을 뜬다.
    /// 수치는 케이던스 줄·존 막대까지 다 있을 때의 iPhone 17 시뮬레이터 실측(구간 표 한 줄 ≈ 16.5pt)
    static func routeHeight(detail: WorkoutDetail?) -> CGFloat {
        let rows = ShareSummary.splitRows(paces: detail?.splits.map(\.paceSecPerKm) ?? []).count
        guard rows > 0 else { return 204 }
        return min(204, 194 - 16.5 * CGFloat((rows + 1) / 2))
    }

    private var splitRows: [ShareSummary.SplitRow] {
        ShareSummary.splitRows(paces: detail?.splits.map(\.paceSecPerKm) ?? [])
    }

    /// 구간 페이스 2열 표 — 왼쪽 열을 먼저 채운다. 가장 빠른 구간은 브랜드색 (이슈 #221)
    private var splitTable: some View {
        let rows = splitRows
        let half = (rows.count + 1) / 2
        return VStack(alignment: .leading, spacing: 6) {
            Text("구간 페이스")
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            HStack(alignment: .top, spacing: 18) {
                splitColumn(rows[..<half])
                splitColumn(rows[half...])
            }
        }
    }

    private func splitColumn(_ rows: ArraySlice<ShareSummary.SplitRow>) -> some View {
        VStack(spacing: 2) {
            ForEach(rows, id: \.label) { row in
                HStack {
                    Text(row.label)
                        .foregroundStyle(row.isFastest ? RR.brand : RR.text3)
                    Spacer(minLength: 4)
                    Text(Format.pace(row.paceSecPerKm))
                        .fontWeight(row.isFastest ? .heavy : .semibold)
                        .foregroundStyle(row.isFastest ? RR.brand : RR.text)
                }
                .font(.system(size: 11.5, design: .monospaced))
            }
        }
        .frame(maxWidth: .infinity, alignment: .top)
    }

    private func stat(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            Text(value)
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(unit)
                .font(.system(size: 10.5, design: .monospaced))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var indoorBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 경로를 숨긴 야외 러닝도 이 블록을 쓴다 — 트레드밀로 오표기하지 않는다 (이슈 #84)
            Text(run.isIndoor ? "TREADMILL RUN" : "OUTDOOR RUN")
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .kerning(1.4)
                .foregroundStyle(RR.text3)
            HStack(spacing: 0) {
                stat("시간", Format.duration(run.durationSec), "h:m:s")
                stat("칼로리", run.calories.map(Format.kcal) ?? "—", "kcal")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .frame(minHeight: 96, maxHeight: 204, alignment: .topLeading)
        .background(RR.surface2, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(RR.line))
    }

    private var dateLine: String {
        RoutePrivacy.cardDateLine(run.start, end: showsTime ? run.end : nil)
    }
}

// MARK: - 사진 배경형 카드

/// 사용자가 고른 사진 위에 어둡기 오버레이 + 핵심 수치.
/// 사진 위 텍스트는 모드와 무관하게 읽혀야 해서 RR 적응 토큰 대신
/// 고정 흑백을 쓴다 — 지도 헤더 오버레이와 같은 예외 (SessionDetailScreen).
struct PhotoCardView: View {
    let run: RunSummary
    var photo: UIImage?
    /// 날짜 줄에 시작~종료 시각을 적을지 — 미니멀 카드와 같은 토글 (이슈 #221)
    var showsTime = true

    var body: some View {
        ZStack {
            Group {
                if let photo {
                    Image(uiImage: photo)
                        .resizable()
                        .scaledToFill()
                } else {
                    // 사진 미선택 기본 배경 — 오버레이·텍스트와 같은 고정 무채색 계열
                    Color(white: 0.12)
                }
            }
            .frame(width: 360, height: 640)
            .clipped()

            // 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (이 카드의 흰 글자·검정 그라데이션 전부)
            // 수치 가독용 어둡기 오버레이 — 위·아래만 진하게, 가운데는 사진 그대로
            LinearGradient(stops: [
                .init(color: .black.opacity(0.5), location: 0),
                .init(color: .clear, location: 0.32),
                .init(color: .clear, location: 0.55),
                .init(color: .black.opacity(0.62), location: 1),
            ], startPoint: .top, endPoint: .bottom)

            VStack(alignment: .leading, spacing: 0) {
                Text("런미새 · \(dateLine)")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .kerning(1.4)
                    .foregroundStyle(.white.opacity(0.85))

                Spacer(minLength: 0)

                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    Text(run.distanceKm.map(Format.km) ?? "—")
                        .font(.system(size: 58, weight: .heavy, design: .monospaced))
                        .foregroundStyle(.white)
                    Text("km")
                        .font(.system(size: 19, weight: .bold, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.7))
                }

                HStack(spacing: 0) {
                    stat("평균 페이스", run.paceSecPerKm.map(Format.pace) ?? "—", "/km")
                    stat("시간", Format.duration(run.durationSec), "h:m:s")
                    stat("평균 심박", run.avgHeartRate.map { "\(Int($0.rounded()))" } ?? "—", "bpm")
                }
                .padding(.top, 18)

                Text("런미새")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.top, 22)
            }
            .padding(28)
            .frame(width: 360, height: 640, alignment: .leading)
        }
        .frame(width: 360, height: 640)
    }

    private func stat(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(.white.opacity(0.65))
            Text(value)
                .font(.system(size: 21, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(unit)
                .font(.system(size: 10.5, design: .monospaced))
                .foregroundStyle(.white.opacity(0.65))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var dateLine: String {
        RoutePrivacy.cardDateLine(run.start, end: showsTime ? run.end : nil)
    }
}

// MARK: - 경로 스냅샷

/// MKMapSnapshotter 래퍼 — snapshotter는 오버레이를 지원하지 않아
/// 스냅샷 이미지 위에 polyline을 직접 그린다 (계획서 M5)
enum RouteSnapshot {
    static func image(route: [CLLocationCoordinate2D], size: CGSize) async -> UIImage? {
        guard route.count >= 2 else { return nil }
        let options = MKMapSnapshotter.Options()
        options.region = region(for: route)
        options.size = size
        // 카드가 늘 라이트라 지도 타일·경로 색도 라이트로 고정한다 — 다크 모드 기기에서 어두운 지도가 박히지 않게 (이슈 #221)
        options.traitCollection = UITraitCollection(userInterfaceStyle: .light)
        guard let snapshot = try? await MKMapSnapshotter(options: options).start() else {
            return nil
        }
        return UIGraphicsImageRenderer(size: size).image { _ in
            snapshot.image.draw(at: .zero)
            let path = UIBezierPath()
            path.move(to: snapshot.point(for: route[0]))
            for coordinate in route.dropFirst() {
                path.addLine(to: snapshot.point(for: coordinate))
            }
            path.lineWidth = 4.5
            path.lineCapStyle = .round
            path.lineJoinStyle = .round
            UIColor(RR.brand).resolvedColor(with: options.traitCollection).setStroke()
            path.stroke()
        }
    }

    /// 경로 전체가 보이도록 여유(1.4배)를 준 지도 영역 — 세션 상세 지도 헤더와 공용
    static func region(for route: [CLLocationCoordinate2D]) -> MKCoordinateRegion {
        let lats = route.map(\.latitude)
        let lons = route.map(\.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (lats.min()! + lats.max()!) / 2,
            longitude: (lons.min()! + lons.max()!) / 2)
        return MKCoordinateRegion(center: center, span: MKCoordinateSpan(
            latitudeDelta: max((lats.max()! - lats.min()!) * 1.4, 0.008),
            longitudeDelta: max((lons.max()! - lons.min()!) * 1.4, 0.008)))
    }
}

// MARK: - 렌더 · 저장

/// 카드 뷰 → 1080×1920 이미지 (360×640 @3x — 계획서 M5), 사진 앱 저장(add-only)
@MainActor
enum ShareCardRenderer {
    static func render<Card: View>(_ card: Card) -> UIImage? {
        let renderer = ImageRenderer(content: card)
        renderer.proposedSize = .init(width: 360, height: 640)
        renderer.scale = 3
        return renderer.uiImage
    }

    /// add-only 저장 — 읽기 권한 없이 NSPhotoLibraryAddUsageDescription만 요구한다
    static func saveToPhotos(_ image: UIImage) async throws {
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }
}
