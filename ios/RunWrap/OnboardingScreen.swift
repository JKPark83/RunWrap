import SwiftUI

/// 첫 실행 — HealthKit 권한 요청 전 안내 (시안 "첫 실행 · 권한 요청")
struct OnboardingScreen: View {
    @EnvironmentObject private var health: HealthStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 40)

            RoundedRectangle(cornerRadius: 19, style: .continuous)
                .fill(RR.brand)
                .frame(width: 64, height: 64)
                .overlay {
                    Image(systemName: "figure.run")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .shadow(color: RR.brand.opacity(0.28), radius: 12, y: 10)

            Text("손목에 쌓인 기록을,\n해석해 드립니다")
                .font(.system(size: 30, weight: .bold))
                .lineSpacing(4)
                .foregroundStyle(RR.text)
                .padding(.top, 26)

            Text("숫자를 나열하지 않습니다. 이번 주 훈련이 무리였는지, 몸이 나아지고 있는지 문장으로 알려드립니다.")
                .font(.system(size: 15))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
                .padding(.top, 12)

            VStack(alignment: .leading, spacing: 16) {
                feature(icon: "heart.fill",
                        title: "건강 앱의 러닝 기록을 읽습니다",
                        sub: "거리, 페이스, 심박, 케이던스, 경로")
                feature(icon: "lock.fill",
                        title: "모든 계산은 iPhone 안에서",
                        sub: "서버로 전송되는 데이터가 없습니다")
                feature(icon: "chart.bar.fill",
                        title: "매주 월요일 아침 리포트",
                        sub: "4주치가 쌓이면 부하 지표까지 계산합니다")
            }
            .padding(.top, 34)

            Spacer(minLength: 24)

            Button {
                Task { await health.connect() }
            } label: {
                Text("건강 데이터 연결")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }

            Text("읽기 전용 권한만 요청하며, 언제든 건강 앱에서 해제할 수 있습니다.")
                .font(.system(size: 12))
                .foregroundStyle(RR.text3)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
                .padding(.top, 14)
                .padding(.bottom, 10)
        }
        .padding(.horizontal, 26)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(RR.bg.ignoresSafeArea())
    }

    private func feature(icon: String, title: String, sub: String) -> some View {
        HStack(alignment: .top, spacing: 13) {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(RR.brandSoft)
                .frame(width: 30, height: 30)
                .overlay {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(RR.brand)
                }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14.5, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text(sub)
                    .font(.system(size: 13))
                    .foregroundStyle(RR.text2)
            }
        }
    }
}

/// 온보딩 2단계 — 권한 요청 뒤 목적·레벨 선택 (계획서 M2).
/// @AppStorage 대신 @State + 명시적 저장을 쓴다: "시작하기"를 누르기 전에는
/// 프로필이 확정되지 않은 상태라 저장소에 흘리지 않는다.
struct ProfileSetupScreen: View {
    @State private var goal: RunGoal = .training
    @State private var level: RunLevel = .beginner  // 온보딩 기본은 초보 — 쉬운 문장부터
    @AppStorage(ProfileKey.didSetProfile) private var didSetProfile = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 30)

            Eyebrow(text: "Profile")
            Text("어떤 러닝을 하시나요?")
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(RR.text)
                .padding(.top, 8)
            Text("리포트 카드의 구성과 문장 톤을 여기에 맞춰 드려요. 설정에서 언제든 바꿀 수 있습니다.")
                .font(.system(size: 14))
                .lineSpacing(4)
                .foregroundStyle(RR.text2)
                .padding(.top, 10)

            optionGroup(title: "러닝 목적") {
                ForEach(RunGoal.allCases, id: \.self) { item in
                    optionCard(label: item.label, caption: item.caption,
                               isSelected: goal == item) { goal = item }
                }
            }
            .padding(.top, 26)

            optionGroup(title: "러닝 레벨") {
                ForEach(RunLevel.allCases, id: \.self) { item in
                    optionCard(label: item.label, caption: item.caption,
                               isSelected: level == item) { level = item }
                }
            }
            .padding(.top, 18)

            Spacer(minLength: 24)

            Button {
                UserDefaults.standard.set(goal.rawValue, forKey: ProfileKey.goal)
                UserDefaults.standard.set(level.rawValue, forKey: ProfileKey.level)
                didSetProfile = true
            } label: {
                Text("시작하기")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .padding(.bottom, 12)
        }
        .padding(.horizontal, 26)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(RR.bg.ignoresSafeArea())
    }

    private func optionGroup(title: String, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(RR.text2)
            rows()
        }
    }

    private func optionCard(label: String, caption: String,
                            isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(label)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(RR.text)
                    Text(caption)
                        .font(.system(size: 12.5))
                        .foregroundStyle(RR.text2)
                }
                Spacer(minLength: 8)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(isSelected ? RR.brand : RR.text3.opacity(0.5))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(RR.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .strokeBorder(isSelected ? RR.brand : RR.line, lineWidth: isSelected ? 1.5 : 1))
    }
}
