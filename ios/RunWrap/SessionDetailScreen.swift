import SwiftUI
import MapKit
import PhotosUI

/// 세션 상세 — 지도 헤더 + 지표 그리드 + 구간 페이스 + 심박 존 (시안 "세션 상세 · 지도")
struct SessionDetailScreen: View {
    let run: RunSummary
    /// 이번 주가 과부하일 때만 전달 — 이 세션의 기여도를 배지로 보여준다
    var weeklyContext: WeeklyReport.DistanceCard? = nil
    /// 러닝 후 러닝화 묻기 팝업의 한 장으로 쓸 때만 전달 (이슈 #206) — 러닝화 행 대신 고르는 목록을 펼치고
    /// 뒤로가기를 숨긴다(닫기는 팝업의 확인 버튼). 값은 '다시 보지 않기' 동작
    var onShoePromptOptOut: (() -> Void)? = nil

    @EnvironmentObject private var health: HealthStore
    @StateObject private var store = WorkoutDetailStore()
    @Environment(\.dismiss) private var dismiss
    @State private var showShare = false
    @State private var showsGPXExport = false
    /// 경로 플라이오버 전체 화면 (이슈 #224)
    @State private var showsFlyover = false
    /// 상단 스크림 표시 — 쉴 때는 지도 헤더를 가리지 않는다 (이슈 #211)
    @State private var scrolled = false
    /// 러닝화 (이슈 #171) — 이 세션에 신은 신발을 바꾼다. 등록한 신발이 없으면 행을 숨긴다
    @EnvironmentObject private var shoes: ShoeStore
    @State private var showsShoePicker = false
    @State private var showsShoeEditor = false
    // 심박 기준 (이슈 #56) — 0/빈 문자열이면 미설정 → 추정·건강 앱 값. 해석은 엔진 한 곳
    @AppStorage(ProfileKey.hrMaxManual) private var hrMaxManual = 0
    @AppStorage(ProfileKey.restingHRManual) private var restingHRManual = 0
    @AppStorage(ProfileKey.hrZoneMethod) private var hrZoneMethodRaw = ""

    /// 존·노력도·세션 상세가 공유하는 심박 기준 — 수동 > 추정 우선순위는 엔진이 정한다 (이슈 #56)
    private var heartRate: HeartRateProfile {
        TrainingGuideEngine.heartRateProfile(estimate: health.hrMaxEstimate,
                                             manualHrMax: hrMaxManual,
                                             manualRestingHR: restingHRManual,
                                             measuredRestingHR: health.restingHRBpm,
                                             zoneMethodRaw: hrZoneMethodRaw)
    }

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

