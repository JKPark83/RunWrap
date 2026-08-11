import SwiftUI
import MapKit

/// 세션 상세 — 지도 헤더 + 지표 그리드 + 구간 페이스 + 심박 존 (시안 "세션 상세 · 지도")
struct SessionDetailScreen: View {
    let run: RunSummary
    /// 이번 주가 과부하일 때만 전달 — 이 세션의 기여도를 배지로 보여준다
    var weeklyContext: WeeklyReport.DistanceCard? = nil

    @StateObject private var store = WorkoutDetailStore()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                mapHeader

                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 7) {
                        Eyebrow(text: dateLine)
                        Text(run.displayTitle)
                            .font(.system(size: 26, weight: .bold))
                            .foregroundStyle(RR.text)
                    }

                    if let badge = contributionBadge {
                        Text(badge)
                            .font(.system(size: 11.5, weight: .bold))
                            .foregroundStyle(RR.dang)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(RR.dangSoft,
                                        in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }

                    statsGrid
                    if let detail = store.detail, detail.splits.count >= 3 {
                        splitsCard(detail)
                    }
                    if let zones = store.detail?.zones {
                        zonesCard(zones, hrMaxEstimated: store.detail?.hrMaxEstimated ?? false)
                    }
                    shareTeaser
                }
                .padding(.horizontal, 18)
            }
            .padding(.bottom, 26)
        }
        .ignoresSafeArea(edges: .top)
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .topLeading) { backButton }
        .task { await store.load(run: run) }
    }

    // MARK: 지도 헤더

    private var mapHeader: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let route = store.detail?.route, route.count >= 2 {
                    Map(initialPosition: .region(region(for: route)),
                        interactionModes: []) {
                        MapPolyline(coordinates: route)
                            .stroke(RR.brand, style: StrokeStyle(lineWidth: 4,
                                                                 lineCap: .round, lineJoin: .round))
                    }
                } else {
                    ZStack {
                        RR.surface2
                        VStack(spacing: 8) {
                            Image(systemName: "map")
                                .font(.system(size: 24))
                                .foregroundStyle(RR.text3)
                            Text(store.isLoading ? "경로를 불러오는 중" : "경로 기록이 없어요")
                                .font(.system(size: 12.5))
                                .foregroundStyle(RR.text3)
                        }
                    }
                }
            }
            .frame(height: 320)
            .clipped()

            LinearGradient(colors: [.black.opacity(0.42), .clear],
                           startPoint: .top, endPoint: .bottom)
                .frame(height: 110)
                .frame(maxHeight: .infinity, alignment: .top)
                .allowsHitTesting(false)

            if let km = run.distanceKm {
                Text("러닝 경로 · \(Format.km(km)) km")
                    .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.5),
                                in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .padding(14)
            }
        }
    }

    private var backButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "chevron.left")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(.black.opacity(0.42), in: Circle())
        }
        .padding(.leading, 14)
        .padding(.top, 8)
    }

    private func region(for route: [CLLocationCoordinate2D]) -> MKCoordinateRegion {
        let lats = route.map(\.latitude)
        let lons = route.map(\.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (lats.min()! + lats.max()!) / 2,
            longitude: (lons.min()! + lons.max()!) / 2)
        return MKCoordinateRegion(center: center, span: MKCoordinateSpan(
            latitudeDelta: max((lats.max()! - lats.min()!) * 1.4, 0.008),
            longitudeDelta: max((lons.max()! - lons.min()!) * 1.4, 0.008)))
    }

    // MARK: 텍스트 조각

    private var dateLine: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M.d (E) · HH:mm"
        return formatter.string(from: run.start)
    }

    /// 과부하 주간에 이 세션이 최근 7일 거리의 40% 이상이면 맥락 배지
    private var contributionBadge: String? {
        guard let context = weeklyContext,
              let km = run.distanceKm, context.recent7Km > 0,
              run.start >= Date().addingTimeInterval(-7 * 86_400) else { return nil }
        let share = km / context.recent7Km
        guard share >= 0.4 else { return nil }
        return "이번 주 거리의 \(Int((share * 100).rounded()))%가 이 한 번에서 나왔어요"
    }

    // MARK: 지표 그리드 (3×2)

    private var statsGrid: some View {
        let cadence = store.detail?.cadenceSpm.map { "\(Int($0.rounded()))" } ?? "—"
        let elevation = store.detail?.elevationM.map { "\(Int($0.rounded()))" } ?? "—"
        let cells: [(String, String, String)] = [
            ("거리", run.distanceKm.map(Format.km) ?? "—", "km"),
            ("시간", Format.duration(run.durationSec), "h:m:s"),
            ("평균 페이스", run.paceSecPerKm.map(Format.pace) ?? "—", "/km"),
            ("평균 심박", run.avgHeartRate.map { "\(Int($0.rounded()))" } ?? "—", "bpm"),
            ("케이던스", cadence, "spm"),
            ("상승 고도", elevation, "m"),
        ]
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, cell in
                VStack(alignment: .leading, spacing: 4) {
                    Text(cell.0)
                        .font(.system(size: 11))
                        .foregroundStyle(RR.text3)
                    Text(cell.1)
                        .font(.system(size: 20, weight: .bold, design: .monospaced))
                        .foregroundStyle(RR.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(cell.2)
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(RR.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 14)
                .overlay(alignment: .top) {
                    if index >= 3 { Divider().overlay(RR.line) }
                }
            }
        }
        .padding(.horizontal, 18)
        .rrCard(radius: 22)
    }

    // MARK: 구간별 페이스

    private func splitsCard(_ detail: WorkoutDetail) -> some View {
        let paces = detail.splits.map(\.paceSecPerKm)
        let avg = paces.reduce(0, +) / Double(paces.count)
        let lastQuarter = detail.splits.suffix(max(detail.splits.count / 4, 1))
        let lastAvg = lastQuarter.map(\.paceSecPerKm).reduce(0, +) / Double(lastQuarter.count)
        let drift = Int((lastAvg - avg).rounded())

        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("구간별 페이스")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("km splits")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            splitsSentence(drift: drift, count: lastQuarter.count)
                .font(.system(size: 13))
                .lineSpacing(4)
                .padding(.top, 9)

            SplitBarsChart(splits: detail.splits)
                .padding(.top, 14)
        }
        .padding(18)
        .rrCard(radius: 22)
    }

    private func splitsSentence(drift: Int, count: Int) -> Text {
        if drift >= 5 {
            return Text("후반 \(count) km에서 평균보다 ").foregroundStyle(RR.text2)
                + Text("\(drift)초").foregroundStyle(RR.warn).fontWeight(.semibold)
                + Text(" 느려졌습니다. 페이스 유지 실패 구간이 있어요.").foregroundStyle(RR.text2)
        }
        if drift <= -5 {
            return Text("후반 \(count) km를 평균보다 ").foregroundStyle(RR.text2)
                + Text("\(-drift)초").foregroundStyle(RR.pos).fontWeight(.semibold)
                + Text(" 빠르게 마쳤습니다. 네거티브 스플릿이에요.").foregroundStyle(RR.text2)
        }
        return Text("처음부터 끝까지 페이스가 고르게 유지됐습니다.").foregroundStyle(RR.text2)
    }

    // MARK: 심박 구간

    private func zonesCard(_ zones: [Double], hrMaxEstimated: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("심박 구간")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            ZoneBarView(fractions: zones)
                .padding(.top, 14)

            if hrMaxEstimated {
                Text("최대 심박 190 bpm 추정 기준 · 건강 앱에 생년월일을 넣으면 더 정확해져요")
                    .font(.system(size: 11))
                    .foregroundStyle(RR.text3)
                    .padding(.top, 12)
            }
        }
        .padding(18)
        .rrCard(radius: 22)
    }

    // MARK: 스토리 공유 티저 (로드맵 3단계)

    private var shareTeaser: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .strokeBorder(RR.text3, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                .frame(width: 52, height: 92)
                .overlay {
                    Text("9:16")
                        .font(.system(size: 9, design: .monospaced))
                        .foregroundStyle(RR.text3)
                }
                .opacity(0.6)

            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 7) {
                    Text("스토리 카드로 공유")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(RR.text2)
                    Text("준비 중")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(RR.brand)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(RR.brandSoft,
                                    in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                }
                Text("경로와 핵심 지표를 9:16 카드로 만들어 인스타그램 스토리에 바로 올리는 기능이 준비되고 있습니다.")
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RR.surface2, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
            .strokeBorder(RR.line, style: StrokeStyle(lineWidth: 1, dash: [5, 5])))
    }
}
