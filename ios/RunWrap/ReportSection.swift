import SwiftUI

/// 주간 리포트 섹션 — 목록 상단에서 파생 지표 인사이트를 보여준다 (MVP 2단계)
struct ReportSection: View {
    let runs: [RunSummary]
    var engine = ReportEngine()

    var body: some View {
        let insights = engine.insights(from: runs)
        Section {
            if insights.isEmpty {
                Text("파생 지표를 계산하려면 2주 이상, 주 3회 정도의 기록이 필요합니다.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(insights) { InsightRow(insight: $0) }
            }
        } header: {
            Text("주간 리포트")
        } footer: {
            if !insights.isEmpty {
                Text("참고용 추정치입니다 — 산식: 10% 룰 · ACWR(Gabbett) · 심박 효율(EF)")
            }
        }
    }
}

/// 인사이트 한 줄: 톤 아이콘 + 헤드라인 + 수치 근거
struct InsightRow: View {
    let insight: Insight

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(color)
            VStack(alignment: .leading, spacing: 4) {
                Text(insight.headline)
                    .font(.subheadline.weight(.semibold))
                Text(insight.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var icon: String {
        switch insight.kind {
        case .weeklyDistanceChange: "chart.line.uptrend.xyaxis"
        case .acwr: "gauge.with.needle"
        case .heartRateEfficiency: "heart.fill"
        }
    }

    private var color: Color {
        switch insight.tone {
        case .positive: .green
        case .neutral: .secondary
        case .warning: .orange
        }
    }
}

// 시뮬레이터에는 워치 기록이 없으므로 디자인 확인은 합성 데이터 프리뷰로 한다
#Preview("주간 리포트") {
    let now = Date()
    func run(daysAgo: Double, km: Double, minPerKm: Double = 6, hr: Double = 150) -> RunSummary {
        RunSummary(id: UUID(),
                   start: now.addingTimeInterval(-daysAgo * 86_400),
                   durationSec: km * minPerKm * 60,
                   distanceMeters: km * 1000,
                   avgHeartRate: hr)
    }
    let runs = [
        run(daysAgo: 1, km: 10, hr: 143),
        run(daysAgo: 3, km: 8, hr: 145),
        run(daysAgo: 5, km: 7, hr: 144),
        run(daysAgo: 9, km: 6, hr: 149),
        run(daysAgo: 11, km: 5, hr: 151),
        run(daysAgo: 13, km: 5, hr: 150),
        run(daysAgo: 17, km: 6, hr: 152),
        run(daysAgo: 20, km: 5, hr: 153),
        run(daysAgo: 24, km: 6, hr: 152),
    ]
    return List {
        ReportSection(runs: runs, engine: ReportEngine(now: now))
    }
}
