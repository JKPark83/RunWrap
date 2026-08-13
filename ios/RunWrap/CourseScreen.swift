import MapKit
import SwiftUI
import UniformTypeIdentifiers

/// '코스' 탭 — GPX 코스를 올리면 급수·화장실·편의점을 "몇 km 지점"으로 짚어 준다
/// (기획서 §4.13, 계획서 M12-3). 코스 파일은 기기 밖으로 나가지 않는다 —
/// 번들 POI 데이터와 온디바이스 매칭만 한다.
struct CourseScreen: View {
    @StateObject private var store = CoursePOIStore()
    @State private var course: [GeoPoint] = []
    @State private var courseName = ""
    @State private var result: CourseSupplyEngine.Result?
    @State private var notice: String?
    @State private var showImporter = false
    @State private var showGPXGuide = false
    /// 보급 종류 필터 — 켜진 종류만 지도·리스트에 남긴다.
    /// 기본값은 셋 다 켬(= 전체 표시)이라 필터를 모르는 사용자도 종전과 같은 화면을 본다.
    /// 마지막 하나는 끌 수 없다 — 전부 끄면 빈 화면이 되고, "전체 보기"는 셋 다 켠 상태다
    @State private var kindFilter: Set<CoursePOI.Kind> = [.water, .toilet, .convenience]
    /// 마지막 코스 파일명 — 파일 자체는 Application Support에 캐시해 재진입 시 유지
    @AppStorage("lastCourseName") private var lastCourseName = ""
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 7) {
                    Eyebrow(text: "보급 가이드")
                    Text("코스, 미리 짚어 드려요")
                        .font(RR.display(27))
                        .foregroundStyle(RR.text)
                }
                .padding(.top, 18)

                if case .failed = store.state {
                    noticeCard("보급 데이터를 불러오지 못했어요",
                               message: "앱을 껐다 다시 열어 주세요. 계속 그러면 재설치가 필요할 수 있어요.",
                               symbol: "exclamationmark.triangle")
                } else if let result {
                    mapCard(result)
                    supplyListCard(result)
                    if !result.matches.contains(where: { $0.poi.kind == .water }) {
                        waterGapNote
                    }
                    uploadButtons(compact: true)
                    attribution
                } else if let notice {
                    noticeCard("코스를 읽지 못했어요", message: notice, symbol: "map")
                    uploadButtons(compact: false)
                } else {
                    introCard
                    uploadButtons(compact: false)
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
        .sheet(isPresented: $showGPXGuide) { gpxGuideSheet }
        .task {
            await store.load()
            restoreLastCourse()
            analyze()
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
        if result == nil {
            notice = "코스가 500m보다 짧아서 분석을 접었어요. 이 정도면 보급 없이도 완주하실 거라 믿어요."
        }
    }

    private func restoreLastCourse() {
        guard course.isEmpty, let data = try? Data(contentsOf: Self.lastCourseURL) else { return }
        let points = GPXParser.parse(data)
        guard !points.isEmpty else { return }
        course = points
        courseName = lastCourseName
    }

    private static var lastCourseURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("LastCourse.gpx")
    }

    // MARK: 내장 추천 코스

    /// 서울 인기 코스 6종 — 제3자 GPX가 아니라 OSM 보행로(ODbL) 기반으로 자체 제작해 번들.
    /// 거리 라벨은 제작 시점 실측값이라 GPX와 함께 갱신한다
    private struct BundledCourse {
        let file: String   // 번들 리소스 이름 (.gpx)
        let name: String   // 표시 이름 — 지도 라벨에도 쓰인다
        let km: String     // 메뉴 안내용 거리
    }

    private static let bundledCourses: [BundledCourse] = [
        .init(file: "YeouidoLoop", name: "여의도 한 바퀴", km: "9.7"),
        .init(file: "BanpoJamsu", name: "반포 잠수교 왕복", km: "5.1"),
        .init(file: "SeokchonLake", name: "석촌호수 한 바퀴", km: "2.6"),
        .init(file: "OlympicPark", name: "올림픽공원 한 바퀴", km: "4.4"),
        .init(file: "NamsanNorth", name: "남산 북측순환로 왕복", km: "5.2"),
        .init(file: "Cheonggyecheon", name: "청계천 종주", km: "5.9"),
    ]

    private func loadBundled(_ bundled: BundledCourse) {
        guard let url = Bundle.main.url(forResource: bundled.file, withExtension: "gpx"),
              let data = try? Data(contentsOf: url) else { return }
        apply(data: data, name: bundled.name)
    }

    // MARK: 지도

    private func mapCard(_ result: CourseSupplyEngine.Result) -> some View {
        let coords = course.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        return ZStack(alignment: .bottomLeading) {
            Map(initialPosition: .region(RouteSnapshot.region(for: coords)),
                interactionModes: [.pan, .zoom]) {
                MapPolyline(coordinates: coords)
                    .stroke(RR.brand, style: StrokeStyle(lineWidth: 4,
                                                         lineCap: .round, lineJoin: .round))
                ForEach(Array(visibleMatches(result).enumerated()), id: \.offset) { _, match in
                    Annotation("", coordinate: CLLocationCoordinate2D(latitude: match.poi.lat,
                                                                     longitude: match.poi.lon)) {
                        ZStack {
                            Circle().fill(match.poi.kind.color)
                            Image(systemName: match.poi.kind.symbol)
                                .font(.system(size: 7, weight: .bold))
                                .foregroundStyle(.white)
                        }
                        .frame(width: 16, height: 16)
                        .overlay(Circle().strokeBorder(.white, lineWidth: 1.5))
                    }
                }
            }
            .frame(height: 300)
            .id(courseName + String(course.count))  // 새 코스 업로드 시 카메라 리셋

            Text("\(courseName) · \(Format.km(result.totalKm))km")
                .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.black.opacity(0.5),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .padding(12)
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(RR.line))
    }

    // MARK: 보급 리스트

    private func supplyListCard(_ result: CourseSupplyEngine.Result) -> some View {
        let visible = visibleMatches(result)
        return VStack(alignment: .leading, spacing: 14) {
            Text("보급 지점")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)

            kindFilterBar(result)

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
                        supplyRow(match)
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

    /// 급수·화장실·편의점 필터 버튼 — 각각 아이콘 + 이름 + 개수.
    /// 코스에 없는 종류는 버튼도 흐리게 두되 누를 수는 있게 한다 (없다는 사실 자체가 정보다)
    private func kindFilterBar(_ result: CourseSupplyEngine.Result) -> some View {
        HStack(spacing: 8) {
            ForEach([CoursePOI.Kind.water, .toilet, .convenience], id: \.self) { kind in
                let count = result.matches.filter { $0.poi.kind == kind }.count
                let isOn = kindFilter.contains(kind)
                Button {
                    toggleKind(kind)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: kind.symbol)
                            .font(.system(size: 11, weight: .semibold))
                        Text(kind.label)
                            .font(.system(size: 12, weight: .semibold))
                        Text("\(count)")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .opacity(0.75)
                    }
                    .foregroundStyle(isOn ? .white : kind.color.opacity(count > 0 ? 1 : 0.45))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(isOn ? kind.color : kind.softColor,
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(kind.label) \(count)개")
                .accessibilityValue(isOn ? "표시 중" : "숨김")
            }
        }
    }

    /// 종류를 켜고 끈다. 마지막 하나는 끄지 않는다 —
    /// 전부 꺼진 지도는 정보가 없고, 사용자가 원한 건 "고르기"이지 "비우기"가 아니다
    private func toggleKind(_ kind: CoursePOI.Kind) {
        if kindFilter.contains(kind) {
            guard kindFilter.count > 1 else { return }
            kindFilter.remove(kind)
        } else {
            kindFilter.insert(kind)
        }
    }

    private func supplyRow(_ match: CourseSupplyEngine.Match) -> some View {
        HStack(spacing: 12) {
            Text("\(Format.km(match.courseKm))km")
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .frame(width: 56, alignment: .leading)

            ZStack {
                Circle().fill(match.poi.kind.softColor)
                Image(systemName: match.poi.kind.symbol)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(match.poi.kind.color)
            }
            .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(match.poi.name)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .lineLimit(1)
                Text("\(match.poi.kind.label) · 코스에서 \(Int(match.detourMeters.rounded()))m")
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 9)
    }

    // MARK: 안내 · 버튼 · 출처

    private var introCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: "map")
                .font(.system(size: 24))
                .foregroundStyle(RR.brand)
            Text("아직 받은 코스가 없어요")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
            Text("달릴 코스를 GPX 파일로 올려 주시면, 몇 km 지점에서 물을 마시고 화장실을 들르고 보급을 살 수 있는지 미리 짚어 드릴게요. 서울 인기 코스는 아래 '추천 코스'에서 바로 고르셔도 됩니다. 코스 파일은 기기 밖으로 나가지 않아요.")
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func uploadButtons(compact: Bool) -> some View {
        VStack(spacing: 10) {
            Button {
                showGPXGuide = true
            } label: {
                Label("카카오맵으로 GPX 만들기",
                      systemImage: "point.topleft.down.curvedto.point.bottomright.up")
                    .font(.system(size: 13.5, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.bordered)

            HStack(spacing: 10) {
                Button {
                    showImporter = true
                } label: {
                    Label(compact ? "다른 코스 올리기" : "GPX 파일 올리기",
                          systemImage: "square.and.arrow.up")
                        .font(.system(size: 13.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)

                Menu {
                    ForEach(Self.bundledCourses, id: \.file) { bundled in
                        Button("\(bundled.name) · \(bundled.km)km") { loadBundled(bundled) }
                    }
                } label: {
                    Text("추천 코스")
                        .font(.system(size: 13.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    /// 카카오맵 GPX 안내 시트 — 카카오맵엔 GPX 내보내기가 없어 무료 변환 웹 Map2GPX를
    /// 한 번 거친다(2026-08 기준, map2gpx.com 가이드). 전 과정이 폰에서 끝나는 게 장점.
    private var gpxGuideSheet: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 7) {
                Eyebrow(text: "GPX 만들기")
                Text("카카오맵으로 코스를 그려 오세요")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(RR.text)
            }
            .padding(.top, 26)

            VStack(alignment: .leading, spacing: 14) {
                guideStep(1, "카카오맵 앱에서 도보 길찾기로 출발지 → 경유지 → 도착지를 찍어 코스를 만듭니다.")
                guideStep(2, "공유 메뉴의 'URL 복사'로 경로 링크(kko.to)를 복사합니다.")
                guideStep(3, "Map2GPX에 링크를 붙여넣어 GPX를 내려받은 뒤, 여기서 'GPX 파일 올리기'로 올려 주세요.")
            }

            Text("카카오맵엔 GPX 내보내기가 없어서 무료 변환 사이트 Map2GPX를 한 번 거칩니다. 폰에서 다 끝나요.")
                .font(.system(size: 11.5))
                .lineSpacing(3)
                .foregroundStyle(RR.text3)

            HStack(spacing: 10) {
                Button(action: openKakaoMap) {
                    Label("카카오맵 열기", systemImage: "map")
                        .font(.system(size: 13.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.bordered)

                Link(destination: URL(string: "https://map2gpx.com")!) {
                    Label("Map2GPX 열기", systemImage: "arrow.up.right.square")
                        .font(.system(size: 13.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RR.bg.ignoresSafeArea())
        .presentationDetents([.medium])
    }

    /// 카카오맵 앱을 열고, 없으면 앱스토어로 보낸다 — 커스텀 스킴은 실패해도 조용해서 폴백 필수
    private func openKakaoMap() {
        openURL(URL(string: "kakaomap://open")!) { accepted in
            if !accepted {
                openURL(URL(string: "https://apps.apple.com/kr/app/id304608425")!)
            }
        }
    }

    private func guideStep(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.brand)
                .frame(width: 22, height: 22)
                .background(RR.brandSoft, in: Circle())
            Text(text)
                .font(.system(size: 13))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
    }

    /// 음수대 데이터 공백 안내 — 서울·한강 중심이라 없는 게 아니라 "모르는" 것일 수 있다
    /// (기획서 §6 제약 · §4.13 미노출 가드)
    private var waterGapNote: some View {
        Text("음수대 정보는 아직 서울·한강 공원 중심이에요. 이 코스에 안 보여도 실제로는 있을 수 있으니, 미덥지 않으면 물통을 챙겨 주세요.")
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
