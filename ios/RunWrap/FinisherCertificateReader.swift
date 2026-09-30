import Foundation
import Vision          // 텍스트 인식 (온디바이스)
import CoreGraphics

/// 완주증 OCR (이슈 #192) — 완주증(기록증) 사진에서 종목·기록·날짜를 읽어 대회 기록 폼을 채운다.
///
/// 역할 분담은 자연어 파서(#35)와 같다: Vision은 줄 단위 텍스트까지만, 필드 해석은 순수 코드
/// (`interpret`)가 하고 시·분·초 범위·연도 창·종목 매칭은 `RaceResultParser.interpret`에 위임한다.
/// Vision은 온디바이스라 이미지가 기기 밖으로 나가지 않고, 이미지는 저장하지도 않는다.
/// 결과는 폼 프리필까지만 — 저장은 사용자가 값을 확인하고 누른다 (#35와 같은 규칙).
enum FinisherCertificateReader {
    /// Vision 텍스트 인식 → 위→아래 순서의 줄 문자열 (각 관측의 최상위 후보 1개).
    /// 언어 교정은 끈다 — 숫자·시간이 단어로 교정되는 것을 막는다.
    /// perform은 동기·무거운 호출이라 백그라운드 태스크에서 돌린다.
    static func recognizeLines(in image: CGImage) async throws -> [String] {
        try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["ko-KR", "en-US"]
            request.usesLanguageCorrection = false
            try VNImageRequestHandler(cgImage: image).perform([request])
            // Vision 좌표는 아래가 0 — maxY 내림차순이 위→아래 순서다
            return (request.results ?? [])
                .sorted { $0.boundingBox.maxY > $1.boundingBox.maxY }
                .compactMap { $0.topCandidates(1).first?.string }
        }.value
    }

    /// 편의: 이미지 → 프리필. 오류는 nil로 삼킨다 — 화면은 조용히 수동 입력으로 넘긴다
    static func read(_ image: CGImage, now: Date = Date()) async -> RaceResultParser.Parsed? {
        guard let lines = try? await recognizeLines(in: image) else { return nil }
        return interpret(lines: lines, now: now)
    }

    /// 줄 텍스트 → 폼 프리필 (순수 로직 — 테스트는 이 함수를 겨눈다). 세 필드 모두 못 읽으면 nil.
    /// 종목·기록이 둘 다 있는데 페이스가 비현실적이면 기록만 버린다 — 구간 기록·페이스 오독 방어
    static func interpret(lines: [String], now: Date) -> RaceResultParser.Parsed? {
        let time = recordTime(in: lines)
        let date = raceDate(in: lines)
        guard let parsed = RaceResultParser.interpret(
            distanceKm: distanceKm(in: lines),
            hours: time?.hours, minutes: time?.minutes, seconds: time?.seconds,
            year: date?.year, month: date?.month, day: date?.day, now: now)
        else { return nil }
        if let race = parsed.race, let sec = parsed.timeSec,
           !RaceRecord.isPlausible(timeSec: sec, km: race.km) {
            return RaceResultParser.Parsed(race: race, timeSec: nil, date: parsed.date)
        }
        return parsed
    }

    // MARK: - 종목

    /// 우선순위 순 종목 패턴 — 우선순위가 줄 순서보다 먼저다 ("서울하프마라톤"은 '마라톤'보다 '하프')
    private static let distanceTiers: [[(pattern: String, km: Double)]] = [
        // 1. 공인 거리 숫자 표기
        [(#"(?<![\d.])42\.(195|2)(?!\d)"#, 42.195), (#"(?<![\d.])21\.(0975|1)(?!\d)"#, 21.0975)],
        // 2. 하프 — '마라톤'이 같이 있어도 하프
        [(#"하프|half"#, 21.0975)],
        // 3. 10K·5K — 앞이 숫자면 제외(15km·2.5km), 뒤가 영문자면 제외(10kg)
        [(#"(?<![\d.])10\s?(km|k)(?![a-z])"#, 10), (#"(?<![\d.])5\s?(km|k)(?![a-z])"#, 5)],
        // 4. 풀코스 — 가장 넓은 표현이라 맨 뒤
        [(#"풀코스|풀|full|마라톤|marathon"#, 42.195)],
    ]

    /// 풀코스 완주증의 구간 기록표("Half 01:42:10", "10K 00:50:30")에는 종목 라벨이 여럿 찍힌다.
    /// 서로 다른 거리가 둘 이상 보이면 기록이 같이 적힌 줄(구간 행)의 라벨은 버리고,
    /// 대회명처럼 기록 없는 줄에서만 고른다 — 그래야 '서울마라톤' 완주증이 하프로 읽히지 않는다.
    /// 거리가 하나뿐이면("하프 01:45:30") 그대로 쓴다
    private static func distanceKm(in lines: [String]) -> Double? {
        var hits: [(tier: Int, km: Double, withTime: Bool)] = []
        for (tierIndex, tier) in distanceTiers.enumerated() {
            for line in lines {
                if let hit = tier.first(where: { matches($0.pattern, in: line) }) {
                    hits.append((tierIndex, hit.km, hasTime(line)))
                }
            }
        }
        let distinct = Set(hits.map(\.km))
        if distinct.count > 1, hits.contains(where: { !$0.withTime }) {
            hits.removeAll(where: \.withTime)
        }
        return hits.min { $0.tier < $1.tier }?.km
    }

    /// 줄에 기록(H:MM:SS·MM:SS·N시간 N분)이 있는지 — 구간 기록표 행 판별용
    private static func hasTime(_ line: String) -> Bool {
        matches(#"\d{1,2}:\d{2}|\d{1,2}\s?시간\s?\d{1,2}\s?분"#, in: line)
    }

    // MARK: - 기록

    private struct TimeCandidate {
        let hours: Int?
        let minutes: Int
        let seconds: Int?
        let line: Int
    }

    /// 넷타임(칩) 우선 — 같은 줄(없으면 바로 위 줄)의 라벨로 후보 순위를 정한다.
    /// 넷·칩 라벨 → 맨 앞, 무라벨 → 가운데, 건·총 라벨만 → 맨 뒤. 같은 순위는 위→아래
    private static func recordTime(in lines: [String]) -> (hours: Int?, minutes: Int, seconds: Int?)? {
        var candidates: [TimeCandidate] = []
        for (index, line) in lines.enumerated() {
            for groups in captures(#"(?<![\d:])(\d{1,2}):(\d{2}):(\d{2})(?![\d:])"#, in: line) {
                if let h = Int(groups[0]), let m = Int(groups[1]), let s = Int(groups[2]) {
                    candidates.append(TimeCandidate(hours: h, minutes: m, seconds: s, line: index))
                }
            }
            for groups in captures(#"(?<!\d)(\d{1,2})\s?시간\s?(\d{1,2})\s?분(?:\s?(\d{1,2})\s?초)?"#, in: line) {
                if let h = Int(groups[0]), let m = Int(groups[1]) {
                    candidates.append(TimeCandidate(hours: h, minutes: m, seconds: Int(groups[2]), line: index))
                }
            }
        }
        // 8시간 이상은 휠 범위 밖 — 페이스·시각 표기 오독으로 보고 버린다
        candidates.removeAll { ($0.hours ?? 0) >= 8 }
        // H:MM:SS가 하나도 없을 때만 MM:SS를 받는다. 시각(07:30 AM·오전 7:30)은 제외
        if candidates.isEmpty {
            for (index, line) in lines.enumerated() {
                let pattern = #"(?<![\d:])(?<!(?:오전|오후)\s?)(\d{1,2}):(\d{2})(?![\d:])(?!\s?(?:am|pm|오전|오후))"#
                for groups in captures(pattern, in: line) {
                    if let m = Int(groups[0]), let s = Int(groups[1]) {
                        candidates.append(TimeCandidate(hours: nil, minutes: m, seconds: s, line: index))
                    }
                }
            }
        }
        func rank(_ candidate: TimeCandidate) -> Int {
            let own = lines[candidate.line]
            let above = candidate.line > 0 ? lines[candidate.line - 1] : ""
            let labelLine = hasLabel(own) ? own : above
            if matches(netLabel, in: labelLine) { return 0 }
            if matches(gunLabel, in: labelLine) { return 2 }
            return 1
        }
        // min(by:)는 동순위에서 앞 원소를 돌려준다 — 위→아래 순서가 유지된다
        guard let best = candidates.min(by: { rank($0) < rank($1) }) else { return nil }
        return (best.hours, best.minutes, best.seconds)
    }

    private static let netLabel = #"넷|net|칩|chip"#
    private static let gunLabel = #"gun|건|총"#

    private static func hasLabel(_ line: String) -> Bool {
        matches(netLabel, in: line) || matches(gunLabel, in: line)
    }

    // MARK: - 날짜

    /// 한국 완주증의 '2026.03.15'·'2026-3-15'·'2026년 3월 15일'. 없으면 연도 없는 '3월 15일'
    /// (연도 해석은 RaceResultParser.interpret이 가장 가까운 과거로 한다)
    private static func raceDate(in lines: [String]) -> (year: Int?, month: Int, day: Int)? {
        let full = #"(?<!\d)(\d{4})\s?[.\-/년]\s?(\d{1,2})\s?[.\-/월]\s?(\d{1,2})(?!\d)"#
        for line in lines {
            if let groups = captures(full, in: line).first,
               let y = Int(groups[0]), let m = Int(groups[1]), let d = Int(groups[2]) {
                return (y, m, d)
            }
        }
        for line in lines {
            if let groups = captures(#"(?<!\d)(\d{1,2})\s?월\s?(\d{1,2})\s?일"#, in: line).first,
               let m = Int(groups[0]), let d = Int(groups[1]) {
                return (nil, m, d)
            }
        }
        return nil
    }

    // MARK: - 정규식 헬퍼 (대소문자 무시)

    private static func matches(_ pattern: String, in line: String) -> Bool {
        !captures(pattern, in: line).isEmpty
    }

    /// 매칭마다 캡처 그룹 문자열 배열 — 매칭되지 않은 그룹은 빈 문자열
    private static func captures(_ pattern: String, in line: String) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive)
        else { return [] }
        let text = line as NSString
        return regex.matches(in: line, range: NSRange(location: 0, length: text.length)).map { match in
            (1..<match.numberOfRanges).map { index in
                let range = match.range(at: index)
                return range.location == NSNotFound ? "" : text.substring(with: range)
            }
        }
    }
}
