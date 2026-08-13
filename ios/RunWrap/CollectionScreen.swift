import SwiftUI

/// 도감 — 수집한 새를 성조 이미지 + 당시 목표 + 수집일과 함께 보여준다 (기획서 §5).
///
/// 홈의 새 스테이지 헤더에서 진입한다. 별도 탭은 만들지 않는다 — 5탭 유지가 §6 원칙이다.
/// 전 종을 칸으로 깔아 두고 미수집은 실루엣으로 남긴다: 도감의 재미는 빈 칸에서 온다.
struct CollectionScreen: View {
    @EnvironmentObject private var collection: CollectionStore

    private let columns = [GridItem(.flexible(), spacing: 12),
                           GridItem(.flexible(), spacing: 12)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                summary

                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(BirdSpecies.allCases, id: \.self) { species in
                        cell(for: species)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)

                footnote
            }
            .padding(.bottom, 28)
        }
        .background(RR.bg.ignoresSafeArea())
        .navigationTitle("도감")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - 요약

    private var summary: some View {
        VStack(alignment: .leading, spacing: 6) {
            Eyebrow(text: "collection")
            Text("\(collection.birds.count)마리를 키워 냈어요")
                .font(RR.display(22))
                .foregroundStyle(RR.text)
            Text("성조가 된 새는 도감에 남습니다. 새 목표를 잡으면 새 알에서 다시 시작해요.")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    // MARK: - 칸

    @ViewBuilder
    private func cell(for species: BirdSpecies) -> some View {
        // 같은 종을 여러 사이클에서 수집할 수 있다 — 칸에는 가장 최근 것을 세우고
        // 2마리 이상이면 개수를 덧붙인다
        let collected = collection.birds.filter { $0.species == species }
        let latest = collected.max { $0.collectedAt < $1.collectedAt }

        VStack(spacing: 10) {
            ZStack {
                if latest != nil {
                    BirdView(stage: .flying)
                } else {
                    // 미수집 — 같은 실루엣을 회색 한 겹으로 눌러 둔다.
                    // 실루엣도 새 모양이라 "무엇을 모으는 중인지"는 보인다
                    BirdView(stage: .flying)
                        .opacity(0.13)
                        .grayscale(1)
                }
            }
            // BirdView는 240×240 정사각형을 **폭 기준**으로 확대한다 — 칸 폭을 그대로
            // 주면 높이를 넘어 아래 글자를 덮는다. 폭도 92로 묶어 정사각형을 유지한다
            .frame(width: 92, height: 92)

            VStack(spacing: 3) {
                HStack(spacing: 4) {
                    Text(species.label)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(latest != nil ? RR.text : RR.text2)
                    if collected.count > 1 {
                        Text("×\(collected.count)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(RR.brand)
                    }
                }

                if let latest {
                    Text(latest.goalLabel)
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(RR.text2)
                        .lineLimit(1)
                    Text(Self.collectedFormatter.string(from: latest.collectedAt))
                        .font(.system(size: 10.5))
                        .foregroundStyle(RR.text2)
                } else {
                    Text(species.goalHint)
                        .font(.system(size: 11))
                        .foregroundStyle(RR.text2)
                        .lineLimit(1)
                    Text("아직 비어 있어요")
                        .font(.system(size: 10.5))
                        .foregroundStyle(RR.text2)
                        .opacity(0.7)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .rrCard()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(species: species, latest: latest,
                                                count: collected.count))
    }

    private func accessibilityLabel(species: BirdSpecies, latest: CollectedBird?,
                                     count: Int) -> String {
        guard let latest else { return "\(species.label), 미수집. \(species.goalHint)" }
        let repeated = count > 1 ? " \(count)마리." : ""
        return "\(species.label), 수집함.\(repeated) 목표 \(latest.goalLabel), "
             + "\(Self.collectedFormatter.string(from: latest.collectedAt)) 수집"
    }

    private var footnote: some View {
        Text("새 종류는 그 사이클의 목표 수준이 정해요. 큰 목표일수록 큰 새가 됩니다.")
            .font(.system(size: 11.5))
            .foregroundStyle(RR.text2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.top, 18)
    }

    /// "2026년 8월 13일" — 도감은 이력이라 연도까지 적는다
    private static let collectedFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "yyyy년 M월 d일"
        return f
    }()
}