                    VStack(spacing: 0) {
                        statsGrid
                        effortRow
                    }
                    .padding(.horizontal, 18)
                    .rrCard()
                    if onShoePromptOptOut != nil { shoePrompt } else { shoeRow }
                    if let heat = heatAdjustment {
                        heatCard(heat)
                    }
                    if store.loadFailed {
                        loadFailedCard
                    }
                    if let detail = store.detail, detail.splits.count >= 3 {
                        splitsCard(detail)
                    }
                    if let detail = store.detail, let profile = RoutePaceEngine.elevationProfile(detail.route) {
                        elevationCard(profile)
                    }
                    if let matches = store.courseMatches,
                       let standing = CourseMatchEngine.standing(of: run, in: matches) {
                        courseCard(matches, standing: standing)
                    }
                    if let drift = store.detail?.drift {
                        driftCard(drift, heat: heatAdjustment)
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
                    // 경로가 없는 세션(실내 등)은 내보낼 것이 없어 버튼을 숨긴다 (미노출 원칙, 이슈 #222)
                    if let detail = store.detail, detail.route.count >= 2 {
                        gpxRow
                    }
                }
                .padding(.horizontal, 18)
            }
            .padding(.top, run.isIndoor ? 44 : 0)  // 지도 헤더가 없으면 뒤로가기 버튼 자리 확보
            .rrTracksScroll($scrolled)
            .padding(.bottom, onShoePromptOptOut == nil ? 26 : 56)   // 팝업에서는 페이지 점 자리
        }
        .ignoresSafeArea(edges: run.isIndoor ? [] : .top)
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        // 뒤로가기 버튼 줄(위 8 + 34pt)까지 덮어 스크롤한 카드 글자가 버튼 밑에서 겹쳐 보이지 않게 한다 (이슈 #211)
        .rrStatusBarScrim(belowTop: onShoePromptOptOut == nil ? 46 : 0, visible: scrolled)
        .overlay(alignment: .topLeading) { if onShoePromptOptOut == nil { backButton } }
        .overlay(alignment: .topTrailing) { if onShoePromptOptOut == nil { headerShareButton } }
        .task {
            await load()
        }
        .onChange(of: health.state) { _, state in
            // 진입 시 목록이 로드 전이었다면 빈 기준선으로 끝났다 — 로드되면 스냅샷만 다시 부른다 (이슈 #92)
            guard case .loaded(let all) = state else { return }
            Task {
                await store.reloadSnapshots(others: all, excluding: run)
                await store.loadCourse(run: run, others: all)
            }
        }
        .fullScreenCover(isPresented: $showsFlyover) {
            if let track = flyoverTrack {
                RouteFlyoverScreen(track: track)
            }
        }
        .sheet(isPresented: $showShare) {
            ShareSheetView(run: run,
                           detail: store.detail,
                           route: store.detail?.route.thinned() ?? [],
                           weeklySummary: weeklySummaryLine)
        }
        .sheet(isPresented: $showsGPXExport) {
            if let detail = store.detail {
                GPXExportSheet(run: run, detail: detail)
            }
        }
        // 새 신발 등록 — 러닝화 행과 팝업 목록이 같이 쓴다. 등록 시각이 러닝보다 늦어
        // 자동 배정 대상이 아니므로 명시적으로 배정한다 (이슈 #206)
        .sheet(isPresented: $showsShoeEditor) {
            ShoeEditSheet(shoe: Shoe(name: "", createdAt: Date()), isNew: true,
                          isDefault: shoes.defaultShoeID == nil,
                          onSave: { saved, isDefault in
                              shoes.save(saved, isDefault: isDefault, runs: loadedRuns)
                              shoes.assign(runID: run.id, shoeID: saved.id)
                          },
                          onDelete: {})
        }
    }

    // MARK: 조회 실패

    /// 세부 기록 조회 실패 — 경로·스플릿·존 카드 자리에 다시 시도를 띄운다 (이슈 #102).
    /// 버튼 스타일은 다른 화면의 안내 카드(.bordered)와 같다
    private var loadFailedCard: some View {
        VStack(spacing: 10) {
            Text("세부 기록을 불러오지 못했어요")
                .font(.system(size: 13.5, weight: .semibold))
                .foregroundStyle(RR.text2)
            Button {
                Task { await load() }
            } label: {
                Label("다시 시도", systemImage: "arrow.clockwise")
                    .font(.system(size: 13.5, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)
            .disabled(store.isLoading)
        }
        .padding(16)
        .rrCard()
    }

    // MARK: 지도 헤더

    private var mapHeader: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let detail = store.detail, detail.route.count >= 2 {
                    routeMap(detail)
                } else {
                    ZStack {
                        RR.surface2
                        VStack(spacing: 8) {
                            Image(systemName: "map")
                                .font(.system(size: 24))
                                .foregroundStyle(RR.text3)
                            Text(store.isLoading ? "경로를 불러오는 중"
                                 : store.loadFailed ? "경로를 불러오지 못했어요" : "경로 기록이 없어요")
                                .font(.system(size: 12.5))
                                .foregroundStyle(RR.text3)
                        }
                    }
                }
            }
            .frame(height: 320)
            .clipped()

            // 사진/지도 위 오버레이라 스킴 무관 — 토큰 대상 아님 (히어로 그라데이션·거리 배지·뒤로 버튼)
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
        .overlay(alignment: .bottomTrailing) {
            // 플라이오버 화면과 같은 가드 — 경로가 없거나 점이 너무 적거나(20점 미만) 거리·시간이 0이면
            // 진입하지 않는다. 버튼만 따로 거르면 닫기 버튼도 없는 빈 fullScreenCover에 갇힌다 (이슈 #224)
            if flyoverTrack != nil {
                flyoverButton
            }
        }
    }

    /// 플라이오버 재생 경로 — 진입 버튼과 화면이 같은 값을 본다.
    /// 표시용 솎기(~600점)로 충분 — 원본 시각이 남아 있어 시간 비례 재생이 그대로다
    private var flyoverTrack: FlyoverEngine.Track? {
        FlyoverEngine.track(store.detail?.route.thinned() ?? [], distanceM: run.distanceKm.map { $0 * 1_000 })
    }

    /// 지도 오른쪽 아래 플라이오버 진입 — 거리 배지와 같은 지도 위 오버레이 스타일
    private var flyoverButton: some View {
        Button {
            showsFlyover = true
        } label: {
            Label("플라이오버", systemImage: "play.fill")
                .font(.system(size: 11.5, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.black.opacity(0.5),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .rrTapTarget()
        }
        .buttonStyle(.plain)
        .padding(14)
    }

    /// 페이스 색 구간마다 MapPolyline을 따로 칠한다 — 구간을 못 내면(표본 부족) 단색 brand.
    /// 각 km 지점에 작은 번호 점을 얹는다 (이슈 #222)
    private func routeMap(_ detail: WorkoutDetail) -> some View {
        let points = detail.route.thinned()
        let coordinates = points.map(\.coordinate)
        let segments = RoutePaceEngine.segments(points)
        let markers = RoutePaceEngine.kmMarkers(points, count: detail.splits.count)
        let line = StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
        return Map(initialPosition: .region(RouteSnapshot.region(for: coordinates)),
                   interactionModes: []) {
            if let segments {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, segment in
                    MapPolyline(coordinates: segment.points.map(\.coordinate))
                        .stroke(segment.color, style: line)
                }
            } else {
                MapPolyline(coordinates: coordinates).stroke(RR.brand, style: line)
            }
            ForEach(Array(markers.enumerated()), id: \.offset) { index, marker in
                Annotation("", coordinate: marker.coordinate) {
                    Text("\(index + 1)")
                        .font(.system(size: 8.5, weight: .bold, design: .monospaced))
                        .foregroundStyle(RR.text)
                        .frame(width: 15, height: 15)
                        .background(RR.surface, in: Circle())
                        .overlay(Circle().strokeBorder(RR.line))
                }
                .annotationTitles(.hidden)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if segments != nil { paceLegend.padding(14) }
        }
    }

    /// 페이스 색 범례 — 빠름(개선) → 느림(과부하)
    private var paceLegend: some View {
        HStack(spacing: 5) {
            Text("빠름")
            ForEach([RRTone.improving, .steady, .caution, .overload], id: \.self) { tone in
                Capsule().fill(tone.color).frame(width: 12, height: 4)
            }
            Text("느림")
        }
        .font(.system(size: 10, weight: .semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("경로 색은 구간 페이스 — 초록이 빠르고 빨강이 느려요")
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
                .rrTapTarget()
        }
        .buttonStyle(.plain)  // 기본 스타일은 라벨 밖으로 넓힌 탭 영역을 받지 않는다 (이슈 #212)
        .accessibilityLabel("뒤로")
        .padding(.leading, 14)
        .padding(.top, 8)
    }

    /// 뒤로가기 맞은편 공유 — 맨 아래 공유 카드와 같은 시트를 연다 (이슈 #210)
    private var headerShareButton: some View {
        Button {
            showShare = true
        } label: {
            Image(systemName: "square.and.arrow.up")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(.black.opacity(0.42), in: Circle())
                .rrTapTarget()
        }
        .buttonStyle(.plain)
        // 경로 로딩 중에 열면 카드에 경로가 빠진다 — 공유 카드와 같이 막는다 (이슈 #84)
        .disabled(store.isLoading)
        .accessibilityLabel("스토리 카드로 공유")
        .padding(.trailing, 14)
        .padding(.top, 8)
    }

    // MARK: 텍스트 조각

    private var dateLine: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M.d (E) · HH:mm"
        return formatter.string(from: run.start)
    }

    /// 과부하 주간에 이 세션이 최근 7일 거리의 40% 이상이면 맥락 배지.
    /// 기간 판정은 분모(recent7Km)와 같은 창 — 6일 전 자정부터 (이슈 #75)
    private var contributionBadge: String? {
        guard let context = weeklyContext,
              let km = run.distanceKm, context.recent7Km > 0,
              run.start >= Calendar.current.startOfDay(for: Date().addingTimeInterval(-6 * 86_400))
        else { return nil }
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
    }

    /// 그리드 아래 한 줄 — Apple 운동 노력도가 있을 때만 (iOS 18+, 이슈 #178)
    @ViewBuilder
    private var effortRow: some View {
        if let effort = store.detail?.effort {
            HStack(spacing: 6) {
                Image(systemName: "flame")
                    .font(.system(size: 12))
                    .foregroundStyle(RR.text3)
                    .accessibilityHidden(true)
                Text("노력도 \(Int(effort.score.rounded()))/10 · \(effort.label) · \(effort.sourceLabel)")
                    .font(.system(size: 13))
                    .foregroundStyle(RR.text2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 12)
            .overlay(alignment: .top) { Divider().overlay(RR.line) }
        }
    }

    // MARK: 러닝화 (이슈 #171)

    /// 고를 신발이 있으면(현역 신발 또는 이미 지정된 신발) 피커, 없으면 등록 시트 (이슈 #206)
    private var canPickShoe: Bool {
        shoes.shoes.contains { !$0.isRetired } || shoes.shoe(forRun: run.id) != nil
    }

    /// 사진 + "러닝화 / 페가수스 41" + 누적 거리 — 탭하면 은퇴하지 않은 신발 + "없음" 중에서 고른다.
    /// 신발이 없으면 "등록하기"로 항상 노출해 등록 후 이 러닝에 바로 지정한다 (이슈 #206)
    private var shoeRow: some View {
        let assigned = shoes.shoe(forRun: run.id)
        return Button {
            if canPickShoe { showsShoePicker = true } else { showsShoeEditor = true }
        } label: {
            HStack(spacing: 14) {
                Group {
                    if let assigned { ShoeImage(shoe: assigned) } else { ShoeView() }
                }
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("러닝화")
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text3)
                    Text(canPickShoe ? (assigned?.name ?? "없음") : "등록하기")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if let assigned {
                    Text("누적 \(Int(shoes.mileage(of: assigned, runs: loadedRuns).rounded())) km")
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(RR.text3)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .rrCard()
        .sheet(isPresented: $showsShoePicker) { shoePickerSheet }
    }

    /// 러닝화 고르기 시트 — 러닝 후 팝업과 같은 사진 목록(shoeList)을 쓴다. 고르면 바로 닫힌다
    private var shoePickerSheet: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("이 러닝에 신은 러닝화")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(RR.text)
                shoeList(shoes.shoes.filter { !$0.isRetired }, showsAdd: false)
            }
            .padding(.horizontal, 18)
            .padding(.top, 26)
            .padding(.bottom, 18)
        }
        .background(RR.bg)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onChange(of: shoes.shoe(forRun: run.id)?.id) { showsShoePicker = false }
    }

    // MARK: 러닝화 묻기 (이슈 #206) — 팝업에서 러닝화 행 자리에 들어간다

    /// 현역 신발이 있으면 이미지 목록에서 탭해 바로 배정, 없으면 등록 권유 + '다시 보지 않기'
    @ViewBuilder
    private var shoePrompt: some View {
        let activeShoes = shoes.shoes.filter { !$0.isRetired }
        if activeShoes.isEmpty {
            noShoeBody
        } else {
            Text("어떤 러닝화를 신었나요?")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
                .padding(.top, 6)
            shoeList(activeShoes)
        }
    }

    /// showsAdd: 고르기 시트에서는 끈다 — 시트 위에 등록 시트를 또 띄우지 않으려고
    private func shoeList(_ activeShoes: [Shoe], showsAdd: Bool = true) -> some View {
        let selectedID = shoes.shoe(forRun: run.id)?.id
        return VStack(spacing: 0) {
            ForEach(activeShoes) { shoe in
                choiceRow(isSelected: selectedID == shoe.id,
                          action: { shoes.assign(runID: run.id, shoeID: shoe.id) }) {
                    ShoeImage(shoe: shoe)
                        .frame(width: 40, height: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(shoe.name)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(RR.text)
                            .lineLimit(1)
                        Text("누적 \(Int(shoes.mileage(of: shoe, runs: loadedRuns).rounded())) km")
                            .font(.system(size: 12.5))
                            .foregroundStyle(RR.text2)
                    }
                }
                Divider().overlay(RR.line).padding(.leading, 68)
            }
            choiceRow(isSelected: selectedID == nil,
                      action: { shoes.assign(runID: run.id, shoeID: nil) }) {
                Image(systemName: "nosign")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(RR.text3)
                    .frame(width: 40, height: 40)
                Text("없음")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text)
            }
            if showsAdd {
                Divider().overlay(RR.line)
                Button {
                    showsShoeEditor = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 16, weight: .semibold))
                        Text("러닝화 추가")
                            .font(.system(size: 14, weight: .semibold))
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(RR.brand)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .rrCard()
    }

    private func choiceRow(isSelected: Bool, action: @escaping () -> Void,
                           @ViewBuilder content: () -> some View) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                content()
                Spacer(minLength: 8)
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(RR.brand)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// 신발이 없을 때 — 등록 권유 + 등록 + 다시 보지 않기
    private var noShoeBody: some View {
        VStack(spacing: 12) {
            ShoeView()
                .frame(width: 96, height: 96)
            Text("러닝화를 등록하면 누적 거리로 교체 시점을 알려드려요")
                .font(.system(size: 14.5))
                .foregroundStyle(RR.text2)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                showsShoeEditor = true
            } label: {
                Text("러닝화 등록")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.brand)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(RR.brandSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            Button("다시 보지 않기") {
                onShoePromptOptOut?()
                dismiss()
            }
            .font(.system(size: 13))
            .foregroundStyle(RR.text3)
            .buttonStyle(.plain)
        }
        .padding(16)
        .frame(maxWidth: .infinity)
        .rrCard()
    }

    /// 러닝화 자동 지정의 재료 — SettingsScreen.loadedRuns와 같은 방식 (이슈 #206)
    private var loadedRuns: [RunSummary] {
        if case .loaded(let runs) = health.state { runs } else { [] }
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

    // MARK: 같은 코스 (이슈 #223) — 본인 화면에만, 공유 카드에는 싣지 않는다

    private func courseCard(_ matches: [RunSummary],
                            standing: (ordinal: Int, rank: Int?)) -> some View {
        let paced = matches.filter { $0.paceSecPerKm != nil }
        let labels = paced.map { run in
            let parts = Calendar.current.dateComponents([.month, .day], from: run.start)
            return "\(parts.month ?? 0)/\(parts.day ?? 0)"
        }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("같은 코스 \(standing.ordinal)번째")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("\(matches.count)번 완주")
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            courseSentence(rank: standing.rank, count: paced.count)
                .font(.system(size: 13))
                .lineSpacing(4)
                .padding(.top, 9)

            if paced.count >= 2 {
                TrendLineChart(points: paced.compactMap(\.paceSecPerKm),
                               tint: RR.brand,
                               endLabels: (labels.first ?? "", labels.last ?? ""),
                               pointLabels: labels,
                               valueText: { Format.paceKm($0) })
                    .padding(.top, 14)
            }

            Text("회차별 평균 페이스 · 내려갈수록 빨라진 것")
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
                .padding(.top, 8)
        }
        .padding(18)
        .rrCard()
    }

    private func courseSentence(rank: Int?, count: Int) -> Text {
        guard let rank else {
            return Text("이 코스를 \(count)번 달린 기록이 있어요.").foregroundStyle(RR.text2)
        }
        if rank == 1 {
            return Text("이 코스 ").foregroundStyle(RR.text2)
                + Text("최고 기록").foregroundStyle(RR.pos).fontWeight(.semibold)
                + Text("이에요. 같은 길에서 스스로를 이기셨습니다.").foregroundStyle(RR.text2)
        }
        return Text("이 코스 \(count)번 중 ").foregroundStyle(RR.text2)
            + Text("\(rank)위").foregroundStyle(RR.text).fontWeight(.semibold)
            + Text(" 기록이에요.").foregroundStyle(RR.text2)
    }

    // MARK: 심박 드리프트 (Pw:HR 디커플링, 제안 문서 A2)

    private func driftCard(_ drift: DriftEngine.Result, heat: HeatEngine.Adjustment?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("심박 드리프트")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(String(format: "%+.1f%%", drift.decouplingPct))
                    .font(.system(size: 26, weight: .bold, design: .monospaced))
                    .foregroundStyle(drift.tone.color)
                Spacer()
                ToneBadge(tone: drift.tone)
            }
            .padding(.top, 12)

            Text(driftSentence(drift, heat: heat))
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
                .padding(.top, 10)
        }
        .padding(18)
        .rrCard()
    }

    /// 사실 먼저, 위트는 뒤 — 톤별 문장은 Friel 5% 기준을 그대로 옮긴다.
    /// 열 보정 카드가 뜬 더운 날(heat non-nil)의 caution은 원인을 유산소 기반으로 단정하지 않는다 —
    /// 더위도 후반 심박을 끌어올린다. 엔진은 날씨를 모르므로 톤은 그대로, 문장만 화면에서 바꾼다 (이슈 #100)
    private func driftSentence(_ drift: DriftEngine.Result, heat: HeatEngine.Adjustment?) -> String {
        switch drift.tone {
        case .improving:
            "후반에 오히려 심박 효율이 좋아졌어요. 엔진이 늦게 데워지는 타입이거나 컨디션이 계속 올라왔거나 — 어느 쪽이든 좋은 신호입니다."
        case .caution where heat != nil:
            "후반 심박이 \(Int(drift.decouplingPct.rounded()))% 더 들었지만, 더위로 오른 몫이 섞여 있어요. 더운 날엔 흔한 일이라 유산소 기반 문제로 단정하진 않을게요 — 선선한 날 한 번 더 재 보시죠."
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

    // MARK: 고도 프로필 (이슈 #222)

    private func elevationCard(_ profile: [RoutePaceEngine.ProfilePoint]) -> some View {
        let elevations = profile.map(\.elevationM)
        let low = Int((elevations.min() ?? 0).rounded())
        let high = Int((elevations.max() ?? 0).rounded())
        return VStack(alignment: .leading, spacing: 0) {
            Text("고도 프로필")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
            Text("최저 \(low)m · 최고 \(high)m")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.top, 9)
            TrendLineChart(points: elevations, tint: RR.brand,
                           endLabels: ("0 km", "\(Format.km(profile.last?.distanceKm ?? 0)) km"),
                           pointLabels: profile.map { "\(Format.km($0.distanceKm)) km" },
                           valueText: { "\(Int($0.rounded()))m" })
                .padding(.top, 14)
        }
        .padding(18)
        .rrCard()
    }

    // MARK: 심박 구간

    private func zonesCard(_ zones: [Double], detail: WorkoutDetail) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("심박 구간")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            ZoneBarView(fractions: zones)
                .padding(.top, 14)

            if let hr = detail.heartRate {
                VStack(alignment: .leading, spacing: 5) {
                    // 세션 최고 심박 — HRmax 대비 %로 강도를 한눈에 (제안 문서 A4).
                    // 직접 입력한 HRmax면 "추정"을 뗀다 (이슈 #56)
                    if let peak = detail.maxHeartRateBpm {
                        Text("최고 심박 \(Int(peak.rounded())) bpm · \(hr.hrMaxSource == .manual ? "" : "추정 ")HRmax의 \(Int((peak / hr.hrMax * 100).rounded()))%")
                    }
                    // 존 방식·HRmax 출처 — 어떤 기준으로 나눈 존인지 밝힌다 (이슈 #56)
                    Text(zoneBasisLine(hr))
                    if hr.hrMaxSource == .fallback {
                        Text("건강 앱에 생년월일을 넣거나 설정에서 최대 심박을 입력하면 더 정확해져요")
                    }
                }
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
                .padding(.top, 12)
            }
        }
        .padding(18)
        .rrCard()
    }

    /// "Karvonen(HRR) 기준 · HRmax 186 bpm(관찰 최대) · 안정 53 bpm"
    private func zoneBasisLine(_ hr: HeartRateProfile) -> String {
        var line = "\(hr.zoneMethod.label) 기준 · HRmax \(Int(hr.hrMax.rounded())) bpm(\(hr.hrMaxSource.label))"
        if hr.zoneMethod == .karvonen, let rest = hr.restingHR {
            line += " · 안정 \(Int(rest.rounded())) bpm"
        }
        return line
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
        // 기준선 창은 '지금'이 아니라 세션 직전 28일 — 과거 세션을 그 이후 기록과 비교하지 않는다 (이슈 #92)
        let engine = FormEngine(now: run.start)
        let advice = engine.baseline(of: store.formSnapshots, excluding: run.id)
            .map { engine.advice(session: session, baseline: $0) }

        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("주법")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
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
                } else if store.isLoadingSnapshots {
                    // 조회 중 빈 스냅샷으로 표본 부족 안내가 뜨지 않게 (이슈 #92)
                    Text("주법 기준선을 불러오는 중…")
                        .font(.system(size: 12.5))
                        .lineSpacing(4)
                        .foregroundStyle(RR.text3)
                } else {
                    Text("이 러닝 전 4주 야외 러닝이 5회 모이면 내 기준선과 비교한 주법 조언이 나와요.")
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
        // 경로 로딩 중에 열면 카드에 경로가 빠진다 — 불러오는 동안은 막는다 (이슈 #84)
        .disabled(store.isLoading)
    }

    /// GPX 내보내기 — 공유 카드 아래 한 줄. 시트에서 가림 여부를 고르고 공유한다 (이슈 #222)
    private var gpxRow: some View {
        Button {
            showsGPXExport = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "doc.badge.arrow.up")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.brand)
                VStack(alignment: .leading, spacing: 3) {
                    Text("GPX 파일로 내보내기")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(RR.text)
                    Text("Strava·Garmin 등에 경로·심박을 옮겨요")
                        .font(.system(size: 12.5))
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

    /// 주법 기준선 재료로 전체 목록을 넘긴다 — 창·표본 가드는 엔진이 건다 (계획서 M4).
    /// 진입 시와 조회 실패 뒤 다시 시도가 같은 경로를 탄다 (이슈 #102)
    private func load() async {
        if case .loaded(let all) = health.state {
            await store.load(run: run, others: all, heartRate: heartRate)
        } else {
            await store.load(run: run, heartRate: heartRate)
        }
    }

    /// 카드 하단 주간 요약 — 이 세션 기준 7일 러닝 횟수·거리 (기획서 §4.4, 이슈 #92)
    private var weeklySummaryLine: String? {
        guard case .loaded(let all) = health.state else { return nil }
        return ShareSummary.weeklyLine(runs: all, sessionStart: run.start, now: Date())
    }
}

