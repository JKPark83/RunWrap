import Foundation

/// 설정 '정보' 섹션의 피드백 메일 (이슈 #185) — 메일 앱을 여는 `mailto:` URL을 만든다.
///
/// 본문에는 문제 재현에 필요한 앱 버전·iOS 버전·기기 모델만 넣는다.
/// 건강 데이터는 절대 싣지 않는다 — 외부로 나가는 건 사용자가 직접 보내는 메일뿐이다.
enum FeedbackMail {
    static let address = "sanaigon@gmail.com"
    static let subject = "[런미새] 피드백"

    /// 퍼센트 인코딩은 URLComponents(queryItems)에 맡긴다 — 한글·공백·줄바꿈을 직접 치환하지 않는다
    static func url(appVersion: String, build: String, systemVersion: String, deviceModel: String) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = address
        components.queryItems = [
            URLQueryItem(name: "subject", value: subject),
            URLQueryItem(name: "body", value: body(appVersion: appVersion, build: build,
                                                   systemVersion: systemVersion, deviceModel: deviceModel)),
        ]
        return components.url
    }

    /// "앱 1.3 (4) / iOS 17.5 / iPhone16,1" + 빈 줄 + 구분선 안내
    static func body(appVersion: String, build: String, systemVersion: String, deviceModel: String) -> String {
        """
        앱 \(appVersion) (\(build)) / iOS \(systemVersion) / \(deviceModel)

        ----
        위 정보는 문제 확인에만 써요. 이 아래에 의견이나 겪은 문제를 적어 주세요.

        """
    }

    /// "17.5" · "17.5.1" — patch가 0이면 뺀다 (설정 앱의 표기와 같다)
    static func systemVersion(_ version: OperatingSystemVersion) -> String {
        let base = "\(version.majorVersion).\(version.minorVersion)"
        return version.patchVersion > 0 ? "\(base).\(version.patchVersion)" : base
    }

    /// 기기 모델 식별자("iPhone16,1") — UIKit 없이 uname(hw.machine)으로 읽는다. 시뮬레이터는 "arm64"
    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}
