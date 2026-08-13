import SwiftUI
import MapKit
import PhotosUI

/// 세션 상세 — 지도 헤더 + 지표 그리드 + 구간 페이스 + 심박 존 (시안 "세션 상세 · 지도")
struct SessionDetailScreen: View {
    let run: RunSummary
    /// 이번 주가 과부하일 때만 전달 — 이 세션의 기여도를 배지로 보여준다
    var weeklyContext: WeeklyReport.DistanceCard? = nil

    @EnvironmentObject private var health: HealthStore
    @StateObject private var store = WorkoutDetailStore()
    @Environment(\.dismiss) private var dismiss
    @State private var showShare = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // 실내 세션은 경로가 없어 지도 헤더 자체를 걸어 두지 않는다 (기획서 §4.6)
                if !run.isIndoor { mapHeader }

                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 7) {
                        Eyebrow(text: dateLine)
                        HStack(spacing: 8) {
                            Text(run.displayTitle)
                                .font(RR.display(27))
                                .foregroundStyle(RR.text)
                            if run.isIndoor { IndoorBadge() }
                        }
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
                    if let heat = heatAdjustment {
                        heatCard(heat)
                    }
                    if let detail = store.detail, detail.splits.count >= 3 {
                        splitsCard(detail)
                    }
                    if let drift = store.detail?.drift {
                        driftCard(drift)
                    }
                    if let detail = store.detail, let zones = detail.zones {
                        zonesCard(zones, detail: detail)
                    }
                    if let detail = store.detail, hasDynamics(detail) {
                        formCard(detail)
                    }
                    if run.isIndoor {
                        Text("실내 러닝에는 경로·고도 데이터가 없어서 해당 섹션이 표시되지 않아요.")
                            .font(.system(size: 11.5))
                            .lineSpacing(3)
                            .foregroundStyle(RR.text3)
                            .padding(.horizontal, 4)
                    }
                    shareSection
                }
                .padding(.horizontal, 18)
            }
            .padding(.top, run.isIndoor ? 44 : 0)  // 지도 헤더가 없으면 뒤로가기 버튼 자리 확보
            .padding(.bottom, 26)
        }
        .ignoresSafeArea(edges: run.isIndoor ? [] : .top)
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .topLeading) { backButton }
        .task {
            // 주법 기준선 재료로 전체 목록을 넘긴다 — 창·표본 가드는 엔진이 건다 (계획서 M4)
            if case .loaded(let all) = health.state {
                await store.load(run: run, others: all)
            } else {
                await store.load(run: run)
            }
        }
        .sheet(isPresented: $showShare) {
            ShareSheetView(run: run,
                           zones: store.detail?.zones,
                           route: store.detail?.route ?? [],
                           weeklySummary: weeklySummaryLine)
        }
    }

    // MARK: 지도 헤더

    private var mapHeader: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let route = store.detail?.route, route.count >= 2 {
                    Map(initialPosition: .region(RouteSnapshot.region(for: route)),
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
        .rrCard()
    }

    // MARK: 열 보정 페이스 (제안 문서 A1)

    /// 야외 + 날씨 메타데이터 + 열 점수 38 초과일 때만 — 수치 가드는 HeatEngine이 건다
    private var heatAdjustment: HeatEngine.Adjustment? {
        guard !run.isIndoor, let pace = run.paceSecPerKm else { return nil }
        return HeatEngine.adjustment(paceSecPerKm: pace,
                                     tempC: run.weatherTempC,
                                     humidityPct: run.weatherHumidityPct)
    }

    private func heatCard(_ heat: HeatEngine.Adjustment) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("열 보정 페이스")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("heat adjusted")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(Format.pace(heat.adjustedPaceSecPerKm))
                    .font(.system(size: 26, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text("/km 상당")
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
                Spacer()
                Text("더위 몫 \(Int(heat.deltaSecPerKm.rounded()))초/km")
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundStyle(RR.warn)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(RR.warnSoft,
                                in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            }
            .padding(.top, 12)

            heatSentence(heat)
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .padding(.top, 10)
        }
        .padding(18)
        .rrCard()
    }

    private func heatSentence(_ heat: HeatEngine.Adjustment) -> Text {
        Text("기온 \(Int(heat.tempC.rounded()))°C · 습도 \(Int(heat.humidityPct.rounded()))%에서 뛰었어요. 서늘한 날이었다면 ")
            .foregroundStyle(RR.text2)
            + Text(Format.paceKm(heat.adjustedPaceSecPerKm)).foregroundStyle(RR.pos).fontWeight(.semibold)
            + Text(" 수준 — 더위 몫까지 뛰었으니 오늘 기록, 억울해하지 않으셔도 됩니다.")
            .foregroundStyle(RR.text2)
    }

    // MARK: 심박 드리프트 (Pw:HR 디커플링, 제안 문서 A2)

    private func driftCard(_ drift: DriftEngine.Result) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("심박 드리프트")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("hr decoupling")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(String(format: "%+.1f%%", drift.decouplingPct))
                    .font(.system(size: 26, weight: .bold, design: .monospaced))
                    .foregroundStyle(drift.tone.color)
                Spacer()
                ToneBadge(tone: drift.tone)
            }
            .padding(.top, 12)

            Text(driftSentence(drift))
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
                .padding(.top, 10)
        }
        .padding(18)
        .rrCard()
    }

    /// 사실 먼저, 위트는 뒤 — 톤별 문장은 Friel 5% 기준을 그대로 옮긴다
    private func driftSentence(_ drift: DriftEngine.Result) -> String {
        switch drift.tone {
        case .improving:
            "후반에 오히려 심박 효율이 좋아졌어요. 엔진이 늦게 데워지는 타입이거나 컨디션이 계속 올라왔거나 — 어느 쪽이든 좋은 신호입니다."
        case .caution:
            "같은 페이스인데 후반 심박이 \(Int(drift.decouplingPct.rounded()))% 더 들었어요. 이 거리엔 유산소 기반이 아직 덜 자랐다는 신호 — 편한 페이스 러닝을 늘리면 따라옵니다."
        default:
            "전·후반 심박 효율 차이가 5% 안이에요. 오늘 페이스는 몸이 끝까지 감당했다는 뜻입니다."
        }
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
        .rrCard()
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

    private func zonesCard(_ zones: [Double], detail: WorkoutDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("심박 구간")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            ZoneBarView(fractions: zones)
                .padding(.top, 14)

            VStack(alignment: .leading, spacing: 5) {
                // 세션 최고 심박 — HRmax 대비 %로 강도를 한눈에 (제안 문서 A4)
                if let peak = detail.maxHeartRateBpm, let hrMax = detail.hrMaxBpm, hrMax > 0 {
                    Text("최고 심박 \(Int(peak.rounded())) bpm · \(detail.hrMaxEstimated ? "추정 " : "")HRmax의 \(Int((peak / hrMax * 100).rounded()))%")
                }
                if detail.hrMaxEstimated {
                    Text("최대 심박 190 bpm 추정 기준 · 건강 앱에 생년월일을 넣으면 더 정확해져요")
                }
            }
            .font(.system(size: 11))
            .foregroundStyle(RR.text3)
            .padding(.top, 12)
        }
        .padding(18)
        .rrCard()
    }

    // MARK: 주법 (러닝 다이내믹스) — 기획서 §4.8, 계획서 M4

    /// 다이내믹스가 하나라도 있어야 카드를 건다 — 실내(미기록)·구형 워치의 이중 미노출 가드
    private func hasDynamics(_ detail: WorkoutDetail) -> Bool {
        detail.verticalOscillationCm != nil || detail.groundContactMs != nil
            || detail.strideLengthM != nil || detail.runningPowerW != nil
    }

    private func formCard(_ detail: WorkoutDetail) -> some View {
        let session = FormSnapshot(id: run.id, start: run.start,
                                   cadenceSpm: detail.cadenceSpm ?? run.cadenceSpm,
                                   verticalOscillationCm: detail.verticalOscillationCm,
                                   groundContactMs: detail.groundContactMs)
        let engine = FormEngine()
        let advice = engine.baseline(of: store.formSnapshots, excluding: run.id)
            .map { engine.advice(session: session, baseline: $0) }

        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("주법")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("running dynamics")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            dynamicsGrid(detail)
                .padding(.top, 4)

            Divider().overlay(RR.line)

            Group {
                if let advice, !advice.isEmpty {
                    VStack(alignment: .leading, spacing: 9) {
                        ForEach(advice, id: \.message) { adviceRow($0) }
                    }
                } else if advice != nil {
                    Text("평소 주법 리듬을 그대로 유지했어요. 지금 폼이 흔들리지 않게 이어가면 됩니다.")
                        .font(.system(size: 12.5))
                        .lineSpacing(4)
                        .foregroundStyle(RR.text2)
                } else {
                    Text("최근 4주 야외 러닝이 5회 모이면 내 기준선과 비교한 주법 조언이 나와요.")
                        .font(.system(size: 12.5))
                        .lineSpacing(4)
                        .foregroundStyle(RR.text3)
                }
            }
            .padding(.top, 13)
        }
        .padding(18)
        .rrCard()
    }

    private func dynamicsGrid(_ detail: WorkoutDetail) -> some View {
        let cells: [(String, String, String)] = [
            ("수직 진폭", detail.verticalOscillationCm.map { String(format: "%.1f", $0) } ?? "—", "cm"),
            ("지면 접촉", detail.groundContactMs.map { "\(Int($0.rounded()))" } ?? "—", "ms"),
            ("보폭", detail.strideLengthM.map { String(format: "%.2f", $0) } ?? "—", "m"),
            ("러닝 파워", detail.runningPowerW.map { "\(Int($0.rounded()))" } ?? "—", "W"),
        ]
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, cell in
                VStack(alignment: .leading, spacing: 4) {
                    Text(cell.0)
                        .font(.system(size: 11))
                        .foregroundStyle(RR.text3)
                    Text(cell.1)
                        .font(.system(size: 17, weight: .bold, design: .monospaced))
                        .foregroundStyle(RR.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(cell.2)
                        .font(.system(size: 10.5, design: .monospaced))
                        .foregroundStyle(RR.text3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 14)
            }
        }
    }

    private func adviceRow(_ item: FormAdvice) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: item.kind == .sensor
                  ? "exclamationmark.triangle.fill" : "shoeprints.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(item.kind == .sensor ? RR.warn : RR.brand)
                .padding(.top, 2)
            Text(item.message)
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
    }

    // MARK: 스토리 공유 (기획서 §4.4, 계획서 M5)

    private var shareSection: some View {
        Button {
            showShare = true
        } label: {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(RR.brandSoft)
                    .frame(width: 52, height: 92)
                    .overlay {
                        VStack(spacing: 5) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(RR.brand)
                            Text("9:16")
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundStyle(RR.brand)
                        }
                    }

                VStack(alignment: .leading, spacing: 6) {
                    Text("스토리 카드로 공유")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(RR.text)
                    Text("경로와 핵심 지표를 9:16 카드로 만들어 저장하거나 스토리에 올려보세요.")
                        .font(.system(size: 12.5))
                        .lineSpacing(3)
                        .foregroundStyle(RR.text3)
                }

                Spacer(minLength: 0)

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RR.text3)
            }
            .padding(16)
            .rrCard()
        }
        .buttonStyle(.plain)
    }

    /// 카드 하단 주간 요약 — 최근 7일 러닝 횟수·거리 (기획서 §4.4)
    private var weeklySummaryLine: String? {
        guard case .loaded(let all) = health.state else { return nil }
        let recent = all.filter { $0.start >= Date().addingTimeInterval(-7 * 86_400) }
        guard !recent.isEmpty else { return nil }
        let km = recent.compactMap(\.distanceKm).reduce(0, +)
        return "최근 7일 \(recent.count)회 · \(Format.km(km)) km"
    }
}

