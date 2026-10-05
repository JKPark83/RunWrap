import SwiftUI

/// 월간·연간 결산 리캡 시트 (이슈 #167) — 홈 결산 카드·리포트 '이번달'의 결산 버튼이 연다.
///
/// 세로 페이지 넘김이 아니라 스크롤 카드 나열이다. 내용은 전부 `RecapEngine`이 정하고
/// 여기서는 그린다 — 표본이 없는 카드는 엔진이 nil·빈 배열로 내므로 자리조차 만들지 않는다.
/// 하단 "이미지로 저장"은 세션 공유 카드와 같은 렌더·저장 경로(ShareCardRenderer)를 쓴다.
struct RecapScreen: View {
    let period: RecapPeriod

    @EnvironmentObject private var health: HealthStore
    @AppStorage(ProfileKey.levelV2) private var levelRaw = RunnerLevel.beginner.rawValue
    // 심박 기준 (이슈 #56) — 강도 배분 카드의 존 경계. 해석은 리포트 탭과 같은 엔진 한 곳
    @AppStorage(ProfileKey.hrMaxManual) private var hrMaxManual = 0
    @AppStorage(ProfileKey.restingHRManual) private var restingHRManual = 0
    @AppStorage(ProfileKey.hrZoneMethod) private var hrZoneMethodRaw = ""
    @Environment(\.dismiss) private var dismiss
    /// 공유 이미지가 화면과 같은 모드로 그려지도록 명시적으로 주입한다 (ShareSheetView와 같다)
    @Environment(\.colorScheme) private var colorScheme

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    @State private var saveMessage: String?

    private var heartRate: HeartRateProfile {
        TrainingGuideEngine.heartRateProfile(estimate: health.hrMaxEstimate,
                                             manualHrMax: hrMaxManual,
                                             manualRestingHR: restingHRManual,
                                             measuredRestingHR: health.restingHRBpm,
                                             zoneMethodRaw: hrZoneMethodRaw)
    }

    private var recap: Recap? {
        guard case .loaded(let runs) = health.state else { return nil }
        return RecapEngine.compute(period: period, runs: runs,
                                   efforts: health.bestEfforts,
                                   histograms: health.zoneHistograms,
                                   profile: heartRate,
                                   level: RunnerLevel(rawValue: levelRaw) ?? .beginner,
                                   now: Date())
    }

