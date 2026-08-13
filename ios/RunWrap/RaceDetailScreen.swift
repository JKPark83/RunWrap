import SwiftUI
import MapKit

/// 대회 상세 — 종목·일시·장소·접수기간 + 참가하기 버튼 (기획서 §4.14, 계획서 M13-3).
/// 참가비·기념품은 로드런에 구조화 필드가 없다 — 기타소개(note)에 담긴 경우만
/// '대회 소개'로 보여주고, 없으면 아예 표시하지 않는다 (미노출 가드).
struct RaceDetailScreen: View {
    let entry: RaceEngine.Entry

    private var race: Race { entry.race }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                if let categories = race.categories, !categories.isEmpty {
                    categoryChips(categories)
                }
                infoCard
                if let lat = race.lat, let lon = race.lon {
                    mapCard(lat: lat, lon: lon)
                }
                if let note = race.note {
                    noteCard(note)
                }
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
    }

    // MARK: 헤더

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Eyebrow(text: RaceFormat.dDay(entry.dDay))
                RegisterBadge(status: entry.status)
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
