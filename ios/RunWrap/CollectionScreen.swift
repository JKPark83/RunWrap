import SwiftUI

/// 도감 — 수집한 새를 성조 이미지 + 당시 목표 + 수집일과 함께 보여준다 (기획서 §5).
///
/// 홈의 새 스테이지 헤더에서 진입한다. 별도 탭은 만들지 않는다 — 5탭 유지가 §6 원칙이다.
/// 전 종을 칸으로 깔아 두고 미수집은 실루엣으로 남긴다: 도감의 재미는 빈 칸에서 온다.
struct CollectionScreen: View {
    @EnvironmentObject private var collection: CollectionStore
    /// 이력 시트를 띄울 종 — 수집된 칸을 탭하면 채워진다 (이슈 #117)
    @State private var historySpecies: BirdSpecies?

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
        .sheet(item: $historySpecies) { species in
            SpeciesHistorySheet(species: species,
                                birds: collection.birds
                                    .filter { $0.species == species }
                                    .sorted { $0.collectedAt > $1.collectedAt })
        }
    }

    // MARK: - 요약

    private var summary: some View {
        VStack(alignment: .leading, spacing: 6) {
            Eyebrow(text: "도감")
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
            // 미수집도 종의 모양·색은 보여준다 — 무엇을 모으는지 보여야 모으고 싶어진다.
            // 흐리게 눌러 두었다가 수집하면 제 색이 된다
            SpeciesBirdView(species: species)
                .opacity(latest != nil ? 1 : 0.55)
                .frame(width: 104, height: 104)

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
        // 칸에는 최근 1마리만 서므로 같은 종의 이력은 시트로 연다. 미수집 칸은 반응하지 않는다
        .contentShape(Rectangle())
        .onTapGesture {
            if latest != nil { historySpecies = species }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(species: species, latest: latest,
                                                count: collected.count))
        .accessibilityHint(latest != nil ? "탭하면 수집 이력" : "")
        .accessibilityAddTraits(latest != nil ? .isButton : [])
    }

    private func accessibilityLabel(species: BirdSpecies, latest: CollectedBird?,
                                     count: Int) -> String {
        guard let latest else { return "\(species.label), 미수집. \(species.goalHint)" }
        let repeated = count > 1 ? " \(count)마리." : ""
        return "\(species.label), 수집함.\(repeated) \(latest.goalLabel), "
             + "\(Self.collectedFormatter.string(from: latest.collectedAt)) 수집"
    }

    private var footnote: some View {
        Text("새 종류는 키우는 동안 실제로 달린 기록이 정해요. 더 멀리, 더 빨리 달릴수록 큰 새가 됩니다.")
            .font(.system(size: 11.5))
            .foregroundStyle(RR.text2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.top, 18)
    }

    /// "2026년 8월 13일" — 도감은 이력이라 연도까지 적는다
    fileprivate static let collectedFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ko_KR")
        f.dateFormat = "yyyy년 M월 d일"
        return f
    }()
}

/// 같은 종의 수집 이력 — 도감 칸에는 최근 1마리만 서므로 나머지는 여기서 본다 (기획서 §5, 이슈 #117).
///
/// 종별 성조 이미지는 에셋 대기라 목록만 보여준다. 행마다 당시 목표 · 수집일 · 걸린 일수.
private struct SpeciesHistorySheet: View {
    let species: BirdSpecies
    /// 그 종의 수집 이력 — collectedAt 내림차순(최근이 위)
    let birds: [CollectedBird]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Eyebrow(text: species.label)
                        Text("\(birds.count)마리를 키워 냈어요")
                            .font(RR.display(22))
                            .foregroundStyle(RR.text)
                    }

                    VStack(spacing: 0) {
                        ForEach(Array(birds.enumerated()), id: \.element.id) { index, bird in
                            row(bird)
                            if index < birds.count - 1 {
                                Divider().overlay(RR.line).padding(.leading, 16)
                            }
                        }
                    }
                    .rrCard()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 28)
            }
            .background(RR.bg.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .tint(RR.brand)
        .presentationDetents([.medium, .large])
    }

    private func row(_ bird: CollectedBird) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(bird.goalLabel)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(RR.text)
                    .lineLimit(1)
                Text(CollectionScreen.collectedFormatter.string(from: bird.collectedAt))
                    .font(.system(size: 12))
                    .foregroundStyle(RR.text2)
            }
            Spacer(minLength: 8)
            Text(cycleText(bird.cycleDays))
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.brand)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    /// "27일 만에" — 시작한 날 바로 성조가 된 경우(0 이하)는 "당일"
    private func cycleText(_ days: Int) -> String {
        days > 0 ? "\(days)일 만에" : "당일"
    }
}
