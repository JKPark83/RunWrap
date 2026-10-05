import SwiftUI

/// 수집 세러모니 — 성조 도달 → 축하 → 도감 수록 → 새 목표 → 새 알 (기획서 §5).
///
/// 외부 의존성 없음 원칙이라 Lottie를 쓰지 않는다. 파티클은 `TimelineView`+`Canvas`로
/// 직접 그린다 — 뷰를 수백 개 만들지 않고 한 캔버스에 찍는 편이 가볍다.
///
/// 이 화면은 **되돌아갈 수 없는 전환**을 담는다. 마지막 버튼을 누르는 순간 도감에
/// 수록되고 사이클이 초기화되므로, 중간에 닫아도 아무 일도 일어나지 않게 설계했다 —
/// 실제 커밋은 `finish()` 한 곳에서만 일어난다.
struct CeremonyScreen: View {
    let species: BirdSpecies
    let goalLabel: String
    let cycleStartedAt: Date
    /// 이번 사이클 목표 — 다음 목표 추천을 여기서 한 칸 올린다 (이슈 #127)
    let cycleGoal: RaceDistance?
    let cycleGoalSeconds: Int
    /// 설정의 현재 목표 — 사이클 도중 더 높게 바꿔 뒀다면 그 값을 초기 선택으로 우선한다
    let currentGoal: RaceDistance?
    let currentGoalSeconds: Int
    /// "조금 더 키우기" — 수집을 미룬다. 수집 전까지는 더 긴 거리·빠른 기록이 나오면 종이 오른다
    /// (풀코스 대회 전에 XP가 먼저 차도 대회를 기다릴 수 있게)
    let onLater: () -> Void

    /// 수집 확정 — 도감 수록과 사이클 초기화를 호출부(홈)가 실행한다.
    /// 전환 부작용을 화면이 직접 저지르지 않게 하려고 클로저로 올린다.
    /// false(도감 저장 실패)면 닫지 않고 남아 다시 누를 수 있게 한다 (이슈 #67)
    let onFinish: (_ newGoal: RaceDistance?, _ newGoalSeconds: Int) -> Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 세러모니 단계 — 축하를 먼저 보여주고, 이어서 다음 목표를 고르게 한다
    private enum Step { case celebrate, chooseGoal }
    @State private var step: Step = .celebrate
    @State private var appeared = false

    /// 사용자가 고른 다음 목표. 처음에는 추천값으로 채워 두고 바꿀 수 있게 한다
    @State private var pickedDistance: RaceDistance?
    @State private var pickedSeconds = 0

    var body: some View {
        ZStack {
            RR.bg.ignoresSafeArea()
            confetti

            switch step {
            case .celebrate: celebrateBody
            case .chooseGoal: goalBody
            }
        }
        .onAppear {
            // 추천 목표를 초기 선택으로 깔아 둔다 (§5 "직전 목표·최근 기록 기반", 이슈 #127)
            let initial = CollectionEngine.initialNextGoal(cycleGoal: cycleGoal,
                                                           cycleGoalSeconds: cycleGoalSeconds,
                                                           currentGoal: currentGoal,
                                                           currentSeconds: currentGoalSeconds)
            pickedDistance = initial.distance
            pickedSeconds = initial.seconds
            // 모션 줄이기면 튀어나오는 spring 없이 바로 보인다 (이슈 #212)
            withAnimation(reduceMotion ? nil : .spring(response: 0.7, dampingFraction: 0.6)) { appeared = true }
        }
    }

    // MARK: - 1) 축하

