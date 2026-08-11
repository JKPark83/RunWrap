import SwiftUI
import CoreLocation

/// '오늘' 탭 — 현재 위치의 날씨 수치와 조건에 맞는 러닝 복장 추천 (기획서 §4.10, 계획서 M6).
/// 복장은 SF 심볼 + 라벨 칩으로 표현한다 — 전용 일러스트는 후속 제작 항목.
struct TodayScreen: View {
    @StateObject private var location = LocationProvider()
    @State private var weather: CurrentWeather?
    @State private var weatherFailed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 7) {
                    Eyebrow(text: todayEyebrow)
                    Text("오늘, 달리기 좋을까")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(RR.text)
                }
                .padding(.top, 18)

                switch location.state {
                case .denied:
                    noticeCard("위치 권한이 꺼져 있어요",
                               message: "설정 > RunWrap에서 위치 접근을 허용하면 현재 위치의 날씨와 복장 추천을 볼 수 있어요.",
                               symbol: "location.slash")
                case .failed:
                    noticeCard("현재 위치를 확인하지 못했어요",
                               message: "잠시 뒤 탭을 다시 열면 다시 시도해요.",
                               symbol: "location.slash")
                default:
                    if let weather {
                        weatherCard(weather)
                        adviceCard(weather)
                        outfitCard(weather)
                    } else if weatherFailed {
                        noticeCard("날씨를 불러오지 못했어요",
                                   message: "네트워크 상태를 확인하고 탭을 다시 열어 주세요.",
                                   symbol: "cloud.slash")
                    } else {
                        loadingCard
                    }
                }

                attribution
            }
            .padding(.horizontal, 18)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .task { location.request() }
        .onChange(of: location.state) { _, newState in
            guard case .located(let coordinate) = newState else { return }
            Task { await loadWeather(coordinate) }
        }
    }

    private func loadWeather(_ coordinate: CLLocationCoordinate2D) async {
        do {
            let current = try await WeatherClient().current(latitude: coordinate.latitude,
                                                            longitude: coordinate.longitude)
            weather = current
            // 수분 알람 갱신 (계획서 M9) — 날씨를 받아온 이 시점이 당일분 예약 트리거.
            // 위치·날씨 조회가 이 탭에만 있어 앱의 예보 확인 창구도 여기 하나다.
            await NotificationScheduler.rescheduleHydration(forecastMaxC: current.forecastMaxC)
        } catch {
            weatherFailed = true
        }
    }

    private var todayEyebrow: String {
        // 문구는 포맷 밖에서 붙인다 — 패턴 안의 알파벳(예전 "TODAY")은
        // 날짜 기호로 해석돼 통째로 사라진다
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M.d (E)"
        return formatter.string(from: Date()) + " · 오늘"
    }

    // MARK: 날씨 카드

    private func weatherCard(_ weather: CurrentWeather) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("체감온도")
                        .font(.system(size: 11))
                        .foregroundStyle(RR.text3)

                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Text(String(format: "%.0f", weather.apparentC))
                            .font(.system(size: 52, weight: .heavy, design: .monospaced))
                            .foregroundStyle(RR.text)
                        Text("°C")
                            .font(.system(size: 20, weight: .bold, design: .monospaced))
                            .foregroundStyle(RR.text3)
                    }
                    .padding(.top, 4)
                }

                Spacer()

                if let condition = Self.condition(weather.weatherCode) {
                    VStack(spacing: 7) {
                        Image(systemName: condition.symbol)
                            .font(.system(size: 38))
                            .symbolRenderingMode(.multicolor)  // 시스템 멀티컬러 팔레트 — 색상 리터럴 아님
                        Text(condition.label)
                            .font(.system(size: 11.5, weight: .semibold))
                            .foregroundStyle(RR.text2)
                    }
                    .padding(.top, 8)
                    .padding(.trailing, 4)
                }
            }

            Divider().overlay(RR.line)
                .padding(.top, 14)

            HStack(spacing: 0) {
                metric("기온", String(format: "%.0f", weather.temperatureC), "°C")
                metric("습도", String(format: "%.0f", weather.humidityPct), "%")
                metric("바람", String(format: "%.1f", weather.windMs), "m/s")
                metric("강수", String(format: "%.1f", weather.precipitationMm), "mm")
                if let uv = weather.uvIndex {
                    metric("자외선", String(format: "%.0f", uv), Self.uvLevel(uv))
                }
            }
            .padding(.top, 2)
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 6, trailing: 18))
        .rrCard(radius: 22)
    }

    /// WMO 날씨 코드 → SF 심볼·한국어 상태 (Open-Meteo weather_code, WMO 4677 기준)
    private static func condition(_ code: Int?) -> (symbol: String, label: String)? {
        guard let code else { return nil }
        return switch code {
        case 0: ("sun.max.fill", "맑음")
        case 1: ("sun.max.fill", "대체로 맑음")
        case 2: ("cloud.sun.fill", "구름 조금")
        case 3: ("cloud.fill", "흐림")
        case 45, 48: ("cloud.fog.fill", "안개")
        case 51...57: ("cloud.drizzle.fill", "이슬비")
        case 61...67: ("cloud.rain.fill", "비")
        case 71...77: ("cloud.snow.fill", "눈")
        case 80...82: ("cloud.heavyrain.fill", "소나기")
        case 85, 86: ("cloud.snow.fill", "소낙눈")
        case 95...99: ("cloud.bolt.rain.fill", "뇌우")
        default: ("cloud.fill", "흐림")
        }
    }

    /// 자외선지수 등급 라벨 — WHO UV Index 구간 기준
    private static func uvLevel(_ uv: Double) -> String {
        switch uv {
        case ..<3: "낮음"
        case ..<6: "보통"
        case ..<8: "높음"
        case ..<11: "매우 높음"
        default: "위험"
        }
    }

    private func metric(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            Text(value)
                .font(.system(size: 18, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(unit)
                .font(.system(size: 10.5, design: .monospaced))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 12)
    }

    // MARK: 조언 카드

    private func adviceCard(_ weather: CurrentWeather) -> some View {
        let name = WeatherAdviceRules.runName(apparentC: weather.apparentC,
                                              precipitationMm: weather.precipitationMm,
                                              weatherCode: weather.weatherCode)
        let items = WeatherAdviceRules.advice(apparentC: weather.apparentC,
                                              humidityPct: weather.humidityPct,
                                              windMs: weather.windMs,
                                              precipitationMm: weather.precipitationMm,
                                              uvIndex: weather.uvIndex,
                                              weatherCode: weather.weatherCode)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("오늘의 러닝")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("advice")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            HStack(spacing: 13) {
                ZStack {
                    Circle().fill(name.tone.softColor)
                    Image(systemName: Self.runSymbol(name.kind))
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(name.tone.color)
                }
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: 4) {
                    Text(name.title)
                        .font(.system(size: 25, weight: .heavy))
                        .foregroundStyle(RR.text)
                    Text(name.quip)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(RR.text2)
                }
            }
            .padding(.top, 14)

            Divider().overlay(RR.line)
                .padding(.top, 15)

            VStack(alignment: .leading, spacing: 8) {
                ForEach(items, id: \.text) { item in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(item.tone.color)
                            .frame(width: 5, height: 5)
                            .padding(.top, 5)
                        Text(item.text)
                            .font(.system(size: 11.5))
                            .lineSpacing(3)
                            .foregroundStyle(RR.text3)
                    }
                }
            }
            .padding(.top, 13)
        }
        .padding(18)
        .rrCard(radius: 22)
    }

    /// 러닝 이름 → SF 심볼 — 엔진(RunName)은 UI를 모르므로 매핑은 화면 몫
    private static func runSymbol(_ kind: RunName.Kind) -> String {
        switch kind {
        case .treadmill: "house.fill"
        case .snow: "snowflake"
        case .rain: "umbrella.fill"
        case .sauna: "flame.fill"
        case .dawn: "sunrise.fill"
        case .shade: "tree.fill"
        case .fun: "party.popper.fill"
        case .crisp: "leaf.fill"
        case .hotpack: "thermometer.snowflake"
        case .penguin: "snowflake.circle.fill"
        }
    }

    // MARK: 복장 카드

    private func outfitCard(_ weather: CurrentWeather) -> some View {
        let items = OutfitRules.outfit(apparentC: weather.apparentC,
                                       humidityPct: weather.humidityPct,
                                       windMs: weather.windMs,
                                       precipitationMm: weather.precipitationMm,
                                       uvIndex: weather.uvIndex,
                                       now: Date())
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text("오늘의 러닝 복장")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(RR.text)
                Spacer()
                Text("outfit")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }

            OutfitGrid(items: items)
                .padding(.top, 14)
        }
        .padding(18)
        .rrCard(radius: 22)
    }

    // MARK: 안내·로딩

    private func noticeCard(_ title: String, message: String, symbol: String) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.text3)
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(RR.text)
            }
            Text(message)
                .font(.system(size: 12.5))
                .lineSpacing(4)
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .rrCard(radius: 22)
    }

    private var loadingCard: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("현재 위치의 날씨를 불러오는 중")
                .font(.system(size: 12.5))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .rrCard(radius: 22)
    }

    /// Open-Meteo 출처 표기 — CC BY 4.0 조건 (계획서 M6)
    private var attribution: some View {
        Link(destination: URL(string: "https://open-meteo.com")!) {
            Text("Weather data by Open-Meteo.com (CC BY 4.0)")
                .font(.system(size: 10.5))
                .foregroundStyle(RR.text3)
                .underline()
        }
        .padding(.horizontal, 4)
        .padding(.top, 2)
    }
}

