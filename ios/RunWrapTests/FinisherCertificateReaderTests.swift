import Foundation
import Testing
import UIKit
@testable import RunWrap

/// 완주증 OCR 해석 검증 (이슈 #192) — 줄 텍스트 → 폼 프리필(순수 로직)과
/// 합성 이미지로 Vision 인식까지 잇는 통합 1건. now = 2026-09-30T00:00:00Z 고정.
struct FinisherCertificateReaderTests {
    let now = ISO8601DateFormatter().date(from: "2026-09-30T00:00:00Z")!

    private func ymd(_ date: Date?) -> [Int?] {
        guard let date else { return [] }
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return [comps.year, comps.month, comps.day]
    }

    @Test("한국 완주증 — 대회명 속 '하프', 01:45:30, '2026년 3월 15일'을 읽는다")
    func koreanCertificate() throws {
        let parsed = try #require(FinisherCertificateReader.interpret(
            lines: ["2026 서울하프마라톤", "완 주 증", "종목: 하프", "기록 01:45:30", "2026년 3월 15일"],
            now: now))
        #expect(parsed.race == .half)
        #expect(parsed.timeSec == 6_330)   // 1×3600 + 45×60 + 30
        #expect(ymd(parsed.date) == [2026, 3, 15])
    }

    @Test("넷타임 우선 — 같은 줄 라벨이든 바로 위 줄 라벨이든 건타임보다 넷타임")
    func prefersNetTime() throws {
        let sameLine = try #require(FinisherCertificateReader.interpret(
            lines: ["42.195km", "Gun Time 03:52:10", "Net Time 03:48:45"], now: now))
        #expect(sameLine.race == .full)
        #expect(sameLine.timeSec == 13_725)   // 3×3600 + 48×60 + 45
        let labelAbove = try #require(FinisherCertificateReader.interpret(
            lines: ["Net Time", "03:48:45", "Gun Time", "03:52:10"], now: now))
        #expect(labelAbove.timeSec == 13_725)
    }

    @Test("10K MM:SS — H:MM:SS가 없으면 52:31을 52분 31초로, 2026.05.03은 날짜로")
    func tenKMinutesSeconds() throws {
        let parsed = try #require(FinisherCertificateReader.interpret(
            lines: ["10km", "52:31", "2026.05.03"], now: now))
        #expect(parsed.race == .tenK)
        #expect(parsed.timeSec == 3_151)   // 52×60 + 31
        #expect(ymd(parsed.date) == [2026, 5, 3])
    }

    @Test("종목 우선순위 — '하프'+'마라톤' 동시 표기는 하프, 15km는 종목 없음")
    func distancePriority() throws {
        let half = try #require(FinisherCertificateReader.interpret(
            lines: ["제10회 춘천마라톤", "Half Marathon", "01:50:00"], now: now))
        #expect(half.race == .half)
        // 15km: 5K 패턴은 앞이 숫자라 제외 — 종목은 비우고 기록만 살린다 (1:20:00 = 4,800초)
        let fifteen = try #require(FinisherCertificateReader.interpret(
            lines: ["15km", "01:20:00"], now: now))
        #expect(fifteen.race == nil)
        #expect(fifteen.timeSec == 4_800)
    }

    @Test("구간 기록표 — 풀코스 완주증의 Half·10K·5K 구간 행은 종목이 아니라 대회명(마라톤)을 따른다")
    func splitTableDoesNotOverrideDistance() throws {
        // 구간 행에 기록이 붙어 있고 거리가 여럿 → 기록 없는 줄('서울마라톤')만 종목 후보. 넷타임 3:30:00
        let full = try #require(FinisherCertificateReader.interpret(
            lines: ["2026 서울마라톤", "Half 01:42:10", "Net Time 03:30:00"], now: now))
        #expect(full.race == .full)
        #expect(full.timeSec == 12_600)
        // 5K·10K 구간만 있고 넷 라벨이 없으면 첫 기록(25:12)이 뽑히지만 풀코스 페이스로는 비현실 → 기록 nil
        let splits = try #require(FinisherCertificateReader.interpret(
            lines: ["2026 서울마라톤", "5K 00:25:12", "10K 00:50:30"], now: now))
        #expect(splits.race == .full)
        #expect(splits.timeSec == nil)
        // 거리가 하나뿐이면 기록과 같은 줄이어도 그대로 종목으로 쓴다
        let single = try #require(FinisherCertificateReader.interpret(lines: ["하프 01:45:30"], now: now))
        #expect(single.race == .half)
        #expect(single.timeSec == 6_330)
    }

    @Test("비현실 기록 방어 — 하프 20분(57초/km)은 구간·페이스 오독으로 보고 기록만 버린다")
    func dropsImplausibleTime() throws {
        let parsed = try #require(FinisherCertificateReader.interpret(
            lines: ["하프", "00:20:00"], now: now))
        #expect(parsed.race == .half)
        #expect(parsed.timeSec == nil)
    }

    @Test("아무것도 못 읽으면 nil, 출발 시각(07:30 AM)은 기록으로 오인하지 않는다")
    func nothingAndClockTime() throws {
        #expect(FinisherCertificateReader.interpret(lines: ["감사합니다"], now: now) == nil)
        let parsed = try #require(FinisherCertificateReader.interpret(
            lines: ["10K", "출발 07:30 AM"], now: now))
        #expect(parsed.race == .tenK)
        #expect(parsed.timeSec == nil)
    }

    @Test("미래 날짜는 오독 — RaceResultParser가 날짜만 버리고 종목·기록은 살린다")
    func dropsFutureDate() throws {
        let parsed = try #require(FinisherCertificateReader.interpret(
            lines: ["하프", "01:45:30", "2027.01.01"], now: now))
        #expect(parsed.date == nil)
        #expect(parsed.race == .half)
        #expect(parsed.timeSec == 6_330)
    }

    @Test("Vision 합성 완주증 이미지 인식")
    func visionRecognizesSyntheticCertificate() async throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 1_000), format: format)
            .image { context in
                UIColor.white.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 800, height: 1_000))
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: 48, weight: .bold),
                    .foregroundColor: UIColor.black,
                ]
                for (index, line) in ["하프", "01:45:30", "2026.03.15"].enumerated() {
                    (line as NSString).draw(at: CGPoint(x: 80, y: 200 + index * 160),
                                            withAttributes: attributes)
                }
            }
        let cgImage = try #require(image.cgImage)
        let lines = try await FinisherCertificateReader.recognizeLines(in: cgImage)
        let parsed = try #require(FinisherCertificateReader.interpret(lines: lines, now: now))
        #expect(parsed.race == .half)
        #expect(parsed.timeSec == 6_330)
        #expect(ymd(parsed.date) == [2026, 3, 15])
    }
}
