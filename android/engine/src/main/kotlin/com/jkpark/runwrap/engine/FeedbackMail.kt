package com.jkpark.runwrap.engine

import java.net.URI

/// 설정 '정보' 섹션의 피드백 메일 (이슈 #185) — 메일 앱을 여는 `mailto:` URL을 만든다.
///
/// 본문에는 문제 재현에 필요한 앱 버전·iOS 버전·기기 모델만 넣는다.
/// 건강 데이터는 절대 싣지 않는다 — 외부로 나가는 건 사용자가 직접 보내는 메일뿐이다.
/// (Android: `systemVersion`·`deviceModel`은 기기 API라 :app이 맡는다)
object FeedbackMail {
    const val address = "sanaigon@gmail.com"
    const val subject = "[런미새] 피드백"

    /// 퍼센트 인코딩은 URLComponents(queryItems)에 맡긴다 — 한글·공백·줄바꿈을 직접 치환하지 않는다
    /// (Android: URLComponents 대응이 없어 쿼리 값 인코딩을 직접 한다. `URLEncoder`는 공백을 `+`로 바꿔 쓰지 않는다)
    fun url(appVersion: String, build: String, systemVersion: String, deviceModel: String): URI {
        val items = listOf(
            "subject" to subject,
            "body" to body(appVersion = appVersion, build = build,
                           systemVersion = systemVersion, deviceModel = deviceModel),
        )
        val query = items.joinToString("&") { (name, value) -> "${encodeQueryItem(name)}=${encodeQueryItem(value)}" }
        return URI("mailto:$address?$query")
    }

    /// "앱 1.3 (4) / iOS 17.5 / iPhone16,1" + 빈 줄 + 구분선 안내
    fun body(appVersion: String, build: String, systemVersion: String, deviceModel: String): String =
        "앱 $appVersion ($build) / iOS $systemVersion / $deviceModel\n" +
            "\n" +
            "----\n" +
            "위 정보는 문제 확인에만 써요. 이 아래에 의견이나 겪은 문제를 적어 주세요.\n"

    /// URLComponents.queryItems가 이름·값에 남기는 문자 — 영숫자와 `-._~!$'()*+,;:@/?`
    /// (`&`·`=`·`#`·`%` 등은 인코딩한다. Swift 실측, 2026-10)
    private const val queryItemAllowed = "-._~!$'()*+,;:@/?"

    private fun encodeQueryItem(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in queryItemAllowed) append(c)
            else append('%').append(String.format(java.util.Locale.ROOT, "%02X", byte.toInt() and 0xFF))
        }
    }
}
