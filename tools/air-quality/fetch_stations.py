#!/usr/bin/env python3
"""에어코리아 측정소 목록 -> ios/RunWrap/AirStations.json (이슈 #8, data.go.kr 15073877).

앱은 이 파일을 번들해 최근접 측정소 탐색을 기기 안에서 한다 — 사용자 좌표가
네트워크로 나가지 않고, TM 좌표 변환 라이브러리도 필요 없다.

인증키는 환경변수 DATA_GO_KR_KEY로 받는다 — data.go.kr "일반 인증키(Decoding)"
원본을 그대로 넣는다 (URL 인코딩된 Encoding 키가 아니다). 개발계정 트래픽
한도(500건/일)에서 월 1회 실행이면 페이지당 1건씩 몇 건이면 끝난다.

주의: 이 API는 dmX가 위도, dmY가 경도다 (이름과 반대). 문서·실응답이 갈린
사례가 있어 값 범위(한국 영역)로 판별해 배치한다.
"""
import argparse
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList"
ROWS_PER_PAGE = 1000
# 전국 측정소는 약 650곳 — 이보다 크게 줄었으면 API 개편·응답 이상으로 보고 실패시킨다
MIN_STATIONS = 400
# data.go.kr 게이트웨이는 SERVICETIMEOUT(504)을 수시로 낸다 — 재시도로 넘긴다 (실측 절반가량 실패)
MAX_RETRIES = 6


def get_json(url: str) -> dict:
    for attempt in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                return json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            if attempt == MAX_RETRIES - 1:
                raise
            print(f"일시 오류({e}) — {attempt + 2}번째 시도", file=sys.stderr)
            time.sleep(3 * (attempt + 1))
    raise AssertionError("unreachable")


def fetch_all(service_key: str) -> list[dict]:
    items, page = [], 1
    while True:
        query = urllib.parse.urlencode({
            "serviceKey": service_key,
            "returnType": "json",
            "numOfRows": ROWS_PER_PAGE,
            "pageNo": page,
        })
        payload = get_json(f"{BASE}?{query}")
        header = payload["response"]["header"]
        if header["resultCode"] != "00":
            sys.exit(f"API 오류: {header['resultCode']} {header.get('resultMsg', '')}")
        body = payload["response"]["body"]
        page_items = body.get("items") or []
        items += page_items
        if not page_items or page * ROWS_PER_PAGE >= int(body["totalCount"]):
            return items
        page += 1


def to_station(item: dict) -> dict | None:
    name = (item.get("stationName") or "").strip()
    try:
        a, b = float(item.get("dmX") or ""), float(item.get("dmY") or "")
    except ValueError:
        return None  # 좌표 미등록 측정소 — 최근접 탐색에 못 쓴다
    # 한국 영역: 위도 33~39, 경도 124~132. 어느 필드가 위도든 값으로 가려낸다
    lat, lon = (a, b) if 33 <= a <= 39 else (b, a)
    if not name or not (33 <= lat <= 39 and 124 <= lon <= 132):
        return None
    return {"name": name, "lat": round(lat, 6), "lon": round(lon, 6)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-o", "--output", default="ios/RunWrap/AirStations.json")
    args = parser.parse_args()

    service_key = os.environ.get("DATA_GO_KR_KEY", "")
    if not service_key:
        sys.exit("환경변수 DATA_GO_KR_KEY가 없다 — data.go.kr 디코딩 인증키를 넣을 것")

    stations = [s for s in (to_station(i) for i in fetch_all(service_key)) if s]
    stations.sort(key=lambda s: s["name"])
    if len(stations) < MIN_STATIONS:
        sys.exit(f"측정소 {len(stations)}곳 — 기준({MIN_STATIONS}곳) 미달, 응답 이상으로 판단해 갱신하지 않는다")

    generated_at = datetime.datetime.now(
        datetime.timezone(datetime.timedelta(hours=9))).isoformat(timespec="seconds")
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump({"generatedAt": generated_at,
                   "source": "data.go.kr 15073877 (한국환경공단 에어코리아)",
                   "stations": stations},
                  f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    print(f"측정소 {len(stations)}곳 -> {args.output}")


if __name__ == "__main__":
    main()
