"""crawl.py 단위 테스트 — 네트워크 없이 순수 함수만 검증한다 (#45).

실행: cd tools/race-info && python3 -B -m unittest -v
(-B: __pycache__를 만들지 않는다)
"""

import unittest

import crawl


class ParseDateTest(unittest.TestCase):
    def test_말일_초과는_clamp하면_말일(self):
        # 로드런 41854 원문 "2026년9월31일" — 9월은 30일까지 (#45)
        self.assertEqual(crawl.parse_date("2026년9월31일", clamp_day=True), "2026-09-30")

    def test_2월_평년_윤년(self):
        self.assertEqual(crawl.parse_date("2026년2월30일", clamp_day=True), "2026-02-28")
        self.assertEqual(crawl.parse_date("2028년2월30일", clamp_day=True), "2028-02-29")

    def test_공백_허용(self):
        self.assertEqual(crawl.parse_date("2026년 3월 26일"), "2026-03-26")

    def test_clamp_없으면_엄격(self):
        # 대회일은 clamp하지 않는다 — 달력에 없는 날짜는 버린다
        self.assertIsNone(crawl.parse_date("2026년9월31일"))

    def test_clamp해도_잘못된_월과_0일은_None(self):
        self.assertIsNone(crawl.parse_date("2026년13월1일", clamp_day=True))
        self.assertIsNone(crawl.parse_date("2026년0월1일", clamp_day=True))
        self.assertIsNone(crawl.parse_date("2026년9월0일", clamp_day=True))

    def test_날짜_없음(self):
        self.assertIsNone(crawl.parse_date("미정", clamp_day=True))


def cell(label: str, value: str) -> str:
    """상세 페이지의 '라벨 셀 → 값 셀' 한 쌍 (field() 정규식 형태)."""
    return (f'<td bgcolor="#86B7DF"><p>{label}</p></td>'
            f'<td bgcolor="white">{value}</td>')


class ParseDetailTest(unittest.TestCase):
    def test_접수마감_말일_초과를_말일로_살린다(self):
        # 로드런 41854 재현 — 접수기간 "2026년9월3일~2026년9월31일"
        html = (cell("대회명", "테스트 마라톤")
                + cell("대회일시", "2026년10월31일 출발시간:08:30")
                + cell("접수기간", "2026년9월3일~2026년9월31일"))
        race = crawl.parse_detail(41854, html)
        self.assertIsNotNone(race)
        self.assertEqual(race["date"], "2026-10-31")
        self.assertEqual(race["startTime"], "08:30")
        self.assertEqual(race["registerStart"], "2026-09-03")
        self.assertEqual(race["registerEnd"], "2026-09-30")

    def test_대회일이_달력에_없으면_버린다(self):
        html = cell("대회명", "테스트 마라톤") + cell("대회일시", "2026년9월31일")
        self.assertIsNone(crawl.parse_detail(1, html))


class QualityErrorsTest(unittest.TestCase):
    def test_현재_실측은_통과(self):
        # dev Races.json(2026-09-29): 273건 전부 파싱, registerEnd 누락 3건
        self.assertEqual(crawl.quality_errors(273, 273, {"registerEnd": 3}, False), [])

    def test_건수_하한(self):
        self.assertEqual(len(crawl.quality_errors(5, 5, {}, False)), 1)

    def test_limit_실행은_하한_면제(self):
        self.assertEqual(crawl.quality_errors(5, 5, {}, True), [])

    def test_성공률_미달(self):
        # 200/273 = 73.3% < 80%
        errors = crawl.quality_errors(200, 273, {}, False)
        self.assertEqual(len(errors), 1)
        self.assertIn("성공률", errors[0])

    def test_접수기간_누락률_초과(self):
        # 접수기간 라벨이 바뀌어 전부 빠진 상황 — 필드 2개 오류
        errors = crawl.quality_errors(
            273, 273, {"registerStart": 273, "registerEnd": 273}, False)
        self.assertEqual(len(errors), 2)

    def test_핵심_필드가_아니면_무시(self):
        self.assertEqual(crawl.quality_errors(273, 273, {"imageUrl": 200}, False), [])

    def test_전부_실패해도_0으로_나누지_않는다(self):
        self.assertEqual(len(crawl.quality_errors(0, 10, {}, False)), 2)


if __name__ == "__main__":
    unittest.main()
