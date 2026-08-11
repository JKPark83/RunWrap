import SwiftUI

/// MVP 1단계 — 권한 요청 → 최근 러닝 목록 (기획서 §8, 완료 기준: 실기기 목록 표시)
struct WorkoutListScreen: View {
    @StateObject private var health = HealthStore()

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("최근 러닝")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch health.state {
        case .idle:
            connectPrompt
        case .loading:
            ProgressView("불러오는 중…")
        case .unavailable:
            ContentUnavailableView("이 기기는 건강 데이터를 지원하지 않습니다",
                                   systemImage: "heart.slash")
        case .failed(let message):
            ContentUnavailableView {
                Label("불러오지 못했습니다", systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button("다시 시도") { Task { await health.load() } }
            }
        case .loaded(let runs) where runs.isEmpty:
            // 읽기 권한 거부 여부는 조회가 불가능해(HealthStore 참조) 빈 목록과 구분할 수 없다
            ContentUnavailableView {
                Label("러닝 기록이 없습니다", systemImage: "figure.run")
            } description: {
                Text("애플워치로 기록한 러닝이 없거나,\n설정 > 건강 > 데이터 접근 및 기기에서 읽기 권한이 꺼져 있습니다.")
            } actions: {
                Button("다시 확인") { Task { await health.load() } }
            }
        case .loaded(let runs):
            List {
                ReportSection(runs: runs)
                Section("최근 러닝") {
                    ForEach(runs) { RunRow(run: $0) }
                }
            }
            .refreshable { await health.load() }
        }
    }

    private var connectPrompt: some View {
        VStack(spacing: 16) {
            Image(systemName: "figure.run.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.tint)
            Text("애플워치 러닝 기록을 불러옵니다")
                .font(.headline)
            Text("건강 데이터는 읽기만 하며 기기 밖으로 나가지 않습니다.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("건강 데이터 연결") { Task { await health.connect() } }
                .buttonStyle(.borderedProminent)
        }
        .padding()
    }
}

/// 목록 한 줄: 날짜 · 거리 · 시간 · 페이스 · 평균 심박
struct RunRow: View {
    let run: RunSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(run.start.formatted(date: .abbreviated, time: .shortened))
                .font(.caption)
                .foregroundStyle(.secondary)
            // 단일 Text로 합쳐야 좁은 화면·큰 글자에서 말줄임 없이 줄 전체가 균일하게 축소된다
            stats
                .font(.subheadline.monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .padding(.vertical, 2)
    }

    private var stats: Text {
        var parts: [Text] = []
        if let km = run.distanceKm {
            parts.append(stat(String(format: "%.2f km", km), icon: "road.lanes"))
        }
        parts.append(stat(Self.duration(run.durationSec), icon: "stopwatch"))
        if let pace = run.paceSecPerKm {
            parts.append(stat(Self.pace(pace), icon: "speedometer"))
        }
        if let bpm = run.avgHeartRate {
            parts.append(stat("\(Int(bpm.rounded()))", icon: "heart.fill"))
        }
        var line = Text(verbatim: "")
        for (i, part) in parts.enumerated() {
            line = i == 0 ? part : Text("\(line)   \(part)")
        }
        return line
    }

    private func stat(_ text: String, icon: String) -> Text {
        let icon = Text(Image(systemName: icon)).foregroundStyle(.tint)
        return Text("\(icon) \(text)")
    }

    static func duration(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }

    static func pace(_ secPerKm: Double) -> String {
        let s = Int(secPerKm.rounded())
        return String(format: "%d'%02d\"", s / 60, s % 60)
    }
}

#Preview {
    WorkoutListScreen()
}
