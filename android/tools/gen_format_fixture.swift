/// Format 테스트 픽스처 생성기 — iOS `Format`(ios/RunWrap/Theme.swift)이 실제로 낸 출력을 정답으로 기록한다.
///
/// Theme.swift는 SwiftUI·UIKit을 import해 macOS에서 그대로 컴파일되지 않으므로, `enum Format { … }` 블록만
/// 원문 그대로 잘라 이 파일 앞에 붙여 컴파일한다(원본 로직은 한 글자도 바꾸지 않는다).
/// 시각 의존 함수(weekLabel·monthDayTime·relativeWeek)는 `TimeZone.current`를 쓰므로 TZ를 바꿔 두 번 돌린다.
///
/// 재생성 (저장소 루트에서, macOS — 기기 로케일이 ko_KR이어야 kcal의 천 단위 구분이 iOS 사용자와 같다):
///   T=$(mktemp -d); { echo 'import Foundation'; sed -n '/^enum Format {/,/^}/p' ios/RunWrap/Theme.swift; \
///     cat android/tools/gen_format_fixture.swift; } > $T/main.swift && swiftc -O $T/main.swift -o $T/gen && \
///   { TZ=UTC $T/gen; TZ=Asia/Seoul $T/gen; } | awk '!seen[$0]++' > android/engine/src/test/resources/format_fixture.tsv
///
/// 열: fn · zone · input · expected
/// - zone: 시각 무관 함수는 "-", 시각 의존 함수는 생성 당시 TZ ("UTC"/"Asia/Seoul")
/// - input: Double은 bitPattern 16진수(-0.0·NaN 구분), 시각은 ISO8601(Z), relativeWeek는 "date|now"

func hex(_ d: Double) -> String { String(format: "%016llx", d.bitPattern) }

// TZ=UTC는 identifier가 "GMT"로 나온다 — 오프셋이 같아 결과도 같으므로 열 값만 "UTC"로 적는다
let zone = TimeZone.current.secondsFromGMT() == 0 ? "UTC" : TimeZone.current.identifier
let isoOut = ISO8601DateFormatter()
func iso(_ s: String) -> Date { ISO8601DateFormatter().date(from: s)! }

print(["fn", "zone", "input", "expected"].joined(separator: "\t"))
func row(_ fn: String, _ zone: String, _ input: String, _ out: String) {
    print([fn, zone, input, out].joined(separator: "\t"))
}

// MARK: 시각 무관 — duration·pace·paceKm·km·walkRunMinutes·kcal

// 반올림 .5(Swift rounded()는 0에서 먼 쪽), 0, 59.5초, 시 경계(3599.5), 음수
var seconds: [Double] = [0, -0.0, 0.4, 0.5, 0.49999999999999994, 1.5, 2.5, 59.4, 59.5, 59.6, 60, 89.5,
                         599.5, 3_599.4, 3_599.5, 3_600, 3_661, 35_999.5, 36_000, 86_399.5, 359_999.5,
                         -0.4, -0.5, -1, -1.5, -59.5, -61, -3_599.5, -3_600, -3_661]
// 페이스 범위(150~1200초/km)의 .5 지점
for s in stride(from: 150.0, through: 1_200, by: 37) { seconds += [s + 0.5, s + 0.49, s + 0.51] }
for v in seconds {
    row("duration", "-", hex(v), Format.duration(v))
    row("pace", "-", hex(v), Format.pace(v))
    row("paceKm", "-", hex(v), Format.paceKm(v))
}

// %.1f — 이진값 기준 짝수 반올림 (0.25 → 0.2, 0.35 → 0.3)
var kms: [Double] = [0, -0.0, 0.04, 0.05, 0.15, 0.25, 0.35, 0.45, 1.25, 2.675, 9.95, 10, 21.0975, 42.195,
                     99.95, 1_234.55, -0.04, -0.05, -0.25, -1.25]
for i in 0..<40 { kms.append(Double(2 * i + 1) * 0.05) }
for v in kms { row("km", "-", hex(v), Format.km(v)) }

// 걷뛰 분 — 정수/반 분, 음수, 0.5 이외 소수
for v in [0, -0.0, 0.5, 1, 1.5, 2, 3.5, 4.5, 5, 0.25, 0.75, 1.05, 1.25, 2.04, 10, -0.5, -1, -1.5] {
    row("walkRunMinutes", "-", hex(v), Format.walkRunMinutes(v))
}

