import CoreLocation
import MapKit
import SwiftUI
import UniformTypeIdentifiers

/// '코스' 탭 — 급수·화장실·편의점을 짚어 준다 (기획서 §4.13, 계획서 M12-3).
/// 위치도 코스 파일도 기기 밖으로 나가지 않는다 — 번들 POI와 온디바이스 매칭만 한다.
///
/// **두 가지 모드.** 들어오면 먼저 **현재 위치** 기준으로 주변 보급을 보여주고,
/// GPX를 올리면 **코스** 기준("몇 km 지점")으로 바뀐다. 둘을 한 화면에 둔 이유는
/// 묻는 게 결국 같아서다 — "물 어디서 마시지". 다만 답의 단위가 달라
/// (직선 몇 m / 코스 몇 km) 엔진과 행 표기를 나눠 둔다.
struct CourseScreen: View {
    /// 모드는 상태가 아니라 결과에서 파생된다 — 코스 결과가 있으면 코스, 없으면 주변
    private enum Mode { case nearby, course }

    /// 리스트와 지도가 함께 보는 선택 상태 — 어느 지점인지(poi)와 말풍선 문구(caption).
    /// 두 모드가 거리 단위를 달리 쓰므로(직선 m / 코스 km) 문구는 만든 쪽이 넣어 준다
    private struct Selection: Equatable {
        let poi: CoursePOI
        let caption: String
    }

    @StateObject private var store = CoursePOIStore()
    /// 보급 지점은 100m 단위가 의미를 가져 날씨(km)보다 정밀한 위치를 요청한다
    @StateObject private var location = LocationProvider(accuracy: kCLLocationAccuracyNearestTenMeters)
    @State private var course: [GeoPoint] = []
    @State private var courseName = ""
    @State private var result: CourseSupplyEngine.Result?
    @State private var nearby: NearbySupplyEngine.Result?
    @State private var notice: String?
    @State private var showImporter = false
    /// 보급 종류 필터 — 켜진 종류만 지도·리스트에 남긴다.
    /// 기본값은 셋 다 켬(= 전체 표시)이라 필터를 모르는 사용자도 종전과 같은 화면을 본다.
    /// 마지막 하나는 끌 수 없다 — 전부 끄면 빈 화면이 되고, "전체 보기"는 셋 다 켠 상태다
    @State private var kindFilter: Set<CoursePOI.Kind> = [.water, .toilet, .convenience]
    /// 지도 카메라 — 손으로 끌어 옮긴 뒤에도 되돌아올 수 있도록 바인딩으로 쥔다
    @State private var camera: MapCameraPosition = .automatic
    /// 리스트·지도가 공유하는 선택 지점. nil이면 아무것도 고르지 않은 상태
    @State private var selected: Selection?
    /// 위치 갱신 버튼 진행 표시 — 위치가 오거나 실패하면 내린다
    @State private var isRefreshing = false
    /// 마지막 코스 파일명 — 파일 자체는 Application Support에 캐시해 재진입 시 유지
    @AppStorage("lastCourseName") private var lastCourseName = ""

    /// 주변 검색 반경 — 뛰어서 몇 분 거리. 1km면 왕복 10분 남짓이라 "들를 만한" 상한이다
    private static let nearbyRadius: Double = 1_000
    /// 지점 하나를 골랐을 때 잡아 주는 지도 폭 — 골목이 보이면서 주변 맥락도 남는 정도
    private static let focusMeters: Double = 500
    /// 핀 탭 판정 여유(pt) — 핀(16pt)보다 넉넉해야 손가락으로 짚힌다
    private static let pinTapSlop: CGFloat = 22

    private var mode: Mode { result == nil ? .nearby : .course }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header