    private var isMonth: Bool {
        if case .month = period { true } else { false }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header
                if let recap {
                    content(recap)
                } else {
                    Text("기록 3회 이상이면 결산이 열립니다")
                        .font(.system(size: 13.5))
                        .foregroundStyle(RR.text3)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 28)
                        .rrCard()
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 22)
            .padding(.bottom, 26)
        }
        .background(RR.bg.ignoresSafeArea())
        .presentationDragIndicator(.visible)
        .onAppear { appeared = true }
    }

    // MARK: 헤더

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 7) {
                Eyebrow(text: isMonth ? "월간 결산" : "연간 결산")
                Text(recap?.title ?? RecapEngine.periodLabel(period) + " 결산")
                    .font(RR.display(30))
                    .foregroundStyle(RR.text)
            }
            Spacer()
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.text2)
                    .frame(width: 34, height: 34)
                    .background(RR.surface, in: Circle())
                    .overlay(Circle().strokeBorder(RR.line))
                    .rrTapTarget()
            }
            .buttonStyle(.plain)  // 기본 스타일은 라벨 밖으로 넓힌 탭 영역을 받지 않는다 (이슈 #212)
            .accessibilityLabel("닫기")
        }
        .padding(.bottom, 6)
    }

    // MARK: 카드 나열 — 순서 고정 (총량 → 하이라이트 → 새 기록 → 강도 배분 → 마무리)

    @ViewBuilder
    private func content(_ recap: Recap) -> some View {
        // 모션 줄이기면 순차 등장 없이 처음부터 보인다 (이슈 #212)
        let shown = appeared || reduceMotion
        totalsCard(recap).reveal(0, shown)
        if let highlights = recap.highlights {
            highlightsCard(highlights).reveal(1, shown)
        }
        if !recap.records.isEmpty {
            recordsCard(recap.records).reveal(2, shown)
        }
        if let intensity = recap.intensity {
            intensityCard(intensity).reveal(3, shown)
        }
        closingCard(recap.closingLine).reveal(4, shown)
        saveButton(recap)
            .padding(.top, 8)
            .reveal(5, shown)
    }

    private func cardLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundStyle(RR.text3)
    }

    private func totalsCard(_ recap: Recap) -> some View {
        let totals = recap.totals
        return VStack(alignment: .leading, spacing: 14) {
            cardLabel("총량")
            HStack(alignment: .bottom) {
                // 런린이는 거리 대신 횟수를 크게 (ReportGate §4 "문장만")
                if totals.showsDistance {
                    bigNumber(Format.km(totals.distanceKm), unit: "km")
                } else {
                    bigNumber("\(totals.count)", unit: "회")
                }
                Spacer()
                if totals.showsDistance, let delta = recap.deltaPct {
                    Text(String(format: "%@ %@%.0f%%", isMonth ? "지난달 대비" : "지난해 대비",
                                delta >= 0 ? "+" : "−", abs(delta)))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(delta >= 0 ? RR.pos : RR.text2)
                }
            }
            HStack(spacing: 0) {
                if totals.showsDistance {
                    stat("러닝 횟수", "\(totals.count)", "회")
                }
                stat("총 시간", Format.duration(totals.durationSec), "h:m:s")
            }
        }
        .padding(EdgeInsets(top: 18, leading: 18, bottom: 18, trailing: 18))
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func bigNumber(_ value: String, unit: String) -> some View {
        Text(value)
            .font(RR.numeral(52))
            .foregroundStyle(RR.text)
            + Text(" \(unit)")
            .font(.system(size: 15))
            .foregroundStyle(RR.text3)
    }

    private func stat(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
            (Text(value).font(.system(size: 20, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                + Text(" \(unit)")
                .font(.system(size: 11))
                .foregroundStyle(RR.text3))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func highlightsCard(_ highlights: Recap.Highlights) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            cardLabel("하이라이트")
            if let longest = highlights.longest, let km = longest.distanceKm {
                highlightRow(icon: "road.lanes", title: "최장 런",
                             date: longest.start, value: "\(Format.km(km)) km")
            }
            if let fastest = highlights.fastest, let pace = fastest.paceSecPerKm {
                highlightRow(icon: "bolt.fill", title: "가장 빠른 세션",
                             date: fastest.start, value: Format.paceKm(pace))
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func highlightRow(icon: String, title: String, date: Date, value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(RR.brand)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text(RecapFormat.day(date))
                    .font(.system(size: 11.5))
                    .foregroundStyle(RR.text3)
            }
            Spacer(minLength: 8)
            Text(value)
                .font(.system(size: 16, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
        }
    }

    private func recordsCard(_ records: [PersonalRecords.Entry]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            cardLabel("새 기록")
            ForEach(records, id: \.label) { entry in
                HStack(spacing: 12) {
                    Image(systemName: "medal.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(RR.medalColor(forPB: entry.label))
                        .frame(width: 24)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.label)
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(RR.text)
                        Text(RecapFormat.day(entry.date))
                            .font(.system(size: 11.5))
                            .foregroundStyle(RR.text3)
                    }
                    Spacer(minLength: 8)
                    Text(Format.duration(entry.timeSec))
                        .font(.system(size: 16, weight: .bold, design: .monospaced))
                        .foregroundStyle(RR.brand)
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func intensityCard(_ intensity: Recap.Intensity) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ToneBadge(tone: intensity.tone)
            cardLabel("강도 배분 · 이지(Z1~Z2) 비율")
            (Text("\(Int((intensity.easyShare * 100).rounded()))")
                .font(RR.numeral(40))
                .foregroundStyle(RR.text)
                + Text(" %")
                .font(.system(size: 14))
                .foregroundStyle(RR.text3))
            Text("심박 기록 \(intensity.sessions)회 기준 · 80% 이상이면 80/20 원칙에 맞아요")
                .font(.system(size: 11.5))
                .foregroundStyle(RR.text3)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .rrCard()
    }

    private func closingCard(_ line: String) -> some View {
        Text(line)
            .font(.system(size: 15, weight: .semibold))
            .lineSpacing(15 * 0.5)
            .foregroundStyle(RR.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .rrCard()
    }

    // MARK: 이미지 저장 — SessionDetailScreen 공유 시트와 같은 저장 안내(아래 캡션)

    private func saveButton(_ recap: Recap) -> some View {
        VStack(spacing: 8) {
            Button {
                Task { await save(recap) }
            } label: {
                Label("이미지로 저장", systemImage: "square.and.arrow.down")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(RR.onBrand)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(RR.brand, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)

            if let saveMessage {
                Text(saveMessage)
                    .font(.system(size: 12))
                    .foregroundStyle(RR.text3)
            }
        }
    }

    private func save(_ recap: Recap) async {
        let card = RecapShareCardView(recap: recap)
            .environment(\.colorScheme, colorScheme)
        guard let image = ShareCardRenderer.render(card) else {
            saveMessage = "이미지를 만들지 못했어요 — 잠시 후 다시 시도해 주세요"
            return
        }
        do {
            try await ShareCardRenderer.saveToPhotos(image)
            saveMessage = "사진 앱에 저장했어요"
        } catch {
            saveMessage = "저장하지 못했어요 — 설정에서 사진 추가 권한을 확인해 주세요"
        }
    }
}

// MARK: - 카드 등장 애니메이션

private extension View {
    /// 카드 등장 — 순서대로 살짝 늦게 fade + slide up
    func reveal(_ index: Int, _ appeared: Bool) -> some View {
        opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 14)
            .animation(.easeOut(duration: 0.35).delay(Double(index) * 0.06), value: appeared)
    }
}

/// "8월 14일 (목)" — 결산 화면·공유 카드의 세션 날짜
private enum RecapFormat {
    static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ko_KR")
        formatter.dateFormat = "M월 d일 (E)"
        return formatter.string(from: date)
    }
}

// MARK: - 공유 카드

/// 결산 스토리 카드 — ShareCardView와 같은 골격(360×640, 아이브로 → 큰 숫자 → 수치 줄 → 하단 브랜드 줄)
struct RecapShareCardView: View {
    let recap: Recap

    private var isMonth: Bool {
        if case .month = recap.period { true } else { false }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: "런미새 · " + recap.periodLabel)

            Text(recap.title)
                .font(RR.display(26))
                .foregroundStyle(RR.text)
                .padding(.top, 10)

            // 런린이는 거리 대신 횟수를 크게 — 결산 화면과 같은 규칙
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text(recap.totals.showsDistance ? Format.km(recap.totals.distanceKm) : "\(recap.totals.count)")
                    .font(.system(size: 62, weight: .heavy, design: .monospaced))
                    .foregroundStyle(RR.text)
                Text(recap.totals.showsDistance ? "km" : "회")
                    .font(.system(size: 20, weight: .bold, design: .monospaced))
                    .foregroundStyle(RR.text3)
            }
            .padding(.top, 14)

            HStack(spacing: 0) {
                if recap.totals.showsDistance {
                    stat("러닝 횟수", "\(recap.totals.count)", "회")
                }
                stat("총 시간", Format.duration(recap.totals.durationSec), "h:m:s")
            }
            .padding(.top, 18)

            VStack(alignment: .leading, spacing: 10) {
                if let longest = recap.highlights?.longest, let km = longest.distanceKm {
                    line("최장 런", "\(Format.km(km)) km · \(RecapFormat.day(longest.start))")
                }
                if let fastest = recap.highlights?.fastest, let pace = fastest.paceSecPerKm {
                    line("가장 빠른 세션", Format.paceKm(pace))
                }
                // 연간은 기록이 4~5개까지 쌓일 수 있어 한 줄에 넣지 않고 기록마다 한 줄씩
                ForEach(Array(recap.records.enumerated()), id: \.offset) { i, record in
                    line(i == 0 ? "새 기록" : "", "\(record.label) \(Format.duration(record.timeSec))")
                }
            }
            .padding(.top, 24)

            Text(recap.closingLine)
                .font(.system(size: 14, weight: .semibold))
                .lineSpacing(14 * 0.45)
                .foregroundStyle(RR.text)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RR.surface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.top, 24)

            Spacer(minLength: 0)

            Divider().overlay(RR.line)

            HStack {
                Text(isMonth ? "MONTHLY RECAP" : "YEARLY RECAP")
                    .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    .foregroundStyle(RR.text2)
                Spacer()
                Text("런미새")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(RR.brand)
            }
            .padding(.top, 16)
        }
        .padding(28)
        .frame(width: 360, height: 640)
        .background(RR.bg)
    }

    private func stat(_ label: String, _ value: String, _ unit: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(RR.text3)
            Text(value)
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(unit)
                .font(.system(size: 10.5, design: .monospaced))
                .foregroundStyle(RR.text3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func line(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(RR.text3)
            Spacer(minLength: 10)
            Text(value)
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(RR.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
    }
}
