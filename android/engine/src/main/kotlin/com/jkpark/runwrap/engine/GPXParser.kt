package com.jkpark.runwrap.engine

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/// 코스 좌표 한 점 — 엔진 계층은 CoreLocation을 모르므로 자체 타입을 쓴다 (계획서 M12-2).
/// Sendable은 코스 화면이 파싱·분석을 백그라운드 태스크에서 돌려 결과를 넘기기 때문이다 (#147)
data class GeoPoint(val lat: Double, val lon: Double)

/// GPX 파일에서 코스 좌표를 뽑는 파서 — 코스 보급 가이드의 입구 (기획서 §4.13).
/// 기록 트랙(trkpt)을 우선 읽고, 트랙 없이 경로 계획만 있는 파일은 rtept로 폴백한다.
/// wpt만 있는 파일은 "경로"가 아니므로 빈 배열 — 화면이 안내 문구로 처리한다
/// (계획서 M12 오픈 이슈 #4).
object GPXParser {
    /// 세그먼트 구분 없이 이어 붙인 좌표 — 세그먼트를 모르는 호출부·테스트 호환용
    fun parse(data: ByteArray): List<GeoPoint> = parseSegments(data).flatten()

    /// 끊긴 구간을 나눠 읽는다 — trkseg·trk 경계마다 새 배열, 빈 세그먼트는 버린다 (#150).
    /// 두 트랙을 한 파일에 담은 GPX를 그냥 이으면 사이의 수 km 점프가 코스 거리·매칭에 섞인다.
    /// 기록 트랙(trkpt)은 1~수 초 간격이라 인접 점이 `maxLegMeters`보다 멀면 GPS 끊김·일시정지 뒤
    /// 재개로 보고 그 자리에서도 끊는다. 경로 계획(rtept)은 점이 드물어(직선 구간은 점 2개)
    /// 긴 간격이 정상이므로 끊지 않고 전체를 세그먼트 1개로 본다
    fun parseSegments(data: ByteArray): List<List<GeoPoint>> {
        val collector = Collector()
        // Foundation XMLParser처럼 namespace를 처리하지 않는다 — 요소 이름은 접두사 포함 qName 그대로.
        // 외부 엔티티·외부 DTD는 읽지 않는다(XXE) — Collector.resolveEntity가 빈 입력을 돌려준다
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        try {
            factory.newSAXParser().parse(ByteArrayInputStream(data), collector)
        } catch (_: SAXException) {
            // 깨진 XML — XMLParser가 오류에서 멈추듯 그때까지 모은 점만 쓴다
        } catch (_: IOException) {
            // 인코딩 오류 등 — 위와 같다
        }
        collector.closeSegment()
        if (collector.trackSegments.isNotEmpty()) return collector.trackSegments
        return if (collector.routePoints.isEmpty()) emptyList() else listOf(collector.routePoints)
    }

    /// 기록 트랙에서 끊김으로 보는 인접 점 간격 — 1초 기록이면 1km는 달려서 낼 수 없는 거리다
    const val maxLegMeters = 1_000.0
    private const val metersPerDegree = 111_195.0

    /// 인접 두 점의 대략 거리(m) — 등장방형 근사. 끊김 판정용이라 정밀도는 중요치 않다
    fun roughMeters(a: GeoPoint, b: GeoPoint): Double {
        val lonScale = metersPerDegree * cos((a.lat + b.lat) / 2 * PI / 180)
        return hypot((b.lon - a.lon) * lonScale, (b.lat - a.lat) * metersPerDegree)
    }

    private class Collector : DefaultHandler() {
        val trackSegments = mutableListOf<List<GeoPoint>>()
        var currentSegment = mutableListOf<GeoPoint>()
        val routePoints = mutableListOf<GeoPoint>()

        /// 모으던 트랙 세그먼트를 닫는다 — 비어 있으면 버린다
        fun closeSegment() {
            if (currentSegment.isEmpty()) return
            trackSegments.add(currentSegment)
            currentSegment = mutableListOf()
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (qName == "trk" || qName == "trkseg") {
                closeSegment()
                return
            }
            if (qName != "trkpt" && qName != "rtept") return
            val lat = attributes.getValue("lat")?.let(::swiftDouble) ?: return
            val lon = attributes.getValue("lon")?.let(::swiftDouble) ?: return
            // 범위 밖·비유한 좌표(lat 120, nan, inf 등)는 조용히 버린다 — 엔진의 경도 축척이
            // 0 이하·NaN이 되어 크래시를 낸 원인이다 (2026-09-29 감사 M12)
            if (!(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)) return
            val point = GeoPoint(lat, lon)
            if (qName == "trkpt") {
                val last = currentSegment.lastOrNull()
                if (last != null && roughMeters(last, point) > maxLegMeters) closeSegment()
                currentSegment.add(point)
            } else {
                routePoints.add(point)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "trk" || qName == "trkseg") closeSegment()
        }

        /// 외부 엔티티·DTD는 빈 입력으로 대신한다 (XXE 차단)
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
    }

    private val decimalPattern = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val hexPattern = Regex("[+-]?0[xX]([0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)([pP][+-]?\\d+)?")

    /// Swift `Double(String)` 대응 — Kotlin `toDoubleOrNull`과 달리 앞뒤 공백·접미사(d/f)를 받지 않고,
    /// 지수 없는 16진("0x1A")은 받는다. nan·inf는 호출부가 어차피 버리므로 null로 돌려준다
    private fun swiftDouble(text: String): Double? = when {
        decimalPattern.matches(text) -> text.toDouble()
        hexPattern.matches(text) -> (if (text.contains('p', ignoreCase = true)) text else text + "p0").toDouble()
        else -> null
    }
}