// MARK: - 공유 시트 (계획서 M5)

/// 스타일 토글 + 카드 미리보기 + 사진 저장(add-only) + 공유 시트
private struct ShareSheetView: View {
    let run: RunSummary
    var zones: [Double]?
    var route: [CLLocationCoordinate2D]
    var weeklySummary: String?

    private enum CardStyle: String, CaseIterable {
        case minimal = "미니멀"
        case photo = "사진"
    }

    @State private var style: CardStyle = .minimal
    @State private var photoItem: PhotosPickerItem?
    @State private var photo: UIImage?
    @State private var photoVersion = 0
    @State private var routeImage: UIImage?
    @State private var rendered: UIImage?
    @State private var saveMessage: String?
    /// 미리보기와 렌더 이미지가 같은 모드로 그려지도록 명시적으로 주입한다
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(spacing: 16) {
            Text("스토리 카드")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(RR.text)
                .padding(.top, 24)

            Picker("카드 스타일", selection: $style) {
                ForEach(CardStyle.allCases, id: \.self) { Text($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 60)

            cardPreview
                .padding(.top, 4)

            if style == .photo {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label(photo == nil ? "배경 사진 선택" : "사진 바꾸기", systemImage: "photo")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(RR.brand)
                }
            }

            actionRow
                .padding(.horizontal, 22)
                .padding(.top, 2)

            if let saveMessage {
                Text(saveMessage)
                    .font(.system(size: 12))
                    .foregroundStyle(RR.text3)
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(RR.bg.ignoresSafeArea())
        .presentationDragIndicator(.visible)
        .task {
            guard routeImage == nil, route.count >= 2 else { return }
            routeImage = await RouteSnapshot.image(route: route,
                                                  size: CGSize(width: 360, height: 240))
        }
        .task(id: renderKey) {
            rendered = ShareCardRenderer.render(currentCard)
        }
        .onChange(of: photoItem) { _, item in
            Task {
                guard let item,
                      let data = try? await item.loadTransferable(type: Data.self),
                      let image = UIImage(data: data) else { return }
                photo = image
                photoVersion += 1
            }
        }
    }

    /// 스타일·사진·경로 이미지가 바뀔 때만 다시 렌더한다
    private var renderKey: String {
        "\(style.rawValue)-\(photoVersion)-\(routeImage != nil)"
    }

    @ViewBuilder
    private var currentCard: some View {
        Group {
            switch style {
            case .minimal:
                ShareCardView(run: run, zones: zones,
                              routeImage: routeImage, weeklySummary: weeklySummary)
            case .photo:
                PhotoCardView(run: run, photo: photo)
            }
        }
        .environment(\.colorScheme, colorScheme)
    }

    private var cardPreview: some View {
        currentCard
            .frame(width: 360, height: 640)
            .scaleEffect(0.52)
            .frame(width: 360 * 0.52, height: 640 * 0.52)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(RR.line))
            .shadow(color: .black.opacity(0.10), radius: 14, y: 8)
    }

    private var actionRow: some View {
        HStack(spacing: 12) {
            Button {
                Task { await save() }
            } label: {
                Label("사진에 저장", systemImage: "square.and.arrow.down")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(RR.surface,
                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(RR.line))
            }
            .buttonStyle(.plain)
            .disabled(rendered == nil)

            if let rendered {
                ShareLink(item: Image(uiImage: rendered),
                          preview: SharePreview("러닝 스토리 카드",
                                                image: Image(uiImage: rendered))) {
                    Label("공유", systemImage: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(RR.brand,
                                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func save() async {
        guard let rendered else { return }
        do {
            try await ShareCardRenderer.saveToPhotos(rendered)
            saveMessage = "사진 앱에 저장했어요"
        } catch {
            saveMessage = "저장하지 못했어요 — 설정에서 사진 추가 권한을 확인해 주세요"
        }
    }
}
