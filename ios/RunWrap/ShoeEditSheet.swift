import PhotosUI
import SwiftUI

/// 러닝화 편집 시트 (이슈 #171) + 사진 슬롯 (이슈 #206).
/// 설정 화면뿐 아니라 홈 카드·러닝 후 질문 시트에서도 같은 시트를 띄우려고 SettingsScreen에서 꺼냈다.
/// 사진은 저장을 누를 때만 파일로 쓴다 — 닫기로 나가면 아무것도 남기지 않는다

// MARK: - 러닝화 사진 (이슈 #206)

/// 등록한 사진이 있으면 둥근 사각형 안에 통째로 보이고, 없거나 못 읽으면 기본 일러스트(ShoeView). 프레임은 호출부가 정한다
struct ShoeImage: View {
    let shoe: Shoe

    var body: some View {
        if let file = shoe.imageFile,
           let url = ShoeImageStore.url(for: file),
           let image = UIImage(contentsOfFile: url.path) {
            ShoePhoto(image: image)
        } else {
            ShoeView()
        }
    }
}

/// 사진을 주어진 프레임 안에 통째로 맞춘다 — ShoeImage와 편집 시트의 새로 고른 사진 미리보기가 같이 쓴다.
/// 러닝화 사진은 대개 가로로 길어서, 꽉 채워 자르면 앞코·뒤꿈치가 잘린다. 남는 위아래는 surface로 채운다
private struct ShoePhoto: View {
    let image: UIImage

