import SwiftUI
import MapKit

/// 대회 상세 — 종목·일시·장소·접수기간 + 참가하기 버튼 (기획서 §4.14, 계획서 M13-3).
/// 참가비·기념품은 로드런에 구조화 필드가 없다 — 기타소개(note)에 담긴 경우만
/// '대회 소개'로 보여주고, 없으면 아예 표시하지 않는다 (미노출 가드).
struct RaceDetailScreen: View {
    let entry: RaceEngine.Entry

    // 즐겨찾기·캘린더·목표 대회 (이슈 #172)
    /// 대회 목록 스토어 — 즐겨찾기가 바뀌면 접수 알림을 다시 건다
    @EnvironmentObject private var raceStore: RaceStore
    @AppStorage(RaceKey.favorites) private var favoritesRaw = ""
    @AppStorage(RaceKey.targetID) private var targetID = 0
    /// 목표 대회 지정 때 대회일을 넣는다 — 설정 '대회 날짜'와 같은 저장 형식(timeIntervalSince1970)
    @AppStorage(ProfileKey.raceDate) private var raceDateRaw = 0.0
    /// 이번 세션에서 캘린더에 넣었는지 — 같은 이벤트를 두 번 넣지 않게 버튼을 잠근다
    @State private var calendarAdded = false
    @State private var showsCalendarDenied = false
    @State private var showsCalendarFailed = false
    @Environment(\.openURL) private var openURL

