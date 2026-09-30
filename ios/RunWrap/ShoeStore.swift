import Foundation

/// 러닝화 소유 스토어 (이슈 #171) — 설정이 등록·편집하고, 세션 상세가 배정을 바꾸고, 홈이 교체 안내에 쓴다.
/// 세 화면이 공유해야 해서 RootView가 쥐고 environmentObject로 내린다 (RaceRecordStore와 같은 구조).
/// 변경마다 shoes.json에 바로 저장한다. iCloud 진행도 백업에는 넣지 않는다
@MainActor
final class ShoeStore: ObservableObject {
    @Published private(set) var shoes: [Shoe] = []
    @Published private(set) var defaultShoeID: UUID?
    /// 세션 id(uuidString) → 신발 id. "없음"은 `ShoeEngine.noShoeID`
    @Published private(set) var assignments: [String: UUID] = [:]

    init() {
        if let file = ShoeCache.load() {
            shoes = file.shoes
            defaultShoeID = file.defaultShoeID
            assignments = file.assignments
            return
        }
        #if targetEnvironment(simulator)
        // 시뮬레이터 데모 — 워치 기록이 없어 DemoData 러닝이 자동 배정된다. 등록을 28일 전으로 두면
        // 최근 4주 고정 세션(1~26일 전, 71.6km)이 붙어 540 + 71.6 ≈ 612km로 교체 카드가 보인다
        let demo = Shoe(name: "페가수스 41", startKm: 540,
                        createdAt: Date().addingTimeInterval(-28 * 86_400))
        shoes = [demo]
        defaultShoeID = demo.id
        save()
        #endif
    }

    /// 새 신발 등록 — 첫 켤레는 기본 신발 지정 여부를 시트가 정한다
    func add(_ shoe: Shoe) {
        shoes.append(shoe)
        save()
    }

    /// 편집 결과 반영 — 은퇴한 신발이 기본이면 기본 지정을 푼다(자동 배정 대상이 아니다)
    func update(_ shoe: Shoe) {
        guard let index = shoes.firstIndex(where: { $0.id == shoe.id }) else { return }
        shoes[index] = shoe
        if shoe.isRetired, defaultShoeID == shoe.id { defaultShoeID = nil }
        save()
    }

    func retire(_ shoe: Shoe) {
        var retired = shoe
        retired.isRetired = true
        update(retired)
    }

    /// 삭제 — 이 신발에 배정된 세션은 "없음"으로 남긴다. 키를 지우면 다음 자동 배정이
    /// 기본 신발로 다시 채워, 다른 신발 거리로 잘못 넘어간다
    func remove(_ shoe: Shoe) {
        shoes.removeAll { $0.id == shoe.id }
        if defaultShoeID == shoe.id { defaultShoeID = nil }
        for (runID, shoeID) in assignments where shoeID == shoe.id {
            assignments[runID] = ShoeEngine.noShoeID
        }
        save()
    }

    func setDefault(_ id: UUID?) {
        guard defaultShoeID != id else { return }
        defaultShoeID = id
        save()
    }

    /// 세션별 신발 변경 — nil은 "없음"(표식으로 남겨 자동 배정이 되살리지 않게 한다)
    func assign(runID: UUID, shoeID: UUID?) {
        assignments[runID.uuidString] = shoeID ?? ShoeEngine.noShoeID
        save()
    }

    /// 러닝 목록이 로드될 때 1회 — 배정 없는 세션을 기본 신발(등록 이후 세션만)로 채우고, 바뀌었을 때만 저장
    func syncAssignments(runs: [RunSummary]) {
        let since = shoes.first { $0.id == defaultShoeID }?.createdAt
        let updated = ShoeEngine.autoAssign(runs: runs, defaultShoeID: defaultShoeID,
                                            assignments: assignments, since: since)
        guard updated != assignments else { return }
        assignments = updated
        save()
    }

    func mileage(of shoe: Shoe, runs: [RunSummary]) -> Double {
        ShoeEngine.mileageKm(shoe: shoe, runs: runs, assignments: assignments)
    }

    /// 세션에 배정된 신발 — 없음 표식·삭제된 신발이면 nil
    func shoe(forRun runID: UUID) -> Shoe? {
        guard let id = assignments[runID.uuidString] else { return nil }
        return shoes.first { $0.id == id }
    }

    private func save() {
        ShoeCache.save(ShoeFile(schemaVersion: ShoeFile.currentSchemaVersion, shoes: shoes,
                                defaultShoeID: defaultShoeID, assignments: assignments))
    }
}
