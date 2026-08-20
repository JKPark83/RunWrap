import Foundation

/// 에어코리아 대기질 순수 로직 — 최근접 측정소 탐색·공식 등급 매핑·캐시 신선도 (이슈 #8).
///
/// KOGL 제3유형(출처표시·변경금지) 준수: 등급을 자체 산식으로 재가공하지 않는다.
/// API가 내려주는 공식 등급(1~4)을 그대로 쓰고, 응답에 등급이 빠진 항목만
/// 에어코리아 공식 등급 구간표(airkorea.or.kr 통합대기환경지수 안내)로 보완한다.
/// 화면 문구도 공식 4등급(좋음/보통/나쁨/매우나쁨)을 그대로 쓴다.

/// 전국 측정소 한 곳 — 번들 AirStations.json 항목 (tools/air-quality 산출 포맷과 1:1).
/// 최근접 탐색을 기기에서 하기 위한 정적 데이터다 — 사용자 좌표는 밖으로 나가지 않는다.
struct AirStation: Codable, Equatable {
    let name: String
    let lat: Double
    let lon: Double
}

/// AirStations.json 전체 — generatedAt은 갱신 배치(air-stations.yml)가 찍는다
struct AirStationFile: Codable {
    let generatedAt: String
    let stations: [AirStation]
}

/// 에어코리아 공식 4등급 — API grade 값(1~4)과 같은 rawValue.
/// Comparable은 "PM 중 나쁜 쪽" 선택(representativeGrade)이 요구한다 — 클수록 나쁘다
enum AirGrade: Int, Codable, Comparable {
    case good = 1, moderate, bad, veryBad

    /// 에어코리아 공식 등급 문구 그대로 (KOGL 변경금지 — 다른 표현으로 바꾸지 않는다)
    var label: String {
        switch self {
        case .good: "좋음"
        case .moderate: "보통"
        case .bad: "나쁨"
        case .veryBad: "매우나쁨"
        }
    }

    /// 등급 → 카드 톤. 색은 화면(Theme)이 정한다 — 엔진은 RRTone까지만
    var tone: RRTone {
        switch self {
        case .good: .improving
        case .moderate: .steady
        case .bad: .caution
        case .veryBad: .overload
        }
    }

    static func < (lhs: AirGrade, rhs: AirGrade) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// 측정소 실시간 수치 스냅샷 — 클라이언트 디코드 결과이자 캐시 저장 단위.
/// 값이 nil인 항목은 측정소 통신장애·점검 등으로 원 데이터가 없는 것 — 화면은 그 항목을 그리지 않는다
struct AirQuality: Codable, Equatable {
    let stationName: String
    /// 측정 기준 시각 — API 원문 그대로 ("2026-08-20 14:00")
    let dataTime: String?
    /// 미세먼지 PM10 (㎍/㎥)
    let pm10: Double?
    /// 초미세먼지 PM2.5 (㎍/㎥)
    let pm25: Double?
    /// 오존 (ppm)
    let o3: Double?
    /// 통합대기환경지수 CAI
    let khai: Double?
    let pm10Grade: AirGrade?
    let pm25Grade: AirGrade?
    let o3Grade: AirGrade?
    let khaiGrade: AirGrade?
}

enum AirQualityEngine {
    /// 지구 반지름 6,371km 기준 위도 1도의 미터 — Course/NearbySupplyEngine과 같은 상수
    private static let metersPerDegree = 111_195.0

    /// 최근접 측정소 — 등장방형 평면 투영 직선거리(수십 km 스케일이면 충분).
    ///
    /// 미노출 가드: 최근접이 maxMeters(기본 30km) 밖이면 nil — 해외 등 커버리지 밖에서
    /// 엉뚱한 측정소 수치를 "현재 위치 공기"로 내보이지 않는다.
    static func nearestStation(to point: GeoPoint, stations: [AirStation],
                               maxMeters: Double = 30_000) -> AirStation? {
        let lonScale = metersPerDegree * cos(point.lat * .pi / 180)
        var best: (station: AirStation, meters: Double)?
        for station in stations {
            let dx = (station.lon - point.lon) * lonScale
            let dy = (station.lat - point.lat) * metersPerDegree
            let meters = hypot(dx, dy)
            if best == nil || meters < best!.meters { best = (station, meters) }
        }
        guard let best, best.meters <= maxMeters else { return nil }
        return best.station
    }

    // MARK: 공식 등급 구간표 폴백 — 응답에 등급이 없을 때만 쓴다 (에어코리아 등급 기준)

    /// PM10 (㎍/㎥): 0–30 좋음 · 31–80 보통 · 81–150 나쁨 · 151~ 매우나쁨
    static func pm10Grade(_ value: Double) -> AirGrade {
        switch value {
        case ...30: .good
        case ...80: .moderate
        case ...150: .bad
        default: .veryBad
        }
    }

    /// PM2.5 (㎍/㎥): 0–15 좋음 · 16–35 보통 · 36–75 나쁨 · 76~ 매우나쁨
    static func pm25Grade(_ value: Double) -> AirGrade {
        switch value {
        case ...15: .good
        case ...35: .moderate
        case ...75: .bad
        default: .veryBad
        }
    }

    /// 오존 (ppm): 0–0.030 좋음 · –0.090 보통 · –0.150 나쁨 · 0.151~ 매우나쁨
    static func o3Grade(_ value: Double) -> AirGrade {
        switch value {
        case ...0.030: .good
        case ...0.090: .moderate
        case ...0.150: .bad
        default: .veryBad
        }
    }

    /// 통합대기환경지수 CAI: 0–50 좋음 · –100 보통 · –250 나쁨 · 251~ 매우나쁨
    static func khaiGrade(_ value: Double) -> AirGrade {
        switch value {
        case ...50: .good
        case ...100: .moderate
        case ...250: .bad
        default: .veryBad
        }
    }

    // MARK: 대표 등급·미노출 가드·캐시

    /// 카드 배지의 대표 등급 — 통합지수(6개 오염물질을 묶는 공식 지수)가 있으면 그것,
    /// 없으면 PM 두 등급 중 나쁜 쪽. 어느 쪽이든 공식 등급을 고를 뿐 새 등급을 만들지 않는다
    static func representativeGrade(_ quality: AirQuality) -> AirGrade? {
        if let khai = quality.khaiGrade { return khai }
        return [quality.pm25Grade, quality.pm10Grade].compactMap { $0 }.max()
    }

    /// PM 수치가 하나도 없으면 지표를 내지 않는다 — 통신장애 측정소의 빈 응답 가드
    static func hasReading(_ quality: AirQuality) -> Bool {
        quality.pm10 != nil || quality.pm25 != nil
    }

    /// 캐시 신선도 — 측정값 자체가 시간 단위로 갱신되므로 1시간이면 정보 손실이 없고,
    /// 기기당 하루 최대 24건으로 개발계정 트래픽 한도(500건/일)를 방어한다 (이슈 #8 선결 과제)
    static func isFresh(fetchedAt: Date, now: Date, maxAge: TimeInterval = 3_600) -> Bool {
        let age = now.timeIntervalSince(fetchedAt)
        return age >= 0 && age < maxAge
    }
}
