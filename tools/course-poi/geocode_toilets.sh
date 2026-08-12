#!/bin/bash
# 전국공중화장실표준데이터를 내려받고 주소를 좌표로 복원한다.
# 코스 보급 가이드 계획서(M12-1) — 원본(data.go.kr 15012892)이 2025.2부터 좌표 제공을
# 중단해 카카오 로컬 API로 개발 단계에서 1회 지오코딩한다 (기획서 §4.13).
#
# 사용법: KAKAO_REST_KEY=<카카오 REST API 키> ./geocode_toilets.sh
#   - 키는 환경변수로만 전달한다. 파일·저장소에 절대 커밋하지 말 것.
#   - 키 없이 실행하면 CSV 다운로드까지만 하고 안내 후 종료한다.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

# file.localdata.go.kr는 브라우저 UA + Referer가 없으면 403을 낸다 (2026-08 실측).
if [ ! -f raw/toilets.csv ]; then
  curl -sSL "https://file.localdata.go.kr/file/download/public_restroom_info/info" \
    -H "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36" \
    -H "Referer: https://www.localdata.go.kr/" \
    -o raw/toilets.csv
fi
python3 -c "import csv; print('toilets.csv:', sum(1 for _ in csv.reader(open('raw/toilets.csv', encoding='cp949'))) - 1, '건')"

if [ -z "${KAKAO_REST_KEY:-}" ]; then
  echo "KAKAO_REST_KEY가 없어 지오코딩을 건너뜁니다. 키를 받으면 다시 실행하세요."
  exit 0
fi

# 도로명주소 우선, 실패 시 지번주소 폴백. 성공률을 로그로 남긴다 (계획서 오픈 이슈 #1).
# 카카오 무료 쿼터(일 10만 건) 안에서 5.3만 건을 병렬 8스레드로 처리한다.
python3 << 'EOF'
import csv, json, os, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ['KAKAO_REST_KEY']

def geocode(query):
    url = 'https://dapi.kakao.com/v2/local/search/address.json?query=' + urllib.parse.quote(query)
    req = urllib.request.Request(url, headers={'Authorization': 'KakaoAK ' + KEY})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                docs = json.load(resp).get('documents')
                if docs:
                    return float(docs[0]['y']), float(docs[0]['x'])  # (lat, lon)
                return None
        except Exception:
            time.sleep(1 + attempt)
    return None

def resolve(row):
    # 4 화장실명, 5 도로명주소, 6 지번주소
    for addr in (row[5], row[6]):
        addr = addr.strip()
        if addr and (coord := geocode(addr)):
            return (row[4].strip() or '공중화장실', coord[0], coord[1])
    return None

with open('raw/toilets.csv', encoding='cp949') as f:
    rows = [r for r in csv.reader(f) if len(r) > 6][1:]

done = 0
with open('raw/toilets_geocoded.csv', 'w', newline='', encoding='utf-8') as out:
    w = csv.writer(out)
    w.writerow(['name', 'lat', 'lon'])
    with ThreadPoolExecutor(max_workers=8) as ex:
        for i, res in enumerate(ex.map(resolve, rows), 1):
            if res:
                w.writerow([res[0], f'{res[1]:.5f}', f'{res[2]:.5f}'])
                done += 1
            if i % 2000 == 0:
                print(f'{i}/{len(rows)} 처리, 성공 {done}', flush=True)

print(f'지오코딩 성공률: {done}/{len(rows)} ({done / len(rows):.1%})')
EOF
