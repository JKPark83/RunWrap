/// SwiftCompat 테스트 픽스처 생성기 — 실제 Swift가 낸 출력을 정답으로 기록한다.
///
/// 재생성 (저장소 루트에서, macOS):
///   swift android/tools/gen_swiftcompat_fixture.swift > android/engine/src/test/resources/swiftcompat_fixture.tsv
///
/// 열: bits(Double bitPattern 16진수) · %.0f · %.1f · %.2f · %.3f · %+.0f · %+.1f · rounded() · Int(rounded())
/// 지정자는 iOS(ios/RunWrap·RunWrapWidget)가 실제로 쓰는 소수 지정자만 넣었다.
/// rounded()는 bitPattern으로 적는다(-0.0 구분). Int 열은 NaN·무한대·Int32 범위 밖이면 "-" (Swift는 트랩).
import Foundation

var values: [Double] = [0, -0.0, .nan, .infinity, -.infinity, 1e-300, -1e-300, 5e-324, 1e6, 1234567.5, 1e15, 1e20, -1e7,
                        0.25, 0.35, 1.005, 2.675, 0.125, 0.375, 1.25, 1.35, 2.5e-1, 0.0005, 0.0015, 0.0025, 0.0045,
                        0.49999999999999994, -0.49999999999999994, 4503599627370495.5, -4503599627370495.5]
// 중간값: ±0.5, ±1.5, … ±20.5
for i in 0...20 { values += [Double(i) + 0.5, -(Double(i) + 0.5)] }
// 0.05·0.15·…·9.95 와 0.005·0.015·…·0.995 (각각 ±)
for i in 0..<100 { let a = Double(2 * i + 1) * 0.05; values += [a, -a] }
for i in 0..<100 { let a = Double(2 * i + 1) * 0.005; values += [a, -a] }
for i in 0..<100 { let a = Double(2 * i + 1) * 0.0005; values += [a, -a] }

// 고정 시드 난수 (SplitMix64) — 페이스(초/km)·거리(km)·심박·변화율(%) 범위
var state: UInt64 = 0x5EED_2026
func next() -> Double {
    state &+= 0x9E37_79B9_7F4A_7C15
    var z = state
    z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
    z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
    z ^= z >> 31
    return Double(z >> 11) / Double(1 << 53)
}
for _ in 0..<600 { values.append(180 + next() * 420) }          // 페이스 180~600초/km
for _ in 0..<600 { values.append(next() * 50) }                 // 거리 0~50km
for _ in 0..<400 { values.append(40 + next() * 170) }           // 심박 40~210
for _ in 0..<600 { values.append((next() - 0.5) * 200) }        // 변화율 −100~+100%
for _ in 0..<200 { values.append((next() - 0.5) * 2) }          // −1~1
// 난수를 0.05 격자로 반올림한 값 — 이진값이 중간값 근처에 몰린다
for _ in 0..<200 { values.append((((next() - 0.5) * 400).rounded() * 0.05)) }

func hex(_ d: Double) -> String { String(format: "%016llx", d.bitPattern) }

print(["bits", "f0", "f1", "f2", "f3", "p0", "p1", "rounded", "int"].joined(separator: "\t"))
for v in values {
    let r = v.rounded()
    let int = (r.isFinite && abs(r) <= Double(Int32.max)) ? String(Int(r)) : "-"
    print([hex(v),
           String(format: "%.0f", v), String(format: "%.1f", v), String(format: "%.2f", v), String(format: "%.3f", v),
           String(format: "%+.0f", v), String(format: "%+.1f", v),
           hex(r), int].joined(separator: "\t"))
}
