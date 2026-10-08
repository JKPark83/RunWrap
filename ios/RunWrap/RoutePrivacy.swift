import Foundation
import CoreLocation

/// 공유 카드 경로 프라이버시 — 러닝은 대개 집에서 출발·도착하므로 SNS에 올리는 스토리 카드에서
/// 경로 양끝을 잘라 거주지가 드러나지 않게 한다 (이슈 #84, Strava 프라이버시 존 방식).
/// 분 단위 출발 시각은 생활 패턴(매일 몇 시에 집을 나서는지)을 드러낼 수 있어 처음에는 카드 날짜 줄을
/// 아침·낮·저녁·밤 시간대로만 흐렸다 (이슈 #191). 지금은 시작~종료 시각을 보여 주는 쪽이 기본이고,
/// 공유 시트의 "시각 표시" 토글로 끄면 예전처럼 시간대로 흐린다 (이슈 #221). 가림 반경은 세 단계에서 고른다 (이슈 #191).
/// 순수 로직이라 UI를 모른다 — 잘라낸 좌표만 돌려주고 그리기는 `RouteSnapshot`이 한다.
enum RoutePrivacy {
    /// 양끝을 가릴 반경 — 아파트 단지·좁은 동네는 300m로 부족할 수 있어 세 단계를 둔다 (이슈 #191)
    enum Radius: Int, CaseIterable {
        case m300 = 300
        case m500 = 500
        case m1000 = 1_000

        var label: String {
            switch self {
            case .m300: "300m"
            case .m500: "500m"
            case .m1000: "1km"
            }
        }

        var meters: Double { Double(rawValue) }
    }

    static let defaultRadius: Radius = .m300
    /// 가림 반경 UserDefaults 키 — rawValue(Int)로 저장한다
    static let radiusKey = "share.trimRadius"

    /// 저장된 값이 없거나(0) 선택지에 없는 값이면 기본 반경으로 되돌린다
    static func radius(rawValue: Int) -> Radius {
        Radius(rawValue: rawValue) ?? defaultRadius
    }

    /// 시작점에서 누적 거리 `meters`까지, 끝점에서 거슬러 `meters`까지의 좌표를 잘라낸다.
    /// 잘라낸 뒤 2개 미만(짧은 코스)이면 선을 그을 수 없으므로 빈 배열 — 경로 없이 카드를 만든다.
    /// 거리는 직선 반경이 아니라 경로를 따라 쌓은 누적 거리다 (`CLLocation.distance(from:)`).
    static func trimmed(_ route: [CLLocationCoordinate2D],
                        meters: Double = 300) -> [CLLocationCoordinate2D] {
        trimmed(route, meters: meters) { $0 }
    }

    /// 좌표 외 값(시각·고도)을 지닌 점도 같은 규칙으로 자른다 — GPX 내보내기의 "시작·끝 가리기" (이슈 #222)
    static func trimmed<Point>(_ route: [Point], meters: Double,
                               coordinate: (Point) -> CLLocationCoordinate2D) -> [Point] {
        guard route.count >= 2 else { return [] }
        var cumulative: [Double] = [0]
        cumulative.reserveCapacity(route.count)
        for i in 1..<route.count {
            let a = coordinate(route[i - 1]), b = coordinate(route[i])
            let previous = CLLocation(latitude: a.latitude, longitude: a.longitude)
            let current = CLLocation(latitude: b.latitude, longitude: b.longitude)
            cumulative.append(cumulative[i - 1] + current.distance(from: previous))
        }
        let total = cumulative[route.count - 1]
        let kept = route.indices
            .filter { cumulative[$0] >= meters && total - cumulative[$0] >= meters }
            .map { route[$0] }
        return kept.count >= 2 ? kept : []
    }

    /// 공유 카드 날짜 줄용 시간대 — 분 단위 출발 시각 대신 쓴다.
    /// 경계(가정): 아침 5~11시, 낮 11~17시, 저녁 17~21시, 밤 21~5시 (이슈 #191)
    static func timeOfDay(_ date: Date, calendar: Calendar = .current) -> String {
        switch calendar.component(.hour, from: date) {
        case 5..<11: "아침"
        case 11..<17: "낮"
        case 17..<21: "저녁"
        default: "밤"
        }
    }

    /// 카드 날짜 줄 — 미니멀·사진 카드가 같이 쓴다 (ko_KR 요일).
    /// `end`를 주면 "2026.09.30 (수) 06:12~07:05", 없으면 "2026.09.30 (수) 아침" (이슈 #221).
    /// 자정을 넘긴 러닝도 날짜는 시작일 하나만 적는다 — "23:40~00:25"
    static func cardDateLine(_ date: Date, end: Date? = nil, calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        // 기기 달력이 불기·일본력이어도 서기 연도로 찍도록 그레고리력을 쓰고 시간대만 따른다
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy.MM.dd (E)"
        let day = formatter.string(from: date)
        guard let end else { return day + " " + timeOfDay(date, calendar: calendar) }
        formatter.dateFormat = "HH:mm"
        return day + " " + formatter.string(from: date) + "~" + formatter.string(from: end)
    }
}
