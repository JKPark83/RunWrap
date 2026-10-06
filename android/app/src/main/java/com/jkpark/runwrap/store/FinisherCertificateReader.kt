package com.jkpark.runwrap.store

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.jkpark.runwrap.engine.FinisherCertificateReader
import com.jkpark.runwrap.engine.RaceResultParser
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// 완주증 OCR (이슈 #192)의 텍스트 인식 부분 — 필드 해석(`interpret`)은 엔진 `FinisherCertificateReader`에 있다.
// (Android: Vision 대신 ML Kit 한국어 텍스트 인식. 모델이 앱에 번들된 온디바이스 인식이라 이미지가 기기 밖으로
//  나가지 않고, 이미지는 저장하지도 않는다. 사진 선택·촬영 결과가 모두 Uri라 이미지 대신 Uri를 받는다)

/// 텍스트 인식 → 위→아래 순서의 줄 문자열.
/// (Android: ML Kit은 언어 교정 옵션이 없다. 블록 순서가 위→아래를 보장하지 않아 모든 줄을 모아
///  boundingBox 위쪽 → 왼쪽 순으로 정렬한다 — Vision의 maxY 내림차순과 같은 위→아래 순서다.
///  인식은 ML Kit이 자체 스레드에서 돌리고 결과 콜백을 기다린다)
suspend fun FinisherCertificateReader.recognizeLines(context: Context, image: Uri): List<String> {
    val input = InputImage.fromFilePath(context, image)   // EXIF 회전을 반영한다
    val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    try {
        val text = suspendCancellableCoroutine<Text> { continuation ->
            recognizer.process(input)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        return text.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .map { it.text }
    } finally {
        recognizer.close()
    }
}

/// 편의: 이미지 → 프리필. 오류는 nil로 삼킨다 — 화면은 조용히 수동 입력으로 넘긴다
suspend fun FinisherCertificateReader.read(
    context: Context,
    image: Uri,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): RaceResultParser.Parsed? {
    val lines = try {
        recognizeLines(context, image)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    return interpret(lines, now, zone)
}