// MARK: - 공유 시트 (계획서 M5)

/// 스타일 토글 + 카드 미리보기 + 사진 저장(add-only) + 공유 시트
private struct ShareSheetView: View {
    let run: RunSummary
    /// 존·케이던스·고도·구간 페이스 재료 — 아직 못 불러왔으면 nil이고 해당 항목은 카드에서 빠진다 (이슈 #221)
    var detail: WorkoutDetail?
    var route: [TrackPoint]
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
    /// 경로를 통째로 숨길지 — 다음 공유 때도 기억한다. 양끝 트림은 켜고 끔과 무관하게 항상 적용 (이슈 #84)
    @AppStorage("share.hidesRoute") private var hidesRoute = false
    /// 양끝을 가릴 반경(300/500/1000m) — 다음 공유 때도 기억한다 (이슈 #191)
    @AppStorage(RoutePrivacy.radiusKey) private var trimRadiusRaw = RoutePrivacy.defaultRadius.rawValue
    /// 날짜 줄에 시작~종료 시각을 적을지 — 기본 켜짐, 다음 공유 때도 기억한다 (이슈 #221)
    @AppStorage("share.showsTime") private var showsTime = true
    @State private var rendered: UIImage?
    @State private var saveMessage: String?

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

            // 사진 카드는 경로를 그리지 않으므로 미니멀 카드에서만 보인다
            if style == .minimal {
                hideRouteRow
                    .padding(.horizontal, 40)
                radiusRow
                    .padding(.horizontal, 40)
            }
            // 날짜 줄은 두 카드 모두에 있어 스타일과 무관하게 보인다
            showsTimeRow
                .padding(.horizontal, 40)

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
        // 시트를 연 뒤 경로가 채워져도 다시 만들도록 route.count를 id로 건다 (이슈 #84)
        // 가림 반경이 바뀌어도 다시 그린다 (이슈 #191). 카드 지도 높이와 같은 비율로 떠야 경로가 잘리지 않는다 (이슈 #221)
        .task(id: "\(route.count)-\(trimRadiusRaw)-\(ShareCardView.routeHeight(detail: detail))") {
            guard route.count >= 2 else { return }
            routeImage = nil
            // 집 근처가 드러나지 않게 시작·끝을 선택한 반경만큼 잘라낸 경로만 그린다 (이슈 #84·#191)
            let image = await RouteSnapshot.image(route: RoutePrivacy.trimmed(route, meters: radius.meters,
                                                                             coordinate: \.coordinate),
                                                  size: CGSize(width: 304, height: ShareCardView.routeHeight(detail: detail)))
            // 스냅샷은 취소를 무시하고 끝나므로, 반경이 바뀐 뒤 늦게 온 옛 반경 이미지를 버린다
            guard !Task.isCancelled else { return }
            routeImage = image
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

    /// 스타일·사진·경로 이미지·경로 숨김·가림 반경·시각 표시·상세 로드가 바뀔 때만 다시 렌더한다
    private var renderKey: String {
        "\(style.rawValue)-\(photoVersion)-\(routeImage != nil)-\(hidesRoute)-\(trimRadiusRaw)-\(showsTime)-\(detail != nil)"
    }

    private var radius: RoutePrivacy.Radius {
        RoutePrivacy.radius(rawValue: trimRadiusRaw)
    }

    private var hideRouteRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("경로 숨기기")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text("집 근처 \(radius.label)는 항상 가려져요")
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 8)
            Toggle("경로 숨기기", isOn: $hidesRoute)
                .labelsHidden()
                .tint(RR.brand)
        }
    }

    /// 가림 반경 선택 — 경로를 통째로 숨기면 의미가 없어 흐리게 막는다 (이슈 #191)
    private var radiusRow: some View {
        HStack(spacing: 12) {
            Text("가릴 반경")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.text)
            Spacer(minLength: 8)
            Picker("가릴 반경", selection: $trimRadiusRaw) {
                ForEach(RoutePrivacy.Radius.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 190)
        }
        .disabled(hidesRoute)
        .opacity(hidesRoute ? 0.5 : 1)
    }

    /// 분 단위 시각 표시 토글 — 끄면 "아침·저녁" 같은 시간대로 흐린다 (이슈 #191·#221)
    private var showsTimeRow: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("시각 표시")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text(showsTime ? "시작~종료 시각이 보여요" : "아침·저녁처럼 시간대만 보여요")
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 8)
            Toggle("시각 표시", isOn: $showsTime)
                .labelsHidden()
                .tint(RR.brand)
        }
    }

    /// 미리보기와 저장 이미지 공통 — 기기 모드와 무관하게 늘 라이트로 그린다.
    /// 스토리는 남의 피드에 섞여 보여 다크 카드가 튀므로 한 가지로 고정한다 (이슈 #221)
    @ViewBuilder
    private var currentCard: some View {
        Group {
            switch style {
            case .minimal:
                ShareCardView(run: run, detail: detail,
                              routeImage: hidesRoute ? nil : routeImage,
                              weeklySummary: weeklySummary,
                              showsTime: showsTime)
            case .photo:
                PhotoCardView(run: run, photo: photo, showsTime: showsTime)
            }
        }
        .environment(\.colorScheme, .light)
    }

    private var cardPreview: some View {
        currentCard
            .frame(width: 360, height: 640)
            .scaleEffect(0.52)
            .frame(width: 360 * 0.52, height: 640 * 0.52)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(RR.line))
            .shadow(color: RR.shadowStrong, radius: 14, y: 8)
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
                        .foregroundStyle(RR.onBrand)
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