    private var celebrateBody: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)

            SpeciesBirdView(species: species)
                .frame(width: 210, height: 210)
                .scaleEffect(appeared ? 1 : 0.6)
                .opacity(appeared ? 1 : 0)

            Text("\(species.label)가 되었어요")
                .font(RR.display(28))
                .foregroundStyle(RR.text)
                .padding(.top, 10)

            Text(goalLabel)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(RR.brand)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(RR.brandSoft, in: Capsule())
                .padding(.top, 10)

            Text("\(cycleDays)일을 함께 달렸습니다.\n이 새는 도감에 남아요.")
                .font(.system(size: 14))
                .foregroundStyle(RR.text2)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.top, 14)

            Spacer(minLength: 20)

            Button {
                withAnimation(.easeInOut(duration: 0.25)) { step = .chooseGoal }
            } label: {
                Text("도감에 넣기")
                    .font(.system(size: 16, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .foregroundStyle(RR.onBrand)
            }
            .padding(.horizontal, 20)

            if let next = species.next {
                Button {
                    onLater()
                    dismiss()
                } label: {
                    VStack(spacing: 3) {
                        Text("조금 더 키우기")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(RR.text)
                        Text("\(next.goalHint) → \(next.label)")
                            .font(.system(size: 11.5))
                            .foregroundStyle(RR.text2)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 20)
                .padding(.top, 6)
            }

            Spacer().frame(height: 24)
        }
    }

    /// 사이클 소요 일수 — 세러모니 문장에만 쓴다
    private var cycleDays: Int {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let days = calendar.dateComponents([.day],
                                            from: calendar.startOfDay(for: cycleStartedAt),
                                            to: calendar.startOfDay(for: Date())).day ?? 0
        return max(0, days)
    }

    // MARK: - 2) 새 목표

    private var goalBody: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 12)

            Eyebrow(text: "다음 목표")
                .padding(.horizontal, 20)
            Text("다음은 어디까지 가 볼까요?")
                .font(RR.display(24))
                .foregroundStyle(RR.text)
                .padding(.horizontal, 20)
                .padding(.top, 6)
            Text("목표 거리를 실제로 달리면 그 새가 돼요.")
                .font(.system(size: 13))
                .foregroundStyle(RR.text2)
                .padding(.horizontal, 20)
                .padding(.top, 4)

            VStack(spacing: 8) {
                goalOption(nil)
                ForEach(RaceDistance.allCases, id: \.self) { goalOption($0) }
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)

            Spacer(minLength: 16)

            Button {
                finish()
            } label: {
                Text("새 알 받기")
                    .font(.system(size: 16, weight: .bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .foregroundStyle(RR.onBrand)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
    }

    /// 목표 후보 한 줄 — 그 목표를 달성하면 될 새 종류를 미리 보여준다
    private func goalOption(_ distance: RaceDistance?) -> some View {
        // 기록 목표는 이 화면에서 받지 않는다(설정에서 정한다). 다만 풀코스를
        // 이어 가는 경우엔 추천 기록을 유지해야 종이 달라지므로 그대로 넘긴다
        let seconds = (distance == .full && pickedDistance == .full) ? pickedSeconds : 0
        let resulting = CollectionEngine.species(for: distance, goalSeconds: seconds)
        let isPicked = distance == pickedDistance

        return Button {
            pickedDistance = distance
            pickedSeconds = seconds
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(distance.map(\.label) ?? "목표 없이 꾸준히")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text)
                    Text(CollectionEngine.goalLabel(for: distance, goalSeconds: seconds))
                        .font(.system(size: 11.5))
                        .foregroundStyle(RR.text2)
                }
                Spacer()
                Text(resulting.label)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(isPicked ? RR.onBrand : RR.text2)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(isPicked ? RR.brand : RR.surface2, in: Capsule())
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .background(RR.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(isPicked ? RR.brand : RR.line, lineWidth: isPicked ? 2 : 1))
        }
        .buttonStyle(.plain)
    }

    private func finish() {
        guard onFinish(pickedDistance, pickedSeconds) else { return }
        dismiss()
    }

    // MARK: - 파티클

    /// 축하 색종이 — TimelineView로 시간을 받아 Canvas 한 장에 찍는다.
    /// 난수는 인덱스 기반 결정론 함수로 만든다(뷰가 다시 그려져도 같은 자리)
    private var confetti: some View {
        // 모션 줄이기면 색종이를 멈춘 한 장면으로 둔다 (이슈 #212)
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let t = timeline.date.timeIntervalSinceReferenceDate
                for i in 0..<Self.confettiCount {
                    let seedX = Self.pseudoRandom(i, salt: 1)
                    let seedSpeed = 0.5 + Self.pseudoRandom(i, salt: 2)
                    let seedSize = 4 + Self.pseudoRandom(i, salt: 3) * 5

                    // 아래로 흐르다 화면 밖에서 되감긴다
                    let cycle = (t * seedSpeed).truncatingRemainder(dividingBy: 1)
                    let y = cycle * (size.height + 40) - 20
                    let sway = sin((t + Double(i)) * 1.6) * 12
                    let x = seedX * size.width + sway

                    let rect = CGRect(x: x, y: y, width: seedSize, height: seedSize * 1.6)
                    context.fill(Path(roundedRect: rect, cornerRadius: 1),
                                  with: .color(Self.confettiColors[i % Self.confettiColors.count]))
                }
            }
            .allowsHitTesting(false)
        }
        .ignoresSafeArea()
    }

    private static let confettiCount = 34
    private static let confettiColors: [Color] = [RR.brand, RR.pos, RR.warn]

    /// 인덱스 기반 유사 난수 0..<1 — `Math.random` 없이 결정론적으로 흩뿌린다
    private static func pseudoRandom(_ index: Int, salt: Int) -> Double {
        let x = sin(Double(index) * 12.9898 + Double(salt) * 78.233) * 43_758.5453
        return x - x.rounded(.down)
    }
}
