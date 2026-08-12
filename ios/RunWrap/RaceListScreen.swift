import SwiftUI

/// 대회 — 전국 마라톤 접수 일정 목록 (기획서 §4.14, 계획서 M13-3).
/// 자료는 로드런(roadrun.co.kr)을 매일 배치로 받아 온 Races.json.
/// 접수 상태 판정·정렬·지난 대회 필터는 RaceEngine — 화면은 결과를 그리기만 한다.
struct RaceListScreen: View {
    @StateObject private var store = RaceStore()
    /// 접수중 대회만 보기 — 세션 한정 필터 (기획서 §4.14)
    @State private var showOpenOnly = false

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
    }

    // MARK: 목록

    private func raceList(_ file: RaceFile) -> some View {
        let entries = RaceEngine.entries(from: file.races, now: Date())
        let openCount = entries.filter(\.isOpen).count
        let visible = showOpenOnly ? entries.filter(\.isOpen) : entries
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 7) {
                    Eyebrow(text: "Race calendar")
                    Text("대회")
                        .font(.system(size: 31, weight: .bold))
                        .foregroundStyle(RR.text)
                    Text(caption(openCount: openCount, updatedAt: file.generatedAt))
                        .font(.system(size: 12))
                        .foregroundStyle(RR.text3)
                }
                .padding(.bottom, 2)

                if !entries.isEmpty {
                    filterChips
                }

                if visible.isEmpty {
                    emptyCard(filtered: showOpenOnly)
                } else {
                    ForEach(visible) { entry in
                        NavigationLink { RaceDetailScreen(entry: entry) } label: {
                            row(entry)
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
    }

    /// 전체/접수중 필터 칩 — StatsScreen 지표 전환 칩과 같은 스타일
    private var filterChips: some View {
        HStack(spacing: 6) {
            filterChip("전체", selected: !showOpenOnly) { showOpenOnly = false }
            filterChip("접수중", selected: showOpenOnly) { showOpenOnly = true }
            Spacer()
        }
    }

    private func filterChip(_ title: String, selected: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(selected ? .white : RR.text2)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(selected ? RR.brand : RR.surface2, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    private func caption(openCount: Int, updatedAt: String) -> String {
        var text = "지금 접수받는 대회 \(openCount)곳"
        if let updated = RaceFormat.updatedLabel(updatedAt) {
            text += " · \(updated) 갱신"
        }
        return text
    }

    private func row(_ entry: RaceEngine.Entry) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 2) {
                Text(RaceFormat.monthDay.string(from: entry.raceDate))
                    .font(.system(size: 15, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text(RaceFormat.weekdayParen.string(from: entry.raceDate))
                    .font(.system(size: 10.5))
                    .foregroundStyle(RR.text3)
            }
            .frame(width: 46)
            .padding(.top, 1)

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
                RegisterBadge(status: entry.status)
                Text(RaceFormat.dDay(entry.dDay))
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text2)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard(radius: 18)
    }

    // MARK: 빈 목록·실패

    private func emptyCard(filtered: Bool) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "flag.slash")
                .font(.system(size: 22))
                .foregroundStyle(RR.text3)
            Text(filtered ? "지금 접수받는 대회가 없어요" : "지금 보여드릴 대회가 없어요")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(RR.text)
            Text(filtered ? "접수가 열리면 접수중 배지로 알려드릴게요." : "자료가 갱신되면 다시 찾아뵐게요.")
                .font(.system(size: 12))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
        .rrCard(radius: 18)
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
}

private extension RaceEngine.Entry {
    var isOpen: Bool {
        if case .open = status { true } else { false }
    }
}
