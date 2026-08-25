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
    ///   미래 연도가 오면 오독으로 보고 버린다.
    /// - 세 필드 모두 확신이 없으면 nil — 화면이 "읽지 못했다"를 안내한다
    static func interpret(distanceKm: Double?, hours: Int?, minutes: Int?, seconds: Int?,
                          year: Int?, month: Int?, day: Int?, now: Date) -> Parsed? {
        let race: RaceDistance? = distanceKm.flatMap { km in
            guard km > 0 else { return nil }
            return RaceDistance.allCases.first { abs($0.km - km) / $0.km <= 0.15 }
        }
        let totalSec = (hours ?? 0) * 3_600 + (minutes ?? 0) * 60 + (seconds ?? 0)
        let timeSec: Double? = totalSec > 0 ? Double(totalSec) : nil
        let date: Date? = month.flatMap { month in
            guard (1...12).contains(month) else { return nil }
            var comps = DateComponents()
            comps.month = month
            // 일을 모르면 15일 — 예측 재료로는 월 해상도면 충분하다 (VO₂max 창이 ±14일)
            comps.day = day.flatMap { (1...31).contains($0) ? $0 : nil } ?? 15
            let calendar = Calendar.current
            if let year {
                comps.year = year
                guard let date = calendar.date(from: comps), date <= now else { return nil }
                return date
            }
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
