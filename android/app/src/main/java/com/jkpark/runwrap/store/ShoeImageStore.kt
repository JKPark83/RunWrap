package com.jkpark.runwrap.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.jkpark.runwrap.engine.swiftRoundedInt
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.max

/// 러닝화 사진 파일 저장소 (이슈 #206) — Application Support/RunWrap/shoes/<shoe id>-<난수>.jpg.
/// 사진은 긴 변 600px JPEG로 줄여 저장한다(카드·칩 표시에 충분하고 원본 수 MB를 쌓지 않는다).
/// ShoeCache처럼 atomic write + 기기 백업 제외. 기기 밖으로 보내지 않는다
/// (Android: 위치는 filesDir/RunWrap/shoes. 백업 제외는 data_extraction_rules.xml이 이 폴더를 include하지 않는 것으로 한다.
///  iOS의 `directory` 주입(테스트용)은 두지 않는다 — 디코더가 Android 플랫폼 API라 JVM 테스트로 돌릴 수 없다)
object ShoeImageStore {
    const val maxPixelSize = 600
    const val folder = "shoes"

    /// 사진 데이터를 줄여 저장하고 파일 이름을 돌려준다. 이미지로 읽히지 않거나 쓰기 실패면 nil.
    /// 저장할 때마다 파일 이름이 달라진다 — 사진을 바꾸면 `Shoe.imageFile`이 바뀌어 화면이 새 사진을 다시 읽는다
    fun save(context: Context, data: ByteArray, shoeID: String): String? {
        val file = "$shoeID-${UUID.randomUUID().toString().uppercase().take(8)}.jpg"
        val dir = directory(context)
        // 디코더가 디코드와 축소를 한 번에 하고, EXIF 회전도 픽셀에 반영한다.
        // 소프트웨어 비트맵이라야 JPEG로 다시 압축할 수 있다
        val image = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = max(info.size.width, info.size.height)
                if (longSide > maxPixelSize) {
                    val scale = maxPixelSize.toDouble() / longSide
                    decoder.setTargetSize((info.size.width * scale).swiftRoundedInt(), (info.size.height * scale).swiftRoundedInt())
                }
            }
        } catch (_: Exception) {
            return null
        }

        val target = File(dir, file)
        return try {
            val temp = File.createTempFile(file, ".tmp", dir)
            try {
                val ok = temp.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!ok) return null
                Files.move(temp.toPath(), target.toPath(),
                           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
            file
        } catch (_: Exception) {
            null
        } finally {
            image.recycle()
        }
    }

    /// 저장된 사진 위치 — 파일이 없으면 nil
    fun file(context: Context, name: String): File? =
        File(directory(context), name).takeIf { it.exists() }

    fun remove(context: Context, name: String) {
        File(directory(context), name).delete()
    }

    private fun directory(context: Context): File = File(appSupportDir(context), folder).apply { mkdirs() }
}