// MARK: - 복장 그리드

/// 복장 아이템 타일 그리드 — 아이콘·라벨 매핑은 화면 몫 (OutfitRules는 UI를 모른다).
/// 칩 나열 대신 큰 아이콘 타일로 보여준다 (확장 요구, 2026-08-12).
private struct OutfitGrid: View {
    let items: [OutfitItem]

    var body: some View {
        let columns = [GridItem(.adaptive(minimum: 68), spacing: 9)]
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(items, id: \.self) { item in
                let meta = Self.meta(item)
                VStack(spacing: 7) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(RR.brandSoft)
                        Image(systemName: meta.symbol)
                            .font(.system(size: 27, weight: .medium))
                            .foregroundStyle(RR.brand)
                    }
                    .frame(height: 62)
                    Text(meta.label)
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(RR.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
        }
    }

    /// 같은 조합에 함께 나오는 아이템끼리는 심볼이 겹치지 않게 배정했다
    /// (반바지·타이츠처럼 동시에 안 나오는 쌍만 심볼을 공유)
    private static func meta(_ item: OutfitItem) -> (symbol: String, label: String) {
        switch item {
        case .singlet: ("tshirt", "싱글렛")
        case .shortSleeve: ("tshirt", "반팔 티")
        case .longSleeve: ("tshirt.fill", "긴팔 티")
        case .shorts: ("figure.run", "반바지")
        case .tights: ("figure.run", "타이츠")
        case .jacket: (firstAvailable("jacket.fill", "wind"), "자켓")
        case .gloves: ("hand.raised.fill", "장갑")
        case .windbreaker: ("wind", "바람막이")
        case .waterproofCap: ("umbrella", "방수 캡")
        case .waterproofJacket: ("cloud.rain", "방수 자켓")
        case .thermalTop: ("tshirt.fill", "방한 상의")
        case .thermalBottom: ("figure.run", "방한 하의")
        case .beanie: (firstAvailable("hat.cap.fill", "snowflake"), "비니")
        case .neckWarmer: ("thermometer.snowflake", "넥워머")
        case .sunCap: (firstAvailable("hat.cap.fill", "sun.max.fill"), "러닝 캡")
        case .sunglasses: ("sunglasses.fill", "선글라스")
        case .sunscreen: ("drop.fill", "선크림")
        }
    }

    /// 배포 타깃(iOS 17)보다 늦게 추가된 심볼은 구형 기기에서 빈 칸이 된다 —
    /// 런타임에 확인해 첫 사용 가능 심볼을 쓴다 (hat.cap.fill·jacket.fill은 iOS 18 추가)
    private static func firstAvailable(_ names: String...) -> String {
        names.first { UIImage(systemName: $0) != nil } ?? names[names.count - 1]
    }
}
