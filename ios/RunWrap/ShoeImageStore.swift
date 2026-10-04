import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 러닝화 사진 파일 저장소 (이슈 #206) — Application Support/RunWrap/shoes/<shoe id>-<난수>.jpg.
/// 사진은 긴 변 600px JPEG로 줄여 저장한다(카드·칩 표시에 충분하고 원본 수 MB를 쌓지 않는다).
/// ShoeCache처럼 atomic write + 기기 백업 제외. 기기 밖으로 보내지 않는다
enum ShoeImageStore {
    static let maxPixelSize = 600
    static let folder = "shoes"

    /// 사진 데이터를 줄여 저장하고 파일 이름을 돌려준다. 이미지로 읽히지 않거나 쓰기 실패면 nil.
    /// directory 주입은 테스트용 — 기본은 Application Support/RunWrap/shoes (없으면 만든다)
    /// 저장할 때마다 파일 이름이 달라진다 — 사진을 바꾸면 `Shoe.imageFile`이 바뀌어 화면이 새 사진을 다시 읽는다
    static func save(_ data: Data, for shoeID: UUID, in directory: URL? = nil) -> String? {
        let file = "\(shoeID.uuidString)-\(UUID().uuidString.prefix(8)).jpg"
        guard let dir = folderURL(in: directory),
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // 썸네일 API가 디코드와 축소를 한 번에 하고, EXIF 회전도 픽셀에 반영한다
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
        else { return nil }

        let jpeg = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(jpeg, UTType.jpeg.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(dest, image,
                                   [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }

        var url = dir.appendingPathComponent(file)
        do { try (jpeg as Data).write(to: url, options: .atomic) } catch { return nil }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return file
    }

    /// 저장된 사진 위치 — 파일이 없으면 nil
    static func url(for file: String, in directory: URL? = nil) -> URL? {
        guard let url = folderURL(in: directory)?.appendingPathComponent(file),
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    static func remove(_ file: String, in directory: URL? = nil) {
        guard let url = folderURL(in: directory)?.appendingPathComponent(file) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func folderURL(in directory: URL?) -> URL? {
        let dir: URL
        if let directory {
            dir = directory
        } else {
            guard let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                                      in: .userDomainMask).first else { return nil }
            dir = base.appendingPathComponent("RunWrap", isDirectory: true)
                .appendingPathComponent(folder, isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}