    var body: some View {
        RR.surface
            .overlay { Image(uiImage: image).resizable().scaledToFit() }
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

// MARK: - 러닝화 편집 시트 (이슈 #171)

/// 사진·이름·등록 전 누적 거리·교체 기준·기본 지정·은퇴·삭제. 신규 등록에서는 은퇴·삭제를 숨긴다
struct ShoeEditSheet: View {
    let isNew: Bool
    let onSave: (Shoe, Bool) -> Void
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var shoe: Shoe
    @State private var isDefault: Bool
    @State private var confirmsDelete = false
    /// 사진 (이슈 #206) — 고른 사진은 저장 전까지 메모리에만 둔다. clearsPhoto는 기존 사진을 지우기로 했다는 표시
    @State private var photoItem: PhotosPickerItem?
    @State private var pickedData: Data?
    @State private var clearsPhoto = false

    init(shoe: Shoe, isNew: Bool, isDefault: Bool,
         onSave: @escaping (Shoe, Bool) -> Void, onDelete: @escaping () -> Void) {
        self.isNew = isNew
        self.onSave = onSave
        self.onDelete = onDelete
        _shoe = State(initialValue: shoe)
        _isDefault = State(initialValue: isDefault)
    }

    private var trimmedName: String {
        shoe.name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 새로 고른 사진이 있거나, 지우지 않은 기존 사진이 있는가
    private var hasPhoto: Bool {
        pickedData != nil || (!clearsPhoto && shoe.imageFile != nil)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    photoSlot
                    field(title: "이름") {
                        TextField("예: 페가수스 41", text: $shoe.name)
                            .font(.system(size: 15))
                            .textFieldStyle(.plain)
                            .padding(14)
                    }
                    field(title: "거리") {
                        stepperRow(title: "등록 전 누적 \(Int(shoe.startKm)) km",
                                   caption: "앱에 등록하기 전에 이미 달린 거리예요",
                                   value: $shoe.startKm, range: 0...2_000, step: 10)
                        Divider().overlay(RR.line)
                        stepperRow(title: "교체 기준 \(Int(shoe.replaceKm)) km",
                                   caption: "누적이 이 거리를 넘으면 홈에서 알려드려요",
                                   value: $shoe.replaceKm, range: 300...1_200, step: 50)
                    }
                    field(title: "상태") {
                        toggleRow(label: "기본 신발로 지정",
                                  caption: "새 러닝이 자동으로 이 신발에 기록돼요",
                                  isOn: $isDefault)
                            .disabled(shoe.isRetired)
                            .opacity(shoe.isRetired ? 0.45 : 1)
                        if !isNew {
                            Divider().overlay(RR.line)
                            toggleRow(label: "은퇴",
                                      caption: "목록 끝으로 옮기고 러닝 배정 후보에서 빼요",
                                      isOn: $shoe.isRetired)
                        }
                    }
                    if !isNew {
                        Button(role: .destructive) { confirmsDelete = true } label: {
                            Text("러닝화 삭제")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(RR.dang)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                        .rrCard()
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 12)
                .padding(.bottom, 26)
            }
            .background(RR.bg.ignoresSafeArea())
            .navigationTitle(isNew ? "러닝화 추가" : "러닝화 편집")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: shoe.isRetired) { _, retired in
                // 은퇴한 신발은 자동 배정 대상이 아니다 — 기본 지정을 함께 푼다
                if retired { isDefault = false }
            }
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        pickedData = data
                        clearsPhoto = false
                    }
                    photoItem = nil
                }
            }
            .confirmationDialog("이 러닝화를 삭제할까요?", isPresented: $confirmsDelete, titleVisibility: .visible) {
                Button("삭제", role: .destructive) {
                    onDelete()
                    dismiss()
                }
                Button("취소", role: .cancel) {}
            } message: {
                Text("배정된 러닝은 '없음'으로 바뀌어요. 그만 신는다면 은퇴가 기록을 남겨요.")
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") {
                        var saved = shoe
                        saved.name = trimmedName
                        // 사진은 저장할 때만 파일에 반영한다 (이슈 #206) — 축소 실패면 기존 사진을 그대로 둔다
                        if let pickedData, let file = ShoeImageStore.save(pickedData, for: saved.id) {
                            if let old = saved.imageFile { ShoeImageStore.remove(old) }
                            saved.imageFile = file
                        } else if clearsPhoto, let file = saved.imageFile {
                            ShoeImageStore.remove(file)
                            saved.imageFile = nil
                        }
                        onSave(saved, isDefault && !saved.isRetired)
                        dismiss()
                    }
                    .disabled(trimmedName.isEmpty || photoItem != nil)   // 사진을 읽는 중에는 저장을 막는다
                }
            }
        }
    }

    /// 사진 슬롯 (이슈 #206) — 고른 사진 > 기존 사진 > 기본 일러스트 순. 탭하면 사진 앱 선택(권한 불필요)
    private var photoSlot: some View {
        VStack(spacing: 10) {
            PhotosPicker(selection: $photoItem, matching: .images) {
                Group {
                    if let pickedData, let image = UIImage(data: pickedData) {
                        ShoePhoto(image: image)
                    } else if clearsPhoto {
                        ShoeView()
                    } else {
                        ShoeImage(shoe: shoe)
                    }
                }
                .frame(width: 120, height: 120)
            }
            .buttonStyle(.plain)
            HStack(spacing: 10) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Text(hasPhoto ? "사진 바꾸기" : "사진 선택")
                        .font(.system(size: 13, weight: .semibold))
                }
                .buttonStyle(.bordered)
                .tint(RR.brand)
                if hasPhoto {
                    Button {
                        pickedData = nil
                        clearsPhoto = true
                    } label: {
                        Text("사진 지우기")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(RR.text2)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func stepperRow(title: String, caption: String, value: Binding<Double>,
                            range: ClosedRange<Double>, step: Double) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(RR.text)
                Text(caption)
                    .font(.system(size: 12.5))
                    .foregroundStyle(RR.text2)
            }
            Spacer(minLength: 8)
            Stepper("", value: value, in: range, step: step)
                .labelsHidden()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private func toggleRow(label: String, caption: String, isOn: Binding<Bool>) -> some View {
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
            Toggle(label, isOn: isOn)
                .labelsHidden()
                .tint(RR.brand)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private func field(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(RR.text2)
                .padding(.horizontal, 4)
            VStack(spacing: 0) { content() }
                .rrCard()
        }
    }
}
