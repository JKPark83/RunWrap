import SwiftUI

/// 대회 — 전국 마라톤 접수 일정 목록 (기획서 §4.14, 계획서 M13-3).
/// 자료는 로드런(roadrun.co.kr)을 매일 배치로 받아 온 Races.json.
/// 접수 상태 판정·정렬·지난 대회 필터는 RaceEngine — 화면은 결과를 그리기만 한다.
struct RaceListScreen: View {
    /// 대회 목록 — 홈 목표 대회 카드와 함께 쓰려고 RootView가 쥔다 (이슈 #172)
    @EnvironmentObject private var store: RaceStore
    /// 목록 필터 — 전체 / 접수중(기획서 §4.14) / 즐겨찾기(이슈 #172). 세션 한정
    private enum Filter { case all, open, favorites }
    @State private var filter = Filter.all
    /// 즐겨찾기한 대회 번호 (`[Int]` JSON) — 상세 화면의 별이 쓰고 여기서는 읽기만 한다 (이슈 #172)
    @AppStorage(RaceKey.favorites) private var favoritesRaw = ""
    /// 키워드 검색 — 대회명·지역·장소·종목을 대상으로 하고 필터와 AND로 겹친다
    @State private var query = ""
    /// 목록 판정 기준 시각 — 자정·포그라운드 복귀 때 갱신해 D-day·접수 상태가 어제에 머물지 않게 한다 (#145)
    @State private var now = Date()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch store.state {
            case .idle, .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                failedNotice
            case .loaded(let file):
                raceList(file)
            }
        }
        .background(RR.bg.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await store.load() }
        // 포그라운드 복귀 — 날짜를 다시 잡고, 원격은 6시간이 지났을 때만 다시 받는다 (#145)
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            now = Date()
            Task { await store.load() }
        }
        // 앱을 켜 둔 채 자정을 넘기면 D-day를 다시 계산한다 (알림은 임의 스레드에서 올 수 있다)
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)
            .receive(on: RunLoop.main)) { _ in
            now = Date()
        }
    }

    // MARK: 목록

    private func raceList(_ file: RaceFile) -> some View {
        let entries = RaceEngine.entries(from: file.races, now: now)
        let openCount = entries.filter(\.isOpen).count
        let keyword = query.trimmingCharacters(in: .whitespaces)
        let favorites = Set(RaceFavorites.decode(favoritesRaw))
        let visible = entries
            .filter { entry in
                switch filter {
                case .all: true
                case .open: entry.isOpen
                case .favorites: favorites.contains(entry.id)
                }
            }
            .filter { keyword.isEmpty || $0.matches(keyword) }
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 7) {
                    Eyebrow(text: "Race calendar")
                    Text("대회")
                        .font(RR.display(33))
                        .foregroundStyle(RR.text)
                    Text(RaceFormat.caption(
                        openCount: openCount, updatedAt: file.generatedAt,
                        refreshFailed: store.lastRefreshFailed,
                        stale: RaceFormat.isStale(generatedAt: file.generatedAt, now: now)))
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text3)
                }
                .padding(.bottom, 2)

                if !entries.isEmpty {
                    searchField
                    filterChips
                }

                if visible.isEmpty {
                    emptyCard(searching: !keyword.isEmpty, filter: filter)
                } else {
                    ForEach(visible) { entry in
                        NavigationLink { RaceDetailScreen(entry: entry) } label: {
                            row(entry, isFavorite: favorites.contains(entry.id))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 26)
        }
        .refreshable { await store.refresh() }
        .scrollDismissesKeyboard(.immediately)
    }

    /// 키워드 검색 필드 — 내비게이션 바를 숨긴 화면이라 .searchable 대신 직접 그린다
    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14))
                .foregroundStyle(RR.text3)
            TextField("대회명·지역·종목 검색", text: $query)
                .font(.system(size: 14))
                .foregroundStyle(RR.text)
                .submitLabel(.search)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(RR.text3)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(RR.surface2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    /// 전체/접수중/즐겨찾기 필터 칩 — StatsScreen 지표 전환 칩과 같은 스타일
    private var filterChips: some View {
        HStack(spacing: 6) {
            filterChip("전체", selected: filter == .all) { filter = .all }
            filterChip("접수중", selected: filter == .open) { filter = .open }
            filterChip("즐겨찾기", selected: filter == .favorites) { filter = .favorites }
            Spacer()
        }
    }

    private func filterChip(_ title: String, selected: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(selected ? RR.onBrand : RR.text2)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? RR.brand : RR.surface2, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func row(_ entry: RaceEngine.Entry, isFavorite: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // "10/10"처럼 월·일이 모두 두 자리면 46pt에 안 들어가 밀렸다 — 최대 폭 기준으로 고정 (#32)
            VStack(spacing: 2) {
                Text(RaceFormat.monthDay.string(from: entry.raceDate))
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text(RaceFormat.weekdayParen.string(from: entry.raceDate))
                    .font(.system(size: 10.5))
                    .foregroundStyle(RR.text3)
            }
            .frame(width: 54)
            .padding(.top, 1)

            RaceThumbnail(urlString: entry.race.imageUrl)

            VStack(alignment: .leading, spacing: 4) {
                Text(entry.race.name)
                    .font(.system(size: 14.5, weight: .semibold))
                    .foregroundStyle(RR.text)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let categories = entry.race.categories {
                    Text(categories.joined(separator: " · "))
                        .font(.system(size: 11.5))
                        .foregroundStyle(RR.text2)
                        .lineLimit(1)
                }
                let placeLine = [entry.race.region, entry.race.place]
                    .compactMap(\.self).joined(separator: " · ")
                if !placeLine.isEmpty {
                    Text(placeLine)
                        .font(.system(size: 11))
                        .foregroundStyle(RR.text3)
                        .lineLimit(1)
                }
                if let period = RaceFormat.registerPeriod(entry.race) {
                    Text(period)
                        .font(.system(size: 11))
                        .monospacedDigit()
                        .foregroundStyle(RR.text3)
                }
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 6) {
                HStack(spacing: 5) {
                    // 즐겨찾기 표시 (이슈 #172) — 토글은 상세 화면의 별에서만 한다
                    if isFavorite {
                        Image(systemName: "star.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(RR.warn)
                            .accessibilityLabel("즐겨찾기")
                    }
                    RegisterBadge(status: entry.status)
                }
                Text(RaceFormat.dDay(entry.dDay))
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text2)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    // MARK: 빈 목록·실패

    /// 카드 소형 썸네일 — 크롤러가 홈페이지에서 뽑은 대표 이미지(imageUrl)를 보여주고,
    /// 없거나 로딩 전·실패면 코드로 그린 기본 그림으로 대신한다 (#32)
    private struct RaceThumbnail: View {
        let urlString: String?

        var body: some View {
            Group {
                if let urlString, let url = URL(string: urlString) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFill()
                        } else {
                            placeholder
                        }
                    }
                } else {
                    placeholder
                }
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }

        private var placeholder: some View {
            ZStack {
                RR.surface2
                Image(systemName: "figure.run")
                    .font(.system(size: 18))
                    .foregroundStyle(RR.text3)
            }
        }
    }

    private func emptyCard(searching: Bool, filter: Filter) -> some View {
        let (title, subtitle): (String, String) = if searching {
            ("맞는 대회를 못 찾았어요", "다른 키워드로 다시 찾아보시겠어요?")
        } else if filter == .open {
            ("지금 접수받는 대회가 없어요", "접수가 열리면 접수중 배지로 알려드릴게요.")
        } else if filter == .favorites {
            ("즐겨찾기한 대회가 없어요", "상세에서 별을 눌러 보세요.")
        } else {
            ("지금 보여드릴 대회가 없어요", "자료가 갱신되면 다시 찾아뵐게요.")
        }
        return VStack(spacing: 8) {
            Image(systemName: searching ? "magnifyingglass" : filter == .favorites ? "star" : "flag.slash")
                .font(.system(size: 22))
                .foregroundStyle(RR.text3)
            Text(title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(RR.text)
            Text(subtitle)
                .font(.system(size: 12))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .rrCard()
    }

    private var failedNotice: some View {
        VStack(spacing: 10) {
            Image(systemName: "wifi.slash")
                .font(.system(size: 24))
                .foregroundStyle(RR.text3)
            Text("대회 소식을 못 가져왔어요")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(RR.text)
            Text("네트워크 연결을 확인하고 다시 시도해 주세요.")
                .font(.system(size: 12.5))
                .foregroundStyle(RR.text2)
            Button("다시 시도") { Task { await store.load() } }
                .buttonStyle(.borderedProminent)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// 접수 상태 소형 배지 — 훈련 상태(RRTone 4단계)가 아니라 모집 상태 표시라
/// IndoorBadge처럼 별도 색 매핑을 쓴다 (Theme.swift 참조). 상태 미상(nil)이면 그리지 않는다.
/// 목록·상세 화면이 같이 쓴다.
struct RegisterBadge: View {
    let status: RaceEngine.RegisterStatus?

    var body: some View {
        if let status {
            Text(label(status))
                .font(.system(size: 10.5, weight: .bold))
                .foregroundStyle(color(status))
                .padding(.horizontal, 8)
                .padding(.vertical, 3.5)
                .background(softColor(status),
                            in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
    }

    private func label(_ status: RaceEngine.RegisterStatus) -> String {
        switch status {
        case .notYet: "접수예정"
        case .open: "접수중"
        case .closed: "접수완료"
        }
    }

    private func color(_ status: RaceEngine.RegisterStatus) -> Color {
        switch status {
        case .notYet: RR.brand
        case .open: RR.pos
        case .closed: RR.text3
        }
    }

    private func softColor(_ status: RaceEngine.RegisterStatus) -> Color {
        switch status {
        case .notYet: RR.brandSoft
        case .open: RR.posSoft
        case .closed: RR.barFill
        }
    }
}

/// 대회 화면 공용 날짜·기간 표기 — 목록·상세가 같이 쓴다.
/// 대회는 전부 국내 개최라 표기도 RaceEngine의 KST 달력을 따른다.
enum RaceFormat {
    static let monthDay = make("M/d")
    static let weekdayParen = make("(E)")
    static let fullDate = make("yyyy년 M월 d일 (E)")
    static let dotDate = make("yyyy. M. d.")

    private static func make(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.timeZone = RaceEngine.calendar.timeZone
        f.dateFormat = format
        return f
    }

    static func dDay(_ days: Int) -> String { days == 0 ? "D-DAY" : "D-\(days)" }

    /// 목록용 "접수 6/1 ~ 8/15" — 한쪽만 알면 그쪽만 표기, 둘 다 모르면 nil (미노출 가드)
    static func registerPeriod(_ race: Race) -> String? {
        let start = RaceEngine.day(race.registerStart).map { monthDay.string(from: $0) }
        let end = RaceEngine.day(race.registerEnd).map { monthDay.string(from: $0) }
        switch (start, end) {
        case (let s?, let e?): return "접수 \(s) ~ \(e)"
        case (let s?, nil): return "접수 \(s) 시작"
        case (nil, let e?): return "접수 ~\(e) 마감"
        case (nil, nil): return nil
        }
    }

    /// 상세용 "2026. 3. 26. ~ 2026. 7. 30."
    static func registerPeriodLong(_ race: Race) -> String? {
        let start = RaceEngine.day(race.registerStart).map { dotDate.string(from: $0) }
        let end = RaceEngine.day(race.registerEnd).map { dotDate.string(from: $0) }
        switch (start, end) {
        case (let s?, let e?): return "\(s) ~ \(e)"
        case (let s?, nil): return "\(s) 시작"
        case (nil, let e?): return "\(e) 마감"
        case (nil, nil): return nil
        }
    }

    /// 목록 헤더 "8월 12일" — generatedAt(ISO8601)을 못 읽으면 nil
    static func updatedLabel(_ iso: String) -> String? {
        guard let date = ISO8601DateFormatter().date(from: iso) else { return nil }
        return make("M월 d일").string(from: date)
    }

    /// 자료 신선도 (#146) — generatedAt이 maxDays일보다 오래됐으면 true.
    /// 배치가 며칠째 실패해도 앱이 조용히 옛 목록을 보여주지 않게 한다.
    /// generatedAt은 races 내용이 바뀐 날만 커밋되므로(race-info.yml) 대회 변동이 없는 며칠은
    /// 정상이다 — 3일이면 헛경고가 잦아 7일로 잡았다. 못 읽으면 false — 모르는 걸 경고하지 않는다.
    static func isStale(generatedAt iso: String, now: Date, maxDays: Int = 7) -> Bool {
        guard let date = ISO8601DateFormatter().date(from: iso) else { return false }
        return now.timeIntervalSince(date) > TimeInterval(maxDays * 86_400)
    }

    /// 목록 헤더 캡션 (#145 #146) — 평소엔 "지금 접수받는 대회 N곳 · 8월 12일 갱신".
    /// 새로고침 실패·자료 오래됨이면 둘째 줄에 존댓말 안내와 자료 날짜를 붙인다.
    static func caption(openCount: Int, updatedAt: String,
                        refreshFailed: Bool, stale: Bool) -> String {
        let count = "지금 접수받는 대회 \(openCount)곳"
        let updated = updatedLabel(updatedAt)
        let notice: String? = switch (refreshFailed, stale) {
        case (true, true): "새로 받지 못해 자료가 조금 오래됐어요"
        case (true, false): "방금 새로 받진 못했어요"
        case (false, true): "자료가 조금 오래됐어요"
        case (false, false): nil
        }
        guard let notice else {
            return updated.map { "\(count) · \($0) 갱신" } ?? count
        }
        return "\(count)\n" + (updated.map { "\(notice) · \($0) 자료" } ?? notice)
    }
}

private extension RaceEngine.Entry {
    var isOpen: Bool {
        if case .open = status { true } else { false }
    }

    /// 키워드가 대회명·지역·장소·종목 중 하나에라도 들어 있으면 매칭 (한글 친화 비교)
    func matches(_ keyword: String) -> Bool {
        var fields = [race.name, race.region, race.place].compactMap(\.self)
        fields.append(contentsOf: race.categories ?? [])
        return fields.contains { $0.localizedStandardContains(keyword) }
    }
}
