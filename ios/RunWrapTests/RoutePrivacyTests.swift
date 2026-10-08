import CoreLocation
import Testing
@testable import RunWrap

/// 공유 카드 경로 트림 — 시작·끝 300m를 누적 거리로 잘라내는지 검증한다 (이슈 #84).
struct RoutePrivacyTests {
    /// 위도 1°의 대략적 길이(m) — 경도 고정 남북 직선이라 위도 차만으로 거리가 정해진다
    private let metersPerDegree = 111_195.0

    /// 경도 고정, 북쪽으로 `step`m 간격 직선 경로 — 0, step, 2·step, …, length(m)
    /// 경계(300m)에 좌표가 정확히 걸리지 않도록 step을 고른다 (CLLocation 거리 오차 ±0.5% 대비)
    private func straight(length: Double, step: Double) -> [CLLocationCoordinate2D] {
        let count = Int((length / step).rounded())
        return (0...count).map { i in
            CLLocationCoordinate2D(latitude: 37.5 + Double(i) * step / metersPerDegree,
                                   longitude: 127.0)
        }
    }

    private func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    @Test("직선 1km 경로 — 양끝 300m가 잘리고 320~680m 구간 10개 좌표만 남는다")
    func trimsBothEndsOfOneKilometer() throws {
        // 40m 간격 26개(0~1000m) → 앞에서 300m 이상·뒤에서 300m 이상인 320, 360, …, 680m = 10개
        let route = straight(length: 1_000, step: 40)
        let trimmed = RoutePrivacy.trimmed(route)

        #expect(trimmed.count == 10)
        let first = try #require(trimmed.first)
        let last = try #require(trimmed.last)
        #expect(first.latitude == route[8].latitude)   // 8 × 40 = 320m
        #expect(last.latitude == route[17].latitude)   // 17 × 40 = 680m
        #expect(distance(route[0], first) >= 300)
        #expect(distance(last, route[route.count - 1]) >= 300)
    }

    @Test("500m 코스 — 양끝 300m를 자르면 남는 좌표가 없어 빈 배열")
    func shortRouteBecomesEmpty() {
        // 50m 간격 0~500m → 앞 300m 이상이면서 뒤 300m 이상인 지점이 없다
        #expect(RoutePrivacy.trimmed(straight(length: 500, step: 50)).isEmpty)
    }

    @Test("잘라낸 뒤 1개만 남으면 선을 그을 수 없어 빈 배열")
    func singleRemainingPointBecomesEmpty() {
        // 40m 간격 0~640m → 300m 이상·340m 이하인 320m 지점 하나뿐
        #expect(RoutePrivacy.trimmed(straight(length: 640, step: 40)).isEmpty)
    }

    @Test("좌표가 2개 미만이면 빈 배열")
    func tooFewPointsBecomesEmpty() {
        #expect(RoutePrivacy.trimmed([]).isEmpty)
        #expect(RoutePrivacy.trimmed([CLLocationCoordinate2D(latitude: 37.5, longitude: 127)]).isEmpty)
    }

    @Test("트림은 원본 경로를 바꾸지 않는다")
    func originalRouteIsUntouched() {
        let route = straight(length: 1_000, step: 40)
        let snapshot = route.map(\.latitude)
        _ = RoutePrivacy.trimmed(route)
        #expect(route.count == 26)
        #expect(route.map(\.latitude) == snapshot)
    }

    // MARK: - 가림 반경 선택 (이슈 #191)

    @Test("반경 500m — 직선 1.5km 경로의 양끝 500m가 잘리고 첫·끝 좌표가 500~1000m 구간 안")
    func trimsFiveHundredMeters() throws {
        // 30m 간격 0~1500m(51개) → 앞 500m 이상·뒤 500m 이상인 510, 540, …, 990m = 17개
        // (경계 500·1000m에 좌표가 걸리지 않게 30m 간격 — CLLocation 거리 오차 대비)
        let route = straight(length: 1_500, step: 30)
        let trimmed = RoutePrivacy.trimmed(route, meters: RoutePrivacy.Radius.m500.meters)

        #expect(trimmed.count == 17)
        let first = try #require(trimmed.first)
        let last = try #require(trimmed.last)
        let startToFirst = distance(route[0], first)
        let startToLast = distance(route[0], last)
        #expect(startToFirst >= 500 && startToFirst <= 1_000)
        #expect(startToLast >= 500 && startToLast <= 1_000)
        #expect(distance(last, route[route.count - 1]) >= 500)
    }