// MARK: - GPX 내보내기 시트 (이슈 #222)

/// "시작·끝 가리기" 토글 + 공유. 본인이 다른 서비스로 옮기는 용도라 원본이 기대값 — 가리기는 기본 꺼짐.
/// 파일은 임시 디렉터리에 쓰고 ShareLink로 넘긴다 — 앱은 어디에도 보내지 않는다
private struct GPXExportSheet: View {
    let run: RunSummary
    let detail: WorkoutDetail

    @State private var trims = false
    @State private var fileURL: URL?
    /// 공유 카드와 같은 가림 반경을 쓴다 (이슈 #191)
    @AppStorage(RoutePrivacy.radiusKey) private var trimRadiusRaw = RoutePrivacy.defaultRadius.rawValue

    private var radius: RoutePrivacy.Radius { RoutePrivacy.radius(rawValue: trimRadiusRaw) }

    var body: some View {
        VStack(spacing: 18) {
            Text("GPX 내보내기")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(RR.text)
                .padding(.top, 24)

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("시작·끝 가리기")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(RR.text)
                    Text("경로 양끝 \(radius.label)를 빼고 내보내요")
                        .font(.system(size: 11.5))
                        .foregroundStyle(RR.text3)
                }
                Spacer(minLength: 8)
                Toggle("시작·끝 가리기", isOn: $trims)
                    .labelsHidden()
                    .tint(RR.brand)
            }

            if let fileURL {
                ShareLink(item: fileURL, preview: SharePreview(fileURL.lastPathComponent)) {
                    Label("GPX 공유", systemImage: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(RR.onBrand)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(RR.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
            } else {
                Text("경로가 너무 짧아 가리면 남는 구간이 없어요")
                    .font(.system(size: 12))
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity)
        .background(RR.bg.ignoresSafeArea())
        .presentationDetents([.height(230)])
        .presentationDragIndicator(.visible)
        .task(id: trims) { fileURL = writeFile() }
    }

    /// 임시 디렉터리에 "러닝-2026-10-08.gpx"를 쓴다 — 가린 뒤 2점 미만이면 nil
    private func writeFile() -> URL? {
        let points = trims ? RoutePrivacy.trimmed(detail.route, meters: radius.meters, coordinate: \.coordinate)
                           : detail.route
        guard points.count >= 2 else { return nil }
        let gpx = GPXWriter.gpx(name: run.displayTitle, start: run.start,
                                segments: GPXWriter.segments(points),
                                heartRates: detail.heartRateSamples)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(GPXWriter.fileName(start: run.start))
        do {
            try gpx.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            return nil
        }
    }
}
