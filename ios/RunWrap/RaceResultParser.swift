import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// 자연어 대회 기록 파서 (이슈 #35) — "작년 10월 춘마 하프 1시간 45분" 같은 문장을
/// Apple Intelligence(Foundation Models)의 @Generable 제약 디코딩으로 구조화한다.
///
/// 역할 분담이 핵심이다: 모델은 자연어 → 필드 분해까지만 하고, 초 환산·종목 매칭·
/// 상대 날짜 해석은 전부 순수 코드(`interpret`)가 한다 — "숫자 계산은 모델에 맡기지
/// 않는다". 결과는 수동 입력 폼을 채우는 데만 쓰고 바로 저장하지 않는다:
/// 3B 온디바이스 모델의 오독은 사용자가 저장 전에 잡는다.
///
/// Foundation Models는 iOS 26+ 전용이고 배포 타깃은 iOS 17이라 전부
/// canImport + @available 뒤에 숨긴다. 못 쓰는 환경이면 자유 입력 UI 자체를 노출하지
/// 않는다 (isAvailable). 온디바이스 전용이라 건강 데이터 외부 전송 금지 규칙도 지킨다.
enum RaceResultParser {
    /// 파싱 결과 — 폼 프리필 재료. 확신 없는 필드는 nil로 남겨 기존 폼 값을 유지한다
    struct Parsed: Equatable {
        let race: RaceDistance?
        let timeSec: Double?
        let date: Date?
    }