    @Test("반경 1km — 1.5km 경로는 양끝을 자르면 남는 게 없어 빈 배열")
    func oneKilometerRadiusEmptiesShortRoute() {
        // 앞 1000m 이상이면서 뒤 1000m 이상인 지점이 없다 (총 1.5km)
        let route = straight(length: 1_500, step: 30)
        #expect(RoutePrivacy.trimmed(route, meters: RoutePrivacy.Radius.m1000.meters).isEmpty)
    }

    @Test("저장값 복원 — 선택지에 있으면 그 반경, 없거나 잘못된 값이면 기본 300m")
    func radiusFromRawValue() {
        #expect(RoutePrivacy.radius(rawValue: 500) == .m500)
        #expect(RoutePrivacy.radius(rawValue: 1_000) == .m1000)
        #expect(RoutePrivacy.radius(rawValue: 0) == .m300)     // 저장값 없음
        #expect(RoutePrivacy.radius(rawValue: 700) == .m300)   // 선택지에 없는 값
        #expect(RoutePrivacy.defaultRadius == .m300)
    }

    @Test("반경 라벨 — 300m·500m·1km")
    func radiusLabels() {
        #expect(RoutePrivacy.Radius.allCases.map(\.label) == ["300m", "500m", "1km"])
        #expect(RoutePrivacy.Radius.m1000.meters == 1_000)
    }

    // MARK: - 시작 시각 흐리기 (이슈 #191)

    /// KST 고정 달력 — 실행 기기 시간대와 무관하게 결정론적으로
    private var kst: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar
    }

    private func kstDate(_ iso: String) throws -> Date {
        try #require(ISO8601DateFormatter().date(from: iso))
    }

    @Test("시간대 경계 — 아침 5~11시, 낮 11~17시, 저녁 17~21시, 밤 21~5시")
    func timeOfDayBoundaries() throws {
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T05:00:00+09:00"), calendar: kst) == "아침")
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T10:59:00+09:00"), calendar: kst) == "아침")
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T11:00:00+09:00"), calendar: kst) == "낮")
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T17:00:00+09:00"), calendar: kst) == "저녁")
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T21:00:00+09:00"), calendar: kst) == "밤")
        #expect(RoutePrivacy.timeOfDay(try kstDate("2026-09-30T04:59:00+09:00"), calendar: kst) == "밤")
    }

    @Test("카드 날짜 줄 — 분 단위 시각 대신 날짜·요일·시간대")
    func cardDateLineHidesMinutes() throws {
        // 2026-09-30은 수요일, 06:10 → 아침
        let date = try kstDate("2026-09-30T06:10:00+09:00")
        #expect(RoutePrivacy.cardDateLine(date, calendar: kst) == "2026.09.30 (수) 아침")
    }

    @Test("카드 날짜 줄 — 시각 표시를 켜면 시작~종료 시각")
    func cardDateLineShowsTime() throws {
        // 06:12 출발 · 53분 뒤 07:05 종료
        let start = try kstDate("2026-09-30T06:12:00+09:00")
        let end = try kstDate("2026-09-30T07:05:00+09:00")
        #expect(RoutePrivacy.cardDateLine(start, end: end, calendar: kst) == "2026.09.30 (수) 06:12~07:05")
        // 끄면(end 없음) 예전처럼 시간대만
        #expect(RoutePrivacy.cardDateLine(start, calendar: kst) == "2026.09.30 (수) 아침")
    }

    @Test("카드 날짜 줄 — 자정을 넘긴 러닝은 시작일만 적고 시각은 24시간제로")
    func cardDateLineAcrossMidnight() throws {
        let start = try kstDate("2026-09-30T23:40:00+09:00")
        let end = try kstDate("2026-10-01T00:25:00+09:00")
        #expect(RoutePrivacy.cardDateLine(start, end: end, calendar: kst) == "2026.09.30 (수) 23:40~00:25")
    }
}
