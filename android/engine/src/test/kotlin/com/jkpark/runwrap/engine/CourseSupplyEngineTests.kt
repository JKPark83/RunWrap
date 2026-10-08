package com.jkpark.runwrap.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CourseSupplyEngineTests {
    /// GPX 파서 — trkpt 우선, rtept 폴백, wpt 무시 (계획서 M12-2)
    @Nested
    inner class GPXParserTests {
        private fun gpx(body: String): ByteArray =
            ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
             "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
             "$body\n</gpx>").toByteArray()

        @Test
        @DisplayName("트랙 포인트 추출 — trkpt의 lat/lon을 순서대로 읽는다")
        fun parseTrackPoints() {
            val points = GPXParser.parse(gpx("""
            <trk><name>테스트</name><trkseg>
              <trkpt lat="37.5" lon="127.0"><ele>12</ele></trkpt>
              <trkpt lat="37.501" lon="127.001"/>
            </trkseg></trk>
            """))
            assertEquals(listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = 37.501, lon = 127.001)), points)
        }

        @Test
        @DisplayName("rtept 폴백 — 트랙이 없는 경로 계획 파일도 코스로 읽는다")
        fun routeFallback() {
            val points = GPXParser.parse(gpx("""
            <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.51" lon="127.0"/></rte>
            """))
            assertEquals(2, points.size)
            assertEquals(GeoPoint(lat = 37.51, lon = 127.0), points[1])
        }

        @Test
        @DisplayName("trkpt가 있으면 rtept는 무시 — 기록 트랙이 우선")
        fun trackWinsOverRoute() {
            val points = GPXParser.parse(gpx("""
            <rte><rtept lat="1.0" lon="1.0"/></rte>
            <trk><trkseg><trkpt lat="37.5" lon="127.0"/></trkseg></trk>
            """))
            assertEquals(listOf(GeoPoint(lat = 37.5, lon = 127.0)), points)
        }

        @Test
        @DisplayName("wpt만 있는 파일 — 경로가 아니므로 빈 배열 (오픈 이슈 #4)")
        fun waypointOnlyIsEmpty() {
            val points = GPXParser.parse(gpx("""
            <wpt lat="37.5" lon="127.0"><name>급수대</name></wpt>
            """))
            assertTrue(points.isEmpty())
        }

        @Test
        @DisplayName("깨진 XML·좌표 없는 포인트 — 조용히 건너뛴다")
        fun malformedInput() {
            assertTrue(GPXParser.parse("이건 GPX가 아닙니다".toByteArray()).isEmpty())
            val points = GPXParser.parse(gpx("""
            <trk><trkseg><trkpt lat="abc" lon="127.0"/><trkpt lat="37.5" lon="127.0"/></trkseg></trk>
            """))
            assertEquals(listOf(GeoPoint(lat = 37.5, lon = 127.0)), points)
        }

        @Test
        @DisplayName("범위 밖·비유한 좌표 — lat 120·nan, lon inf는 버리고 정상 포인트만 남긴다 (감사 M12)")
        fun dropsOutOfRangeCoordinates() {
            val points = GPXParser.parse(gpx("""
            <trk><trkseg>
              <trkpt lat="120" lon="127.0"/>
              <trkpt lat="nan" lon="127.0"/>
              <trkpt lat="37.5" lon="inf"/>
              <trkpt lat="37.5" lon="127.0"/>
              <trkpt lat="-90" lon="180"/>
            </trkseg></trk>
            """))
            // 경계값(±90, ±180)은 유효 범위라 남는다
            assertEquals(listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = -90.0, lon = 180.0)), points)
        }

        @Test
        @DisplayName("trk 경계 분리 — 트랙 두 개는 세그먼트 두 개, parse는 이어 붙인 결과 (#150)")
        fun splitsTracks() {
            val data = gpx("""
            <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.001"/></trkseg></trk>
            <trk><trkseg><trkpt lat="37.6" lon="127.0"/></trkseg></trk>
            """)
            val segments = GPXParser.parseSegments(data)
            assertEquals(listOf(listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = 37.5, lon = 127.001)),
                                listOf(GeoPoint(lat = 37.6, lon = 127.0))), segments)
            assertEquals(segments.flatten(), GPXParser.parse(data))
        }

        @Test
        @DisplayName("trkseg 경계 분리 — 같은 trk 안 trkseg 두 개도 나누고, 빈 trkseg는 버린다 (#150)")
        fun splitsTrackSegments() {
            val segments = GPXParser.parseSegments(gpx("""
            <trk>
              <trkseg><trkpt lat="37.5" lon="127.0"/></trkseg>
              <trkseg></trkseg>
              <trkseg><trkpt lat="37.51" lon="127.0"/></trkseg>
            </trk>
            """))
            assertEquals(listOf(listOf(GeoPoint(lat = 37.5, lon = 127.0)), listOf(GeoPoint(lat = 37.51, lon = 127.0))),
                         segments)
        }

        @Test
        @DisplayName("rtept는 세그먼트 1개 — 경로 계획에는 끊김 개념이 없다 (#150)")
        fun routeIsSingleSegment() {
            val segments = GPXParser.parseSegments(gpx("""
            <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.51" lon="127.0"/></rte>
            """))
            assertEquals(1, segments.size)
            assertEquals(2, segments.firstOrNull()?.size)
        }
    }

    /// 코스 보급 매칭 엔진 — 수선 거리·누적 km·반경·정렬·미노출 가드 (계획서 M12-2)
    ///
    /// 기대값 산출 근거: 엔진과 같은 산식으로 손 계산.
    /// 위도 1도 = 111,195m (R=6,371km·π/180), 경도 1도 = 111,195 × cos(37.5°) = 88,216.9m.
    /// 코스는 위도 37.5 고정, 경도 127.0 → 127.02의 직선 = 0.02° × 88,216.9 = 1,764.3m.
    /// 중간점 127.01을 둔다 — 점 간격 882.2m가 끊김 판정(1km, #150) 아래여야 한 줄로 이어진다.
    @Nested
    inner class CourseSupplyEngineTests {
        /// 위도 37.5 고정 동서 직선 코스 (1,764.3m)
        private val course = listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = 37.5, lon = 127.01),
                                    GeoPoint(lat = 37.5, lon = 127.02))

        private fun poi(kind: CoursePOI.Kind = CoursePOI.Kind.water, lat: Double, lon: Double): CoursePOI =
            CoursePOI(kind = kind, name = "테스트", lat = lat, lon = lon)

        private fun gpxData(text: String): ByteArray = text.trimIndent().toByteArray()

        @Test
        @DisplayName("수선 매칭 — 코스 중간 북쪽 100m POI는 0.882km 지점·이탈 100m")
        fun perpendicularMatch() {
            // POI(37.5009, 127.01): 수선 발 x = 0.01° × 88,216.9 = 882.2m → 0.882km 지점
            // 이탈 = 0.0009° × 111,195 = 100.1m ≤ 150m → 채택
            val result = CourseSupplyEngine.analyze(course = course, pois = listOf(poi(lat = 37.5009, lon = 127.01)))
            val match = assertNotNull(result?.matches?.firstOrNull())
            assertTrue(abs(match.courseKm - 0.882) < 0.005)
            assertTrue(abs(match.detourMeters - 100.1) < 0.5)
            assertTrue(abs(result!!.totalKm - 1.764) < 0.005)
        }

        @Test
        @DisplayName("반경 밖 제외 — 이탈 222m(> 150m) POI는 매칭하지 않는다")
        fun outsideRadius() {
            // 0.002° × 111,195 = 222.4m > 150m
            val result = CourseSupplyEngine.analyze(course = course, pois = listOf(poi(lat = 37.502, lon = 127.01)))
            assertEquals(true, result?.matches?.isEmpty())
        }

        @Test
        @DisplayName("끝점 클램프 — 코스 연장선 위 POI는 무한 직선이 아니라 끝점 거리로 잰다")
        fun clampToEndpoint() {
            // POI(37.5, 127.03)는 코스 연장선 위(수직 거리 0)지만 끝점에서 882.2m —
            // 클램프가 없으면 잘못 매칭된다
            val result = CourseSupplyEngine.analyze(course = course, pois = listOf(poi(lat = 37.5, lon = 127.03)))
            assertEquals(true, result?.matches?.isEmpty())
        }

        @Test
        @DisplayName("km 순 정렬 — 입력 순서와 무관하게 코스 진행 순으로 나온다")
        fun sortedByCourseKm() {
            // x = 0.015° × 88,216.9 = 1,323.3m / 0.005° × 88,216.9 = 441.1m, 이탈은 둘 다 33.4m
            val far = poi(CoursePOI.Kind.convenience, lat = 37.5003, lon = 127.015)
            val near = poi(CoursePOI.Kind.toilet, lat = 37.5003, lon = 127.005)
            val result = CourseSupplyEngine.analyze(course = course, pois = listOf(far, near))
            val matches = assertNotNull(result?.matches)
            assertEquals(listOf(CoursePOI.Kind.toilet, CoursePOI.Kind.convenience), matches.map { it.poi.kind })
            assertTrue(abs(matches[0].courseKm - 0.441) < 0.005)
            assertTrue(abs(matches[1].courseKm - 1.323) < 0.005)
        }

        @Test
        @DisplayName("미노출 가드 — 포인트 1개 또는 총거리 500m 미만이면 nil")
        fun insufficientCourse() {
            assertNull(CourseSupplyEngine.analyze(course = listOf(GeoPoint(lat = 37.5, lon = 127.0)), pois = emptyList()))
            // 0.0009° × 111,195 = 100.1m < 500m
            val short = listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = 37.5009, lon = 127.0))
            assertNull(CourseSupplyEngine.analyze(course = short, pois = emptyList()))
        }

        @Test
        @DisplayName("범위 밖 위도 — lat 120→121 코스는 트랩 없이 nil (감사 M12)")
        fun outOfRangeLatitudeReturnsNil() {
            // 중앙 위도 120.5°의 cos < 0 → 경도 축척 음수. 가드가 없으면 lonPad가 음수가 되어
            // 경도 고정 코스의 bbox가 127.0053...126.9947(하한 > 상한)로 만들어지며 트랩한다
            val course = listOf(GeoPoint(lat = 120.0, lon = 127.0), GeoPoint(lat = 121.0, lon = 127.0))
            assertNull(CourseSupplyEngine.analyze(course = course, pois = listOf(poi(lat = 37.5, lon = 127.0))))
        }

        @Test
        @DisplayName("비유한 좌표 — inf 위도·경도가 섞인 코스는 트랩 없이 nil (감사 M12)")
        fun infiniteCoordinatesReturnNil() {
            // 위도 inf → 중앙 위도 inf → cos NaN → 축척 가드에서 nil
            val infLat = listOf(GeoPoint(lat = Double.POSITIVE_INFINITY, lon = 127.0), GeoPoint(lat = 37.5, lon = 127.02))
            assertNull(CourseSupplyEngine.analyze(course = infLat, pois = emptyList()))
            // 경도 inf → 비유한 좌표 가드에서 nil
            val infLon = listOf(GeoPoint(lat = 37.5, lon = 127.0), GeoPoint(lat = 37.5, lon = Double.POSITIVE_INFINITY))
            assertNull(CourseSupplyEngine.analyze(course = infLon, pois = emptyList()))
        }

        @Test
        @DisplayName("bbox 1차 필터 — 코스에서 아주 먼 POI가 있어도 결과는 같다")
        fun bboxPrefilter() {
            val result = CourseSupplyEngine.analyze(
                course = course,
                pois = listOf(poi(lat = 35.1, lon = 129.0),           // 부산 — bbox 밖
                              poi(lat = 37.5009, lon = 127.01)))      // 코스 옆 100m
            assertEquals(1, result?.matches?.size)
        }

        // MARK: 세그먼트 분리 (#150)

        /// 위도 37.5 고정, 경도 127.0부터 0.005°(441.1m) 간격 동서 직선 n점
        private fun line(from: Double, points: Int): List<GeoPoint> =
            (0 until points).map { GeoPoint(lat = 37.5, lon = from + it * 0.005) }

        @Test
        @DisplayName("트랙 사이 점프 제외 — 5km 떨어진 두 trk는 세그먼트 2개, totalKm에 점프가 안 들어간다")
        fun excludesJumpBetweenTracks() {
            // 트랙1: 127.0→127.01 (882.2m), 5km 동쪽(0.0567° × 88,216.9 = 5,001.9m)으로 떨어져
            // 트랙2: 127.0667→127.0767 (882.2m). 유효 총거리 1,764.3m — 이으면 6,766m가 된다
            val data = gpxData("""
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
            <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.005"/><trkpt lat="37.5" lon="127.01"/></trkseg></trk>
            <trk><trkseg><trkpt lat="37.5" lon="127.0667"/><trkpt lat="37.5" lon="127.0717"/><trkpt lat="37.5" lon="127.0767"/></trkseg></trk>
            </gpx>
            """)
            val segments = GPXParser.parseSegments(data)
            assertEquals(2, segments.size)
            val result = assertNotNull(CourseSupplyEngine.analyze(segments = segments, pois = emptyList()))
            assertTrue(abs(result.totalKm - 1.764) < 0.005)
        }

        @Test
        @DisplayName("점프 구간 POI 미매칭 — 이어 붙였으면 잡혔을 점프 한가운데 POI는 빠진다")
        fun poiInJumpIsNotMatched() {
            // 세그먼트1 127.0→127.01, 세그먼트2 127.02→127.03 (각 882.2m, 사이 882.2m 점프).
            // POI(37.5003, 127.015)는 점프 선분 한가운데서 33.4m — 한 배열로 넘기면 매칭된다
            val first = line(from = 127.0, points = 3)
            val second = line(from = 127.02, points = 3)
            val jumpPOI = poi(lat = 37.5003, lon = 127.015)
            val joined = CourseSupplyEngine.analyze(course = first + second, pois = listOf(jumpPOI))
            assertEquals(1, joined?.matches?.size)
            val split = CourseSupplyEngine.analyze(segments = listOf(first, second), pois = listOf(jumpPOI))
            assertEquals(true, split?.matches?.isEmpty())
            // 누적 km는 세그먼트를 넘어 이어진다 — 점프를 뺀 882.2 + 882.2 = 1,764.3m
            assertTrue(abs((split?.totalKm ?: 0.0) - 1.764) < 0.005)
        }

        @Test
        @DisplayName("기록 트랙 안 1km 넘는 간격 — 파서가 끊어 거리·매칭에서 뺀다")
        fun longLegInsideTrackIsSplit() {
            // 127.0→127.01(882.2m) 다음 점이 127.03(1,764.3m 점프) → 127.04(882.2m). 유효 총거리 1,764.3m
            val segments = GPXParser.parseSegments(gpxData("""
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
            <trk><trkseg><trkpt lat="37.5" lon="127.0"/><trkpt lat="37.5" lon="127.01"/>
            <trkpt lat="37.5" lon="127.03"/><trkpt lat="37.5" lon="127.04"/></trkseg></trk>
            </gpx>
            """))
            assertEquals(2, segments.size)
            // 점프 한가운데(127.02) 북쪽 33.4m POI
            val result = assertNotNull(CourseSupplyEngine.analyze(segments = segments,
                                                                  pois = listOf(poi(lat = 37.5003, lon = 127.02))))
            assertTrue(result.matches.isEmpty())
            assertTrue(abs(result.totalKm - 1.764) < 0.005)
        }

        @Test
        @DisplayName("경로 계획(rtept)은 1km 넘는 직선도 끊지 않는다 — 점이 드문 게 정상")
        fun routeLongLegIsKept() {
            // 127.0→127.03 한 직선 2,646.5m. 끊었다면 nil(500m 미만)이 된다
            val segments = GPXParser.parseSegments(gpxData("""
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
            <rte><rtept lat="37.5" lon="127.0"/><rtept lat="37.5" lon="127.03"/></rte>
            </gpx>
            """))
            assertEquals(1, segments.size)
            val result = assertNotNull(CourseSupplyEngine.analyze(segments = segments,
                                                                  pois = listOf(poi(lat = 37.5003, lon = 127.015))))
            assertEquals(1, result.matches.size)
            assertTrue(abs(result.totalKm - 2.646) < 0.005)
        }

        @Test
        @DisplayName("범위 밖 좌표 — 유한해도 격자 셀 계산에서 트랩하지 않고 nil")
        fun outOfRangeCoordinatesReturnNil() {
            val course = line(from = 127.0, points = 5)
            val bogus = listOf(GeoPoint(lat = 37.5, lon = 1e20), GeoPoint(lat = 37.5, lon = 1e20))
            assertNull(CourseSupplyEngine.analyze(segments = listOf(course, bogus), pois = emptyList()))
        }

        @Test
        @DisplayName("단일 세그먼트 — analyze(course:)와 totalKm·매칭 수가 같다")
        fun singleSegmentMatchesCourseAPI() {
            val course = line(from = 127.0, points = 5)   // 1,764.3m
            val pois = listOf(poi(lat = 37.5009, lon = 127.01), poi(CoursePOI.Kind.toilet, lat = 37.5003, lon = 127.005),
                              poi(CoursePOI.Kind.convenience, lat = 37.502, lon = 127.01))   // 마지막은 222m로 반경 밖
            val byCourse = assertNotNull(CourseSupplyEngine.analyze(course = course, pois = pois))
            val bySegments = assertNotNull(CourseSupplyEngine.analyze(segments = listOf(course), pois = pois))
            assertEquals(byCourse.totalKm, bySegments.totalKm)
            assertEquals(byCourse.matches.size, bySegments.matches.size)
            assertEquals(2, byCourse.matches.size)
        }

        // MARK: 성능 전처리 (#147)

        @Test
        @DisplayName("격자 셀 경계 — 코스와 다른 셀에 있는 반경 안 POI도 매칭한다")
        fun matchesAcrossCellBoundary() {
            // 셀 한 변 = max(150, 300) = 300m, 원점은 코스 첫 점. 코스는 y = 0 동서선(셀 y = 0).
            // POI(37.4991, 127.01): y = -0.0009° × 111,195 = -100.1m → 셀 y = -1(경계 0m 바로 아래),
            // x = 882.2m → 셀 x = 2(경계 900m 바로 옆). 이탈 100.1m ≤ 150m → 1건
            val result = CourseSupplyEngine.analyze(course = course, pois = listOf(poi(lat = 37.4991, lon = 127.01)))
            val match = assertNotNull(result?.matches?.firstOrNull())
            assertEquals(1, result?.matches?.size)
            assertTrue(abs(match.detourMeters - 100.1) < 0.5)
            assertTrue(abs(match.courseKm - 0.882) < 0.005)
        }

        @Test
        @DisplayName("격자 = 완전 탐색 — 지그재그 코스·POI 400개에서 매칭 결과가 전 선분 검사와 같다")
        fun gridEqualsBruteForce() {
            // 코스: 동쪽으로 0.002°(176.4m)씩 가며 남북으로 0.003°(333.6m) 지그재그 40점.
            // 점 간격 ≈ 377m라 솎아내기(5m)·끊김(1km) 어느 쪽에도 안 걸려 원본 선분 = 엔진 선분이다
            val course = (0 until 40).map { GeoPoint(lat = 37.5 + (if (it % 2 == 0) 0.0 else 0.003),
                                                     lon = 127.0 + it * 0.002) }
            // 결정론적 의사난수(LCG)로 코스 bbox 주변 POI 400개
            var seed: ULong = 42u
            fun next(): Double {
                seed = seed * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
                return (seed shr 11).toDouble() / (1L shl 53).toDouble()
            }
            val pois = (0 until 400).map {
                val lat = 37.497 + next() * 0.009
                val lon = 126.997 + next() * 0.084
                poi(lat = lat, lon = lon)
            }
            val result = assertNotNull(CourseSupplyEngine.analyze(course = course, pois = pois))

            // 완전 탐색 기준값 — 엔진과 같은 투영(첫 점 원점, 위도 범위 중앙 cos)으로 모든 선분을 본다
            val lonScale = 111_195.0 * cos(37.5015 * PI / 180)
            val xy = course.map { Pair((it.lon - 127.0) * lonScale, (it.lat - 37.5) * 111_195.0) }
            val cumulative = mutableListOf(0.0)
            for (i in 1 until xy.size) cumulative.add(cumulative[i - 1] + hypot(xy[i].first - xy[i - 1].first, xy[i].second - xy[i - 1].second))
            val expected = mutableListOf<Pair<Double, Double>>()   // (km, dist)
            for (p in pois) {
                val px = (p.lon - 127.0) * lonScale
                val py = (p.lat - 37.5) * 111_195.0
                var best: Pair<Double, Double>? = null   // (dist, meters)
                for (i in 1 until xy.size) {
                    val (ax, ay) = xy[i - 1]
                    val (bx, by) = xy[i]
                    val len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
                    val t = max(0.0, min(1.0, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / len2))
                    val dist = hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
                    if (best == null || dist < best.first) best = Pair(dist, cumulative[i - 1] + t * (cumulative[i] - cumulative[i - 1]))
                }
                if (best != null && best.first <= 150) expected.add(Pair(best.second / 1_000, best.first))
            }
            expected.sortBy { it.first }

            assertTrue(expected.size > 10)   // 표본이 비어 동일성이 공허하게 참이 되지 않도록
            assertEquals(expected.size, result.matches.size)
            for ((match, want) in result.matches.zip(expected)) {
                assertTrue(abs(match.courseKm - want.first) < 1e-9)
                assertTrue(abs(match.detourMeters - want.second) < 1e-6)
            }
        }

        @Test
        @DisplayName("5m 미만 점 솎아내기 — 겹친 점이 많아도 총거리·매칭은 그대로")
        fun thinsDensePoints() {
            // 127.0 부근에 0.00001°(0.9m) 간격 점 10개를 끼워도 유효 선분은 같은 직선이다.
            // 총거리는 솎아낸 뒤에도 1,764.3m (끼운 점이 직선 위라 거리 변화 없음)
            val dense = (0 until 10).map { GeoPoint(lat = 37.5, lon = 127.0 + it * 0.000_01) }
            val result = assertNotNull(CourseSupplyEngine.analyze(
                course = dense + course.drop(1),
                pois = listOf(poi(lat = 37.5009, lon = 127.01))))
            assertTrue(abs(result.totalKm - 1.764) < 0.005)
            assertEquals(1, result.matches.size)
        }

        @Test
        @DisplayName("번들 포맷 디코드 — 1글자 키(k/n/la/lo)를 CoursePOI로 읽는다")
        fun decodeBundleFormat() {
            val json = """
            {"generatedAt":"2026-08-12","pois":[{"k":"c","n":"GS25 성수점","la":37.54321,"lo":127.04567}]}
            """.trimIndent()
            val file = EngineJson.decodeFromString(CoursePOIFile.serializer(), json)
            assertEquals("2026-08-12", file.generatedAt)
            assertEquals(listOf(CoursePOI(kind = CoursePOI.Kind.convenience, name = "GS25 성수점",
                                          lat = 37.54321, lon = 127.04567)), file.pois)
        }
    }
}
