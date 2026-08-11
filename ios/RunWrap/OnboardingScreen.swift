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
