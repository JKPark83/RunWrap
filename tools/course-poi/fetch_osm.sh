#!/bin/bash
# OSM Overpass에서 전국 음수대·화장실을 내려받는다 (키 불필요).
# 코스 보급 가이드 계획서(M12-1) — 음수대는 전국 표준 공공데이터가 없어 OSM이 주 소스,
# 화장실은 표준데이터 지오코딩 전 임시 소스이자 유실분 보완. 라이선스 ODbL(출처 표기 필요).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

OVERPASS="https://overpass-api.de/api/interpreter"

fetch() {  # $1: amenity 값, $2: 출력 파일
  curl -sS "$OVERPASS" --data-urlencode "data=[out:json][timeout:180];
area[\"name\"=\"대한민국\"]->.kr;
(node[\"amenity\"=\"$1\"](area.kr);
 way[\"amenity\"=\"$1\"](area.kr););
out center tags;" -o "raw/$2"
  python3 -c "import json,sys; print('$2:', len(json.load(open('raw/$2'))['elements']), '건')"
}

fetch drinking_water osm_water.json
fetch toilets osm_toilets.json
