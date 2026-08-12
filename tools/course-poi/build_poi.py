#!/usr/bin/env python3
"""raw/ 원본들을 통합해 앱 번들용 ios/RunWrap/CoursePOI.json을 만든다.

코스 보급 가이드 계획서(M12-1) — 산출 포맷은 필드명 1글자·좌표 소수 5자리(≈1.1m):
  {"generatedAt": "...", "pois": [{"k": "c", "n": "GS25 성수점", "la": 37.54321, "lo": 127.04567}]}
  k: c 편의점 / t 화장실 / w 음수대

- 같은 종류끼리 30m 이내면 하나로 합친다 (공공데이터·OSM 중복 대비).
  이름이 있는 소스를 먼저 넣어 살아남게 한다 (서울 음수대 → OSM, 표준데이터 화장실 → OSM).
- 한국 밖 좌표(위도 33~39, 경도 124~132 밖)는 오염 데이터로 보고 버린다.
- raw/toilets_geocoded.csv가 아직 없으면(카카오 키 대기) OSM 화장실만으로 만든다.

실행: fetch_osm.sh · fetch_seoul_water.sh · fetch_stores.sh (· geocode_toilets.sh) 후
  python3 build_poi.py
"""
import csv
import json
import math
import os
from datetime import date

RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'raw')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', '..', 'ios', 'RunWrap', 'CoursePOI.json')

# 30m 격자 중복 제거 — 셀 크기는 위도 30m, 경도는 한국 위도(≈36°) 기준 30m
CELL_LAT = 30 / 111_320
CELL_LON = 30 / (111_320 * math.cos(math.radians(36)))


def haversine_m(la1, lo1, la2, lo2):
    r1, r2 = math.radians(la1), math.radians(la2)
    a = math.sin((r2 - r1) / 2) ** 2 + math.cos(r1) * math.cos(r2) * math.sin(math.radians(lo2 - lo1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(a))


class Dedup:
    """종류별 30m 격자 — 먼저 넣은 POI가 살아남는다."""

    def __init__(self):
        self.grid = {}

    def insert(self, lat, lon):
        ci, cj = int(lat / CELL_LAT), int(lon / CELL_LON)
        for i in range(ci - 1, ci + 2):
            for j in range(cj - 1, cj + 2):
                for (la, lo) in self.grid.get((i, j), ()):
                    if haversine_m(lat, lon, la, lo) < 30:
                        return False
        self.grid.setdefault((ci, cj), []).append((lat, lon))
        return True


def read_csv_pois(path, encoding='utf-8'):
    """name,lat,lon 형식 중간 산출 CSV"""
    with open(path, encoding=encoding) as f:
        r = csv.DictReader(f)
        for row in r:
            yield row['name'], float(row['lat']), float(row['lon'])


def read_seoul_water(path):
    with open(path, encoding='cp949') as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            try:
                lon, lat = float(row[6]), float(row[7])  # X좌표(LNG), Y좌표(LAT)
            except (ValueError, IndexError):
                continue
            park = row[1].strip()
            yield (park + ' 음수대' if park else '음수대'), lat, lon


def read_osm(path, fallback_name):
    with open(path, encoding='utf-8') as f:
        for e in json.load(f)['elements']:
            lat = e.get('lat') or e.get('center', {}).get('lat')
            lon = e.get('lon') or e.get('center', {}).get('lon')
            if lat is None or lon is None:
                continue
            yield e.get('tags', {}).get('name', '').strip() or fallback_name, lat, lon


def main():
    # (종류, 소스 라벨, 이터레이터) — 이름 좋은 소스를 같은 종류에서 먼저
    sources = [
        ('c', '상가정보 편의점', read_csv_pois(os.path.join(RAW, 'convenience.csv'))),
        ('w', '서울 공원음수대', read_seoul_water(os.path.join(RAW, 'seoul_water.csv'))),
        ('w', 'OSM 음수대', read_osm(os.path.join(RAW, 'osm_water.json'), '음수대')),
        ('t', 'OSM 화장실', read_osm(os.path.join(RAW, 'osm_toilets.json'), '공중화장실')),
    ]
    geocoded = os.path.join(RAW, 'toilets_geocoded.csv')
    if os.path.exists(geocoded):
        sources.insert(3, ('t', '표준데이터 화장실(지오코딩)', read_csv_pois(geocoded)))
    else:
        print('[알림] toilets_geocoded.csv 없음 — 화장실은 OSM만으로 생성 (카카오 키 대기)')

    dedup = {'c': Dedup(), 't': Dedup(), 'w': Dedup()}
    pois, counts = [], {}
    for kind, label, it in sources:
        kept = dropped = 0
        for name, lat, lon in it:
            if not (33 <= lat <= 39 and 124 <= lon <= 132):
                continue
            if dedup[kind].insert(lat, lon):
                pois.append({'k': kind, 'n': name, 'la': round(lat, 5), 'lo': round(lon, 5)})
                kept += 1
            else:
                dropped += 1
        counts[label] = (kept, dropped)

    doc = {'generatedAt': date.today().isoformat(), 'pois': pois}
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))

    print(f'\nCoursePOI.json — 총 {len(pois):,}건, {os.path.getsize(OUT) / 1_048_576:.1f}MB')
    for label, (kept, dropped) in counts.items():
        print(f'  {label}: {kept:,}건 채택, {dropped:,}건 30m 중복 제거')
    by_kind = {}
    for p in pois:
        by_kind[p['k']] = by_kind.get(p['k'], 0) + 1
    print('  종류별:', {'c': '편의점', 't': '화장실', 'w': '음수대'}, by_kind)


if __name__ == '__main__':
    main()