    /// Apple Intelligence를 지금 쓸 수 있는지 — 미지원 OS·기기·모델 미다운로드면 false
    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        guard #available(iOS 26.0, *) else { return false }
        return SystemLanguageModel.default.availability == .available
        #else
        return false
        #endif
    }

    /// 자연어 한 줄 → 파싱 결과. 추출 실패·guardrail 위반 등 모든 오류는 nil로 삼킨다 —
    /// 화면은 조용히 수동 입력으로 넘긴다 (이슈 #35 폴백 규칙)
    static func parse(_ text: String, now: Date = Date()) async -> Parsed? {
        #if canImport(FoundationModels)
        guard #available(iOS 26.0, *) else { return nil }
        do {
            let session = LanguageModelSession(instructions: """
                러너가 말한 대회 완주 기록에서 필드를 추출한다. 계산하거나 추측하지 말고 \
                문장에 있는 값만 옮긴다. 확실하지 않은 필드는 비워 둔다.
                """)
            let response = try await session.respond(to: text,
                                                     generating: RaceResultFields.self)
            let fields = response.content
            return interpret(distanceKm: fields.distanceKm,
                             hours: fields.hours, minutes: fields.minutes,
                             seconds: fields.seconds,
                             year: fields.year, month: fields.month, day: fields.day,
                             now: now)
        } catch {
            return nil
        }
        #else
        return nil
        #endif
    }

    /// 모델 출력 → 폼 값 해석 (순수 로직 — 테스트는 이 함수만 겨눈다):
    /// - 초 환산: 시·분·초를 코드로 합산한다 (모델에 산술을 맡기지 않는다)
    /// - 종목 매칭: 공인 거리와 ±15% 안이면 해당 종목, 밖이면 nil (폼 기본값 유지)
    /// - 날짜: 연도가 없으면 그 월·일의 가장 가까운 과거로 해석한다 —
    ///   "작년 가을(10월)"은 지금이 8월이면 자연히 작년 10월이 된다.
    ///   미래 연도나 최근 2년 밖 연도가 오면 오독으로 보고 버린다 (두 자리는 2000년대).
    /// - 범위 검사: 시 0~7·분·초 0~59(휠 범위) 밖이나 음수인 필드는 버린다 (이슈 #93).
    ///   단 시가 없으면 "105분"처럼 분만으로 말한 경우를 살리기 위해 분은 0~479까지 허용한다.
    /// - 세 필드 모두 확신이 없으면 nil — 화면이 "읽지 못했다"를 안내한다
    static func interpret(distanceKm: Double?, hours: Int?, minutes: Int?, seconds: Int?,
                          year: Int?, month: Int?, day: Int?, now: Date) -> Parsed? {
        let race: RaceDistance? = distanceKm.flatMap { km in
            guard km > 0 else { return nil }
            return RaceDistance.allCases.first { abs($0.km - km) / $0.km <= 0.15 }
        }
        // 시·분·초 범위 검사 (이슈 #93) — 입력 휠 범위(0~7시간, 0~59분·초) 밖이거나 음수인
        // 필드는 오독으로 보고 그 필드만 버린다. 곱셈은 검사 뒤에 해 거대값의 오버플로 트랩을 막는다.
        // 시간 없이 분만 오면("105분") 분이 최상위 단위라 휠 상한(8시간 미만)까지 허용한다.
        let validHours = hours.flatMap { (0...7).contains($0) ? $0 : nil }
        let validMinutes = minutes.flatMap { (0...(hours == nil ? 479 : 59)).contains($0) ? $0 : nil }
        let validSeconds = seconds.flatMap { (0...59).contains($0) ? $0 : nil }
        let totalSec = (validHours ?? 0) * 3_600 + (validMinutes ?? 0) * 60 + (validSeconds ?? 0)
        let timeSec: Double? = totalSec > 0 ? Double(totalSec) : nil
        let date: Date? = month.flatMap { month in
            guard (1...12).contains(month) else { return nil }
            var comps = DateComponents()
            comps.month = month
            let calendar = Calendar.current
            if let year {
                // 두 자리 연도('25년')는 2000년대로 본다. 대회 기록 최대 나이(약 2년) 밖의
                // 연도는 오독이다 — 서기 25년이 date <= now를 통과하던 구멍 (이슈 #93)
                let fullYear = (0...99).contains(year) ? year + 2_000 : year
                let currentYear = calendar.component(.year, from: now)
                guard ((currentYear - 2)...currentYear).contains(fullYear) else { return nil }
                comps.year = fullYear
                comps.day = 1
                // 그 달의 실제 일수로 검증 — '9월 31일'이 10월 1일로 정규화되지 않게.
                // 그 달에 없는 일이면 일을 모를 때처럼 15일로 둔다
                guard let monthStart = calendar.date(from: comps),
                      let validDays = calendar.range(of: .day, in: .month, for: monthStart)
                else { return nil }
                comps.day = day.flatMap { validDays.contains($0) ? $0 : nil } ?? 15
                guard let date = calendar.date(from: comps), date <= now else { return nil }
                return date
            }
            // 일을 모르면 15일 — 예측 재료로는 월 해상도면 충분하다 (VO₂max 창이 ±14일).
            // 연도가 없으면 31일까지 받고, 그 달에 없는 일은 nextDate가 처리한다
            comps.day = day.flatMap { (1...31).contains($0) ? $0 : nil } ?? 15
            return calendar.nextDate(after: now, matching: comps,
                                     matchingPolicy: .nextTime, direction: .backward)
        }
        guard race != nil || timeSec != nil || date != nil else { return nil }
        return Parsed(race: race, timeSec: timeSec, date: date)
    }
}

#if canImport(FoundationModels)
/// 모델이 채우는 원시 필드 — @Generable 제약 디코딩으로 스키마가 보장된다.
/// 시간을 시·분·초로 나눠 받는 이유: "1시간 45분"의 초 환산 같은 산술을
/// 3B 모델에 맡기지 않기 위해서다 (이슈 #35)
@available(iOS 26.0, *)
@Generable
private struct RaceResultFields {
    @Guide(description: "대회 거리(km). 5K는 5, 10K는 10, 하프는 21.1, 풀코스는 42.2. 언급이 없으면 비워 둔다")
    var distanceKm: Double?
    @Guide(description: "완주 기록의 시간 부분. '1시간 45분'이면 1. 없으면 비워 둔다")
    var hours: Int?
    @Guide(description: "완주 기록의 분 부분. '1시간 45분'이면 45. 없으면 비워 둔다")
    var minutes: Int?
    @Guide(description: "완주 기록의 초 부분. 언급이 없으면 비워 둔다")
    var seconds: Int?
    @Guide(description: "대회 연도. '작년' 같은 상대 표현이면 비워 둔다")
    var year: Int?
    @Guide(description: "대회 월(1~12). 계절만 말했으면 봄 4, 여름 7, 가을 10, 겨울 1")
    var month: Int?
    @Guide(description: "대회 일(1~31). 언급이 없으면 비워 둔다")
    var day: Int?
}
#endif