    private var race: Race { entry.race }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                if let categories = race.categories, !categories.isEmpty {
                    categoryChips(categories)
                }
                if let imageURL = race.imageUrl.flatMap(URL.init(string:)) {
                    posterCard(imageURL)
                }
                infoCard
                if let lat = race.lat, let lon = race.lon {
                    mapCard(lat: lat, lon: lon)
                }
                if let note = race.note {
                    noteCard(note)
                }
                actionButtons
                if let homepage = race.homepage.flatMap(URL.init(string:)) {
                    joinButton(homepage)
                }
                Text("자료: 로드런(roadrun.co.kr). 대회 내용은 주최 측 사정으로 바뀔 수 있어요 — 참가 전에 대회 홈페이지에서 꼭 확인해 주세요.")
                    .font(.system(size: 11.5))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text3)
                    .padding(.horizontal, 4)
                    .padding(.top, 2)
            }
            .padding(.horizontal, 18)
            .padding(.top, 12)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .navigationTitle("대회 상세")
        .navigationBarTitleDisplayMode(.inline)
        .alert("캘린더 접근이 꺼져 있어요", isPresented: $showsCalendarDenied) {
            Button("설정 열기") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            Button("닫기", role: .cancel) {}
        } message: {
            Text("설정 > 개인정보 보호 > 캘린더에서 허용해 주세요")
        }
        .alert("캘린더에 추가하지 못했어요", isPresented: $showsCalendarFailed) {
            Button("확인", role: .cancel) {}
        } message: {
            Text("잠시 후 다시 시도해 주세요.")
        }
    }

    // MARK: 헤더

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Eyebrow(text: RaceFormat.dDay(entry.dDay))
                RegisterBadge(status: entry.status)
                Spacer(minLength: 8)
                favoriteButton
            }
            Text(race.name)
                .font(RR.display(27))
                .lineSpacing(5)
                .foregroundStyle(RR.text)
        }
        .padding(.bottom, 4)
    }

    /// 종목 칩 — 종목이 많은 대회(풀·하프·10km·5km…)는 가로 스크롤로 흘린다
    private func categoryChips(_ categories: [String]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(categories, id: \.self) { category in
                    Text(category)
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(RR.brand)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(RR.brandSoft,
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }
        }
    }

    /// 대회 포스터 — 크롤러가 홈페이지에서 뽑은 대표 이미지(imageUrl)를 원본 비율로 보여준다.
    /// 목록의 소형 썸네일과 같은 원본이고, 로딩 실패면 자리를 차지하지 않는다 (#32)
    private func posterCard(_ url: URL) -> some View {
        AsyncImage(url: url) { phase in
            switch phase {
            case .success(let image):
                image.resizable().scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .strokeBorder(RR.line))
            case .empty:
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .frame(height: 170)
            case .failure:
                EmptyView()
            @unknown default:
                EmptyView()
            }
        }
    }

    // MARK: 정보 카드

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 13) {
            infoRow("calendar", "일시", dateLine)
            if let place = race.place {
                infoRow("mappin.and.ellipse", "장소",
                        [race.region, place].compactMap(\.self).joined(separator: " · "))
            }
            if let period = RaceFormat.registerPeriodLong(race) {
                infoRow("square.and.pencil", "접수기간", period)
            }
            if let host = race.host {
                infoRow("person.2", "주최", host)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private var dateLine: String {
        var text = RaceFormat.fullDate.string(from: entry.raceDate)
        if let startTime = race.startTime {
            text += " \(startTime) 출발"
        }
        return text
    }

    private func infoRow(_ symbol: String, _ label: String, _ value: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 13))
                .foregroundStyle(RR.text3)
                .frame(width: 18)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.system(size: 10.5))
                    .foregroundStyle(RR.text3)
                Text(value)
                    .font(.system(size: 13.5, weight: .medium))
                    .lineSpacing(3)
                    .foregroundStyle(RR.text)
            }
        }
    }

    /// 대회장 지도 — 로드런 상세의 좌표를 그대로 쓴다. 보기 전용(스크롤 방해 금지)
    private func mapCard(lat: Double, lon: Double) -> some View {
        let coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        return Map(initialPosition: .region(MKCoordinateRegion(
            center: coordinate, latitudinalMeters: 1_800, longitudinalMeters: 1_800)),
                   interactionModes: []) {
            Marker(race.place ?? race.name, coordinate: coordinate)
                .tint(RR.brand)
        }
        .frame(height: 170)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(RR.line))
    }

    /// 기타소개 원문 — 참가비·기념품 안내가 실려 오는 자리라 그대로 보여준다
    private func noteCard(_ note: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text("대회 소개")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(RR.text)
            Text(note)
                .font(.system(size: 13))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RR.surface2, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(RR.line))
    }

    // MARK: 즐겨찾기·캘린더·목표 대회 (이슈 #172)

    private var isFavorite: Bool { RaceFavorites.decode(favoritesRaw).contains(race.id) }
    private var isTarget: Bool { targetID == race.id }

    /// 헤더 우측 별 — 즐겨찾기는 목록 필터와 접수 알림의 대상이 된다
    private var favoriteButton: some View {
        Button {
            favoritesRaw = RaceFavorites.encode(RaceFavorites.toggled(RaceFavorites.decode(favoritesRaw), race.id))
            Task { await raceStore.rescheduleRaceAlarms() }
        } label: {
            Image(systemName: isFavorite ? "star.fill" : "star")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(isFavorite ? RR.warn : RR.text3)
                .frame(width: 36, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("즐겨찾기")
        .accessibilityAddTraits(isFavorite ? .isSelected : [])
    }

    /// 캘린더 추가 + 목표 대회 지정 — 참가하기 버튼 위 한 줄
    private var actionButtons: some View {
        HStack(spacing: 8) {
            Button {
                Task { await addToCalendar() }
            } label: {
                Label(calendarAdded ? "캘린더에 추가됐어요" : "캘린더에 추가",
                      systemImage: calendarAdded ? "checkmark" : "calendar.badge.plus")
                    .actionLabelStyle()
            }
            .buttonStyle(.bordered)
            .disabled(calendarAdded)

            Button(action: toggleTarget) {
                Label(isTarget ? "목표 대회 해제" : "목표 대회로 지정",
                      systemImage: isTarget ? "flag.slash" : "flag")
                    .actionLabelStyle()
            }
            .buttonStyle(.bordered)
        }
    }

    private func addToCalendar() async {
        do {
            try await RaceCalendar.add(entry)
            calendarAdded = true
        } catch RaceCalendarError.denied {
            showsCalendarDenied = true
        } catch {
            showsCalendarFailed = true
        }
    }

    /// 지정하면 대회일을 설정의 '대회 날짜'로 넣어 대회 목표(훈련 가이드 D-day)를 켠다 —
    /// 종목·목표 기록은 사용자가 설정에서 그대로 관리한다. 해제는 지정만 지우고 대회 날짜는 남긴다
    private func toggleTarget() {
        if isTarget {
            targetID = 0
            return
        }
        targetID = race.id
        raceDateRaw = entry.raceDate.timeIntervalSince1970
        // 대회 날짜는 iCloud 진행도 스냅샷에 담긴다 — 설정 화면과 같이 병합 기준 시각을 갱신한다 (이슈 #130)
        ProgressSnapshot.markLocalChanged(defaults: .standard, now: Date())
    }

    private func joinButton(_ homepage: URL) -> some View {
        Link(destination: homepage) {
            Label("참가하기", systemImage: "arrow.up.right")
                .font(.system(size: 14.5, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
        }
        .buttonStyle(.borderedProminent)
    }
}

private extension View {
    /// 상세 하단 보조 버튼(캘린더·목표 대회)의 라벨 모양 — 반폭 두 개가 한 줄에 들어가게 줄여 맞춘다
    func actionLabelStyle() -> some View {
        font(.system(size: 13.5, weight: .semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
    }
}