                if case .failed = store.state {
                    noticeCard("보급 데이터를 불러오지 못했어요",
                               message: "앱을 껐다 다시 열어 주세요. 계속 그러면 재설치가 필요할 수 있어요.",
                               symbol: "exclamationmark.triangle")
                } else if let result {
                    courseMapCard(result)
                    courseListCard(result)
                    if !result.matches.contains(where: { $0.poi.kind == .water }) {
                        waterGapNote
                    }
                    uploadButtons(compact: true)
                    attribution
                } else {
                    if let notice {
                        noticeCard("코스를 읽지 못했어요", message: notice, symbol: "map")
                    }
                    nearbySection
                    uploadButtons(compact: false)
                    attribution
                }
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: [.gpx, .xml]) { pick in
            guard case .success(let url) = pick else { return }
            // fileImporter가 주는 URL은 보안 스코프 밖 접근이 막혀 있다
            let secured = url.startAccessingSecurityScopedResource()
            defer { if secured { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                notice = "파일을 여는 데 실패했어요. 다른 앱에서 내보낸 GPX인지 확인해 주세요."
                return
            }
            apply(data: data, name: url.deletingPathExtension().lastPathComponent)
        }
        .task {
            await store.load()
            restoreLastCourse()
            analyze()
            // 코스가 복원됐다면 주변 검색은 건너뛴다 — 화면에 안 쓸 위치를 굳이 받지 않는다
            if result == nil {
                location.request()
                // 위치를 이미 갖고 들어온 경우(탭 재진입) onChange가 안 울려서 여기서 한 번 돌린다
                searchNearby()
            }
        }
        .onChange(of: location.state) { _, new in
            searchNearby()
            // 갱신은 답이 나오면 끝난다 — 거부·실패도 답이다(각각 안내 카드로 바뀐다)
            switch new {
            case .located, .denied, .failed: isRefreshing = false
            case .idle, .loading: break
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 7) {
            Eyebrow(text: "보급 가이드")
            Text(mode == .course ? "코스, 미리 짚어 드려요" : "지금 근처, 짚어 드려요")
                .font(RR.display(27))
                .foregroundStyle(RR.text)
        }
        .padding(.top, 18)
    }

    // MARK: 현재 위치 모드

    @ViewBuilder
    private var nearbySection: some View {
        switch location.state {
        case .idle, .loading:
            locatingCard
        case .denied:
            noticeCard("위치를 쓸 수 없어요",
                       message: "설정 앱 → 개인정보 보호 및 보안 → 위치 서비스에서 런미새를 켜 주시면 지금 근처의 보급 지점을 짚어 드릴게요. 그동안은 아래에서 GPX 코스를 올려 주셔도 됩니다.",
                       symbol: "location.slash")
        case .failed:
            noticeCard("위치를 못 받았어요",
                       message: "실내나 지하에서는 위치를 잡기 어려울 수 있어요. 잠시 뒤 다시 들어와 주세요.",
                       symbol: "location.slash")
        case .located:
            if let nearby {
                nearbyMapCard(nearby)
                nearbyListCard(nearby)
                if !nearby.matches.contains(where: { $0.poi.kind == .water }) {
                    waterGapNote
                }
            } else {
                // 위치는 받았는데 POI가 아직 안 올라온 찰나 — 로딩과 같은 카드로 덮는다
                locatingCard
            }
        }
    }

    private var locatingCard: some View {
        HStack(spacing: 12) {
            ProgressView()
            Text("지금 계신 곳을 찾고 있어요")
                .font(.system(size: 13.5))
                .foregroundStyle(RR.text2)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func nearbyMapCard(_ nearby: NearbySupplyEngine.Result) -> some View {
        ZStack(alignment: .bottomLeading) {
            MapReader { proxy in
                Map(position: $camera, interactionModes: [.pan, .zoom]) {
                    UserAnnotation()
                    ForEach(Array(visibleNearby(nearby).enumerated()), id: \.offset) { _, match in
                        Annotation("", coordinate: CLLocationCoordinate2D(latitude: match.poi.lat,
                                                                         longitude: match.poi.lon)) {
                            poiPin(match.poi, caption: nearbyCaption(match))
                        }
                    }
                }
                .onTapGesture { point in
                    tapMap(at: point, proxy: proxy,
                           candidates: visibleNearby(nearby).map { ($0.poi, nearbyCaption($0)) })
                }
            }
            .frame(height: 300)

            // 정수 km로 찍는다 — 아래 리스트 문구("반경 1km 안에서는…")와 표기를 맞춘다
            mapBadge("현재 위치 · 반경 \(Int(nearby.radiusMeters / 1_000))km")
        }
        .overlay(alignment: .topTrailing) { refreshButton }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(RR.line))
    }

    /// 위치 갱신 버튼 — 주변 모드에만 둔다.
    /// 코스 모드는 기준이 현재 위치가 아니고, 권한 거부 상태에서는 지도 대신 안내 카드가 서므로
    /// 이 버튼도 함께 사라진다 (이슈 #4)
    private var refreshButton: some View {
        Button {
            refreshLocation()
        } label: {
            ZStack {
                Circle().fill(RR.surface)
                if isRefreshing {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "location.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(RR.brand)
                }
            }
            .frame(width: 38, height: 38)
            .overlay(Circle().strokeBorder(RR.line))
        }
        .buttonStyle(.plain)
        .disabled(isRefreshing)
        .padding(12)
        .accessibilityLabel("현재 위치 갱신")
    }

    /// 위치를 다시 받아 핀·리스트·거리 표기를 새 좌표 기준으로 바꾼다.
    /// 좌표가 그대로여도 state가 loading을 거쳐 다시 located가 되므로 카메라 복귀는 항상 일어난다
    private func refreshLocation() {
        guard !isRefreshing else { return }
        isRefreshing = true
        location.request()
    }

    private func nearbyListCard(_ nearby: NearbySupplyEngine.Result) -> some View {
        let visible = visibleNearby(nearby)
        return VStack(alignment: .leading, spacing: 14) {
            Text("지금 근처 보급 지점")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            kindFilterBar { kind in nearby.matches.filter { $0.poi.kind == kind }.count }

            if nearby.matches.isEmpty {
                Text("반경 1km 안에서는 보급 지점을 못 찾았어요. 물통을 챙기시는 편이 마음 편하겠어요.")
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text2)
            } else if visible.isEmpty {
                // 근처엔 보급이 있는데 지금 켠 종류만 없는 경우 — 위 문장과 원인이 다르다
                Text("고르신 종류는 이 근처에 없어요. 위 버튼으로 다른 종류를 켜 보세요.")
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text2)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.offset) { index, match in
                        if index > 0 { Divider().overlay(RR.line) }
                        supplyRow(lead: "\(Int(match.meters.rounded()))m",
                                  poi: match.poi,
                                  detail: "\(match.poi.kind.label) · 직선거리",
                                  caption: nearbyCaption(match))
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func visibleNearby(_ nearby: NearbySupplyEngine.Result) -> [NearbySupplyEngine.Match] {
        nearby.matches.filter { kindFilter.contains($0.poi.kind) }
    }

    /// 위치와 POI가 **둘 다** 준비된 순간에만 검색한다.
    /// POI는 `.task`에서 await한 뒤라 이미 있고, 늦게 오는 쪽은 위치다 —
    /// 그래서 호출 지점이 둘(로드 직후 1회 + 위치 onChange)이고 가드는 한 벌이다
    private func searchNearby() {
        // 코스 모드에서는 현재 위치가 기준이 아니다 — 늦게 도착한 위치가 코스 카메라를 흔들지 않게 한다
        guard result == nil,
              case .located(let coordinate) = location.state,
              case .loaded(let file) = store.state else { return }
        nearby = NearbySupplyEngine.search(
            center: GeoPoint(lat: coordinate.latitude, lon: coordinate.longitude),
            pois: file.pois,
            radiusMeters: Self.nearbyRadius)
        selected = nil
        // 반경 1km가 화면에 다 들어오도록 지름(2km)보다 조금 넉넉하게 잡는다.
        // 새 위치를 받을 때마다 카메라를 되돌린다 — 손으로 끌어 옮겼어도 여기서 복귀한다
        withAnimation(.easeInOut(duration: 0.3)) {
            camera = .region(MKCoordinateRegion(
                center: coordinate,
                latitudinalMeters: Self.nearbyRadius * 2.4,
                longitudinalMeters: Self.nearbyRadius * 2.4))
        }
    }

    // MARK: 코스 적용 · 분석

    private func apply(data: Data, name: String) {
        let points = GPXParser.parse(data)
        guard !points.isEmpty else {
            notice = "이 파일에는 경로가 없어요. 지점(웨이포인트)만 있는 GPX일 수 있으니 트랙이 담긴 파일로 부탁드려요."
            return
        }
        notice = nil
        course = points
        courseName = name
        try? data.write(to: Self.lastCourseURL)
        lastCourseName = name
        analyze()
    }

    private func analyze() {
        guard case .loaded(let file) = store.state, !course.isEmpty else { return }
        result = CourseSupplyEngine.analyze(course: course, pois: file.pois)
        selected = nil
        if result == nil {
            notice = "코스가 500m보다 짧아서 분석을 접었어요. 이 정도면 보급 없이도 완주하실 거라 믿어요."
        } else {
            // 새 코스가 통째로 보이게 카메라를 잡는다 (예전 Map(initialPosition:) + .id 리셋을 대신한다)
            camera = .region(RouteSnapshot.region(for: courseCoordinates))
        }
    }

    private func restoreLastCourse() {
        guard course.isEmpty, let data = try? Data(contentsOf: Self.lastCourseURL) else { return }
        let points = GPXParser.parse(data)
        guard !points.isEmpty else { return }
        course = points
        courseName = lastCourseName
    }

    /// 올린 코스를 지우고 현재 위치 모드로 돌아간다 — 캐시 파일까지 지워야 재진입 시 안 살아난다
    private func clearCourse() {
        try? FileManager.default.removeItem(at: Self.lastCourseURL)
        lastCourseName = ""
        course = []
        courseName = ""
        result = nil
        notice = nil
        selected = nil
        if case .located = location.state { searchNearby() } else { location.request() }
    }

    /// 코스 폴리라인 좌표 — 지도와 카메라 리셋이 같은 값을 본다
    private var courseCoordinates: [CLLocationCoordinate2D] {
        course.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
    }

    private static var lastCourseURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("LastCourse.gpx")
    }

    // MARK: 코스 지도 · 리스트

    private func courseMapCard(_ result: CourseSupplyEngine.Result) -> some View {
        ZStack(alignment: .bottomLeading) {
            MapReader { proxy in
                Map(position: $camera, interactionModes: [.pan, .zoom]) {
                    MapPolyline(coordinates: courseCoordinates)
                        .stroke(RR.brand, style: StrokeStyle(lineWidth: 4,
                                                             lineCap: .round, lineJoin: .round))
                    ForEach(Array(visibleMatches(result).enumerated()), id: \.offset) { _, match in
                        Annotation("", coordinate: CLLocationCoordinate2D(latitude: match.poi.lat,
                                                                         longitude: match.poi.lon)) {
                            poiPin(match.poi, caption: courseCaption(match))
                        }
                    }
                }
                .onTapGesture { point in
                    tapMap(at: point, proxy: proxy,
                           candidates: visibleMatches(result).map { ($0.poi, courseCaption($0)) })
                }
            }
            .frame(height: 300)

            mapBadge("\(courseName) · \(Format.km(result.totalKm))km")
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(RR.line))
    }

    private func courseListCard(_ result: CourseSupplyEngine.Result) -> some View {
        let visible = visibleMatches(result)
        return VStack(alignment: .leading, spacing: 14) {
            Text("보급 지점")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            kindFilterBar { kind in result.matches.filter { $0.poi.kind == kind }.count }

            if result.matches.isEmpty {
                Text("코스 150m 안에서는 보급 지점을 못 찾았어요. 물통을 챙기시는 편이 마음 편하겠어요.")
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text2)
            } else if visible.isEmpty {
                // 코스엔 보급이 있는데 지금 켠 종류만 없는 경우 — 위 두 문장과 원인이 다르다
                Text("고르신 종류는 이 코스에 없어요. 위 버튼으로 다른 종류를 켜 보세요.")
                    .font(.system(size: 12.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text2)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(visible.enumerated()), id: \.offset) { index, match in
                        if index > 0 { Divider().overlay(RR.line) }
                        supplyRow(lead: "\(Format.km(match.courseKm))km",
                                  poi: match.poi,
                                  detail: "\(match.poi.kind.label) · 코스에서 \(Int(match.detourMeters.rounded()))m",
                                  caption: courseCaption(match))
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    /// 켜진 종류만 남긴 매칭 — 지도와 리스트가 같은 값을 본다
    private func visibleMatches(_ result: CourseSupplyEngine.Result)
        -> [CourseSupplyEngine.Match] {
        result.matches.filter { kindFilter.contains($0.poi.kind) }
    }

    // MARK: 공용 조각 — 두 모드가 같은 컴포넌트를 쓴다

    /// 급수·화장실·편의점 필터 버튼 — 각각 아이콘 + 이름 + 개수.
    /// 개수 세는 법만 모드마다 다르므로 클로저로 받는다.
    /// 없는 종류는 버튼도 흐리게 두되 누를 수는 있게 한다 (없다는 사실 자체가 정보다)
    private func kindFilterBar(count: @escaping (CoursePOI.Kind) -> Int) -> some View {
        HStack(spacing: 8) {
            ForEach([CoursePOI.Kind.water, .toilet, .convenience], id: \.self) { kind in
                let total = count(kind)
                let isOn = kindFilter.contains(kind)
                Button {
                    toggleKind(kind)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: kind.symbol)
                            .font(.system(size: 11, weight: .semibold))
                        Text(kind.label)
                            .font(.system(size: 12, weight: .semibold))
                        Text("\(total)")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .opacity(0.75)
                    }
                    .foregroundStyle(isOn ? .white : kind.color.opacity(total > 0 ? 1 : 0.45))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(isOn ? kind.color : kind.softColor,
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(kind.label) \(total)개")
                .accessibilityValue(isOn ? "표시 중" : "숨김")
            }
        }
    }

    /// 종류를 켜고 끈다. 마지막 하나는 끄지 않는다 —
    /// 전부 꺼진 지도는 정보가 없고, 사용자가 원한 건 "고르기"이지 "비우기"가 아니다
    private func toggleKind(_ kind: CoursePOI.Kind) {
        selected = nil  // 고른 지점이 방금 숨겨졌을 수도 있다 — 선택은 필터를 만질 때 접는다
        if kindFilter.contains(kind) {
            guard kindFilter.count > 1 else { return }
            kindFilter.remove(kind)
        } else {
            kindFilter.insert(kind)
        }
    }

    /// 보급 한 줄 — 맨 앞 값만 모드마다 다르다 (주변은 "320m", 코스는 "2.4km").
    /// 누르면 지도가 그 지점으로 옮겨 가므로 행 자체가 버튼이다
    private func supplyRow(lead: String, poi: CoursePOI,
                           detail: String, caption: String) -> some View {
        let isSelected = selected?.poi == poi
        return Button {
            select(poi, caption: caption)
        } label: {
            HStack(spacing: 12) {
                Text(lead)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                    .frame(width: 56, alignment: .leading)

                ZStack {
                    Circle().fill(poi.kind.softColor)
                    Image(systemName: poi.kind.symbol)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(poi.kind.color)
                }
                .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(poi.name)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(RR.text)
                        .lineLimit(1)
                    Text(detail)
                        .font(.system(size: 11.5))
                        .foregroundStyle(RR.text3)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 9)
            // 강조 배경만 글자보다 조금 넓게 — 바깥 음수 패딩으로 행 정렬은 그대로 둔다
            .padding(.horizontal, 8)
            .background(isSelected ? poi.kind.softColor : .clear,
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .padding(.horizontal, -8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("지도에서 위치를 짚어 드려요")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func poiPin(_ poi: CoursePOI, caption: String) -> some View {
        let isSelected = selected?.poi == poi
        return ZStack {
            Circle().fill(poi.kind.color)
            Image(systemName: poi.kind.symbol)
                .font(.system(size: isSelected ? 11 : 7, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: isSelected ? 26 : 16, height: isSelected ? 26 : 16)
        .overlay(Circle().strokeBorder(.white, lineWidth: isSelected ? 2 : 1.5))
        // 고른 핀은 크기 + 바깥 링으로 구분한다 — 같은 색 핀이 여럿이어도 한눈에 짚인다
        .overlay { if isSelected { Circle().strokeBorder(poi.kind.color.opacity(0.35),
                                                         lineWidth: 6).padding(-5) } }
        .overlay(alignment: .bottom) {
            if isSelected {
                ChartCallout(text: caption)
                    .offset(y: -34)
            }
        }
        // 핀 자체에는 제스처를 달지 않는다 — 지도에 붙인 탭이 먼저 먹어서 핀까지 오지 않는다.
        // 대신 지도 탭 한 곳에서 좌표를 되짚어(tapMap) 핀 선택과 해제를 같이 판정한다
        .allowsHitTesting(false)
    }

    /// 지도 탭 한 번으로 선택과 해제를 모두 판정한다.
    /// 핀은 16pt라 손가락으로 정확히 짚기 어려워 가까운 핀을 여유 반경 안에서 찾아 준다.
    /// 반경 밖이면 "빈 곳을 눌렀다"로 보고 선택을 접는다
    private func tapMap(at point: CGPoint, proxy: MapProxy,
                        candidates: [(poi: CoursePOI, caption: String)]) {
        let nearest = candidates.compactMap { candidate -> (CoursePOI, String, CGFloat)? in
            let coordinate = CLLocationCoordinate2D(latitude: candidate.poi.lat,
                                                    longitude: candidate.poi.lon)
            guard let screen = proxy.convert(coordinate, to: .local) else { return nil }
            return (candidate.poi, candidate.caption,
                    hypot(screen.x - point.x, screen.y - point.y))
        }.min { $0.2 < $1.2 }

        if let nearest, nearest.2 <= Self.pinTapSlop {
            select(nearest.0, caption: nearest.1)
        } else {
            deselect()
        }
    }

    /// 지점 하나를 골라 지도를 그리로 옮긴다. 같은 지점을 다시 누르면 접는다
    private func select(_ poi: CoursePOI, caption: String) {
        withAnimation(.easeInOut(duration: 0.25)) {
            guard selected?.poi != poi else {
                selected = nil
                return
            }
            selected = Selection(poi: poi, caption: caption)
            camera = .region(MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: poi.lat, longitude: poi.lon),
                latitudinalMeters: Self.focusMeters,
                longitudinalMeters: Self.focusMeters))
        }
    }

    private func deselect() {
        guard selected != nil else { return }
        withAnimation(.easeInOut(duration: 0.25)) { selected = nil }
    }

    /// 말풍선 문구 — 이름이 길면 잘라 쓴다. 지도 폭을 넘기면 말풍선이 잘려 보인다
    private func nearbyCaption(_ match: NearbySupplyEngine.Match) -> String {
        "\(shortName(match.poi.name)) · \(Int(match.meters.rounded()))m"
    }

    private func courseCaption(_ match: CourseSupplyEngine.Match) -> String {
        "\(shortName(match.poi.name)) · \(Format.km(match.courseKm))km 지점"
    }

    private func shortName(_ name: String) -> String {
        name.count > 12 ? String(name.prefix(12)) + "…" : name
    }

    private func mapBadge(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.black.opacity(0.5),
                        in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .padding(12)
    }

    // MARK: 버튼 · 안내 · 출처

    private func uploadButtons(compact: Bool) -> some View {
        VStack(spacing: 10) {
            Button {
                showImporter = true
            } label: {
                Label(compact ? "다른 코스 올리기" : "GPX 코스 올리기",
                      systemImage: "square.and.arrow.up")
                    .font(.system(size: 13.5, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)

            if compact {
                Button(action: clearCourse) {
                    Label("현재 위치로 보기", systemImage: "location")
                        .font(.system(size: 13.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
            } else {
                Text("달릴 코스를 GPX로 올리시면 '몇 km 지점'까지 짚어 드려요. 코스 파일과 위치는 기기 밖으로 나가지 않아요.")
                    .font(.system(size: 11.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.horizontal, 4)
            }
        }
    }

    /// 음수대 데이터 공백 안내 — 서울·한강 중심이라 없는 게 아니라 "모르는" 것일 수 있다
    /// (기획서 §6 제약 · §4.13 미노출 가드)
    private var waterGapNote: some View {
        Text("음수대 정보는 아직 서울·한강 공원 중심이에요. 여기 안 보여도 실제로는 있을 수 있으니, 미덥지 않으면 물통을 챙겨 주세요.")
            .font(.system(size: 11.5))
            .lineSpacing(3)
            .foregroundStyle(RR.text3)
            .padding(.horizontal, 4)
    }

    private func noticeCard(_ title: String, message: String, symbol: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 22))
                .foregroundStyle(RR.text3)
            Text(title)
                .font(.system(size: 14.5, weight: .bold))
                .foregroundStyle(RR.text)
            Text(message)
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private var attribution: some View {
        Group {
            if case .loaded(let file) = store.state {
                Text("데이터 기준 \(file.generatedAt) · 소상공인시장진흥공단·행정안전부·서울열린데이터광장 · © OpenStreetMap 기여자(ODbL)")
                    .font(.system(size: 10.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.horizontal, 4)
            }
        }
    }
}

// MARK: - 종류별 표시 매핑 (화면 전용)

private extension CoursePOI.Kind {
    var label: String {
        switch self {
        case .convenience: "편의점"
        case .toilet: "화장실"
        case .water: "음수대"
        }
    }

    var symbol: String {
        switch self {
        case .convenience: "cart.fill"
        case .toilet: "toilet.fill"
        case .water: "drop.fill"
        }
    }

    var color: Color {
        switch self {
        case .convenience: RR.warn
        case .toilet: RR.brand
        case .water: RR.pos
        }
    }

    var softColor: Color {
        switch self {
        case .convenience: RR.warnSoft
        case .toilet: RR.brandSoft
        case .water: RR.posSoft
        }
    }
}

private extension UTType {
    /// GPX 파일 타입 — Info.plist의 UTImportedTypeDeclarations(project.yml)와 짝
    static let gpx = UTType(importedAs: "com.topografix.gpx")
}