// kcal — NumberFormatter(.decimal, 소수 0자리, 기본 halfEven), 천 단위 구분
for v in [0, -0.0, -0.4, -0.5, 0.4, 0.5, 1.5, 2.5, 3.5, -1.5, -2.5, 99.5, 999.4, 999.5, 1_000, 1_000.5,
          1_234.5, 4_120, 12_345.6, 99_999.5, 123_456.7, 1_234_567.5, 10_000_000, -999.5, -1_234.5,
          -1_234_567,
          // 중간값 바로 옆 이진값·큰 수(최단 십진 표기 뒤 0 채움)
          0.49999999999999994, 0.5000000000000001, 2.4999999999999996, 2.5000000000000004,
          1_234_567.4999999998, 1_234_567.5000000002, 999_999_999_999_999.5, 4_503_599_627_370_495.5,
          1.2345678901234567e19, 1e20, 1.23456e20] {
    row("kcal", "-", hex(v), Format.kcal(v))
}

// MARK: 시각 의존 — weekLabel(withYear)·monthDayTime·relativeWeek

var cal = Calendar(identifier: .iso8601)
cal.timeZone = .current

// weekLabel: 2025-11-24 주 ~ 2027-02-01 주의 모든 주 시작(현재 TZ의 월요일 자정) + 연말·연초 주
var weekStarts: [Date] = []
var cursor = cal.dateInterval(of: .weekOfYear, for: iso("2025-11-26T12:00:00Z"))!.start
while cursor < iso("2027-02-08T00:00:00Z") {
    weekStarts.append(cursor)
    cursor = cal.date(byAdding: .weekOfYear, value: 1, to: cursor)!
}
// 목요일이 연도를 바꾸는 주 (2020-12-28 주 → 목 12-31, 2021-01-04 주, 2015-12-28 주 → 목 2015-12-31,
// 2026-02-23 주 → 목 02-26, 2024-02-26 주 → 목 02-29 윤일)
for s in ["2020-12-30T12:00:00Z", "2021-01-06T12:00:00Z", "2015-12-30T12:00:00Z", "2016-01-06T12:00:00Z",
          "2024-02-28T12:00:00Z", "2024-03-06T12:00:00Z", "2009-12-30T12:00:00Z", "2010-01-06T12:00:00Z"] {
    weekStarts.append(cal.dateInterval(of: .weekOfYear, for: iso(s))!.start)
}
// 주 시작이 아닌 입력 — UTC 일요일 밤(KST 월요일 아침)처럼 TZ에 따라 날짜가 갈리는 시각
for s in ["2026-08-09T20:00:00Z", "2026-08-30T16:00:00Z", "2026-12-27T15:30:00Z", "2025-12-28T23:00:00Z",
          "2026-03-01T14:59:59Z", "2026-03-01T15:00:00Z"] {
    weekStarts.append(iso(s))
}
for d in weekStarts {
    row("weekLabel", zone, isoOut.string(from: d), Format.weekLabel(weekStart: d))
    row("weekLabelYear", zone, isoOut.string(from: d), Format.weekLabel(weekStart: d, withYear: true))
}

// monthDayTime: 오전/오후·자정(오전 12)·정오(오후 12)·한 자리/두 자리 월일, 같은 UTC 시각이 TZ마다 다르게 찍힌다
for day in ["2026-01-01", "2026-09-30", "2026-12-31", "2026-10-05", "2027-02-28"] {
    for time in ["00:00:00", "00:05:00", "00:59:59", "01:00:00", "02:30:00", "03:12:00", "09:07:00", "11:59:59",
                 "12:00:00", "12:01:00", "13:05:00", "14:59:00", "15:00:00", "15:12:00", "21:00:00", "23:59:59"] {
        let d = iso("\(day)T\(time)Z")
        row("monthDayTime", zone, isoOut.string(from: d), Format.monthDayTime(d))
    }
}

// relativeWeek: now를 주 경계 근처(현재 TZ 월요일 자정 직후·일요일 자정 직전)와 주 중간에 두고, date를 −3일 ~ +40일 전으로
let mondayStart = cal.dateInterval(of: .weekOfYear, for: iso("2026-08-12T12:00:00Z"))!.start
let nows = [mondayStart, mondayStart.addingTimeInterval(60), mondayStart.addingTimeInterval(7 * 86_400 - 1),
            iso("2026-08-13T09:00:00Z"), iso("2026-01-01T00:00:00Z"),
            cal.dateInterval(of: .weekOfYear, for: iso("2027-01-04T12:00:00Z"))!.start]
for now in nows {
    var dates: [Date] = []
    for h in stride(from: -72, through: 40 * 24, by: 7) { dates.append(now.addingTimeInterval(Double(-h) * 3_600)) }
    // 주 경계 바로 앞뒤
    let thisWeek = cal.dateInterval(of: .weekOfYear, for: now)!.start
    for w in 0...5 {
        let b = cal.date(byAdding: .weekOfYear, value: -w, to: thisWeek)!
        dates += [b, b.addingTimeInterval(-1)]
    }
    for d in dates {
        row("relativeWeek", zone, isoOut.string(from: d) + "|" + isoOut.string(from: now),
            Format.relativeWeek(of: d, now: now))
    }
}
