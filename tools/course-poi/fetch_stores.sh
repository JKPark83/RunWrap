#!/bin/bash
# 소상공인시장진흥공단 상가(상권)정보 전체 CSV(zip)를 내려받아 편의점만 추출한다 (키 불필요).
# 코스 보급 가이드 계획서(M12-1) — data.go.kr 15083033, 분기 갱신.
#
# 다운로드 프로토콜(2026-08 실측): fileData.do 상세 페이지의 fn_fileDataDown 인자를
# selectFileDataDownload.do에 POST하면 atchFileId가 오고, 그걸로 fileDownload.do에서
# 로그인 없이 받을 수 있다. DETAIL_PK(uddi)는 분기 갱신 시 바뀌므로 실패하면
# https://www.data.go.kr/data/15083033/fileData.do 에서 새 값을 확인할 것.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

DATA_PK="15083033"
DETAIL_PK="uddi:b3094bc9-8756-4ecc-9141-9144b98a531e"

if [ ! -f raw/stores.zip ]; then
  ATCH=$(curl -sS "https://www.data.go.kr/tcs/dss/selectFileDataDownload.do" \
    -H "X-Requested-With: XMLHttpRequest" \
    --data-urlencode "publicDataPk=$DATA_PK" \
    --data-urlencode "publicDataDetailPk=$DETAIL_PK" \
    --data-urlencode "atchFileId=" \
    --data-urlencode "fileDetailSn=1" \
    --data-urlencode "publicDataTyCode=PR0051" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('status'), d; print(d['atchFileId'])")
  curl -sS "https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=$ATCH&fileDetailSn=1" -o raw/stores.zip
fi

# zip 안 16개 시도별 CSV(UTF-8)를 스트리밍으로 훑어 편의점만 뽑는다.
# 필터는 상권업종소분류코드 G20405(편의점) — KSIC(G47122)보다 누락·오포함이 적다.
python3 << 'EOF'
import zipfile, io, csv
z = zipfile.ZipFile('raw/stores.zip')
with open('raw/convenience.csv', 'w', newline='', encoding='utf-8') as out:
    w = csv.writer(out)
    w.writerow(['name', 'lat', 'lon'])
    total = 0
    for info in (i for i in z.infolist() if i.filename.endswith('.csv')):
        with z.open(info) as f:
            r = csv.reader(io.TextIOWrapper(f, encoding='utf-8', errors='replace'))
            next(r)
            for row in r:
                if len(row) < 39 or row[7] != 'G20405':  # 상권업종소분류코드
                    continue
                try:
                    lon, lat = float(row[37]), float(row[38])  # 경도, 위도
                except ValueError:
                    continue
                name = (row[1] + (' ' + row[2] if row[2] else '')).strip()  # 상호명 + 지점명
                w.writerow([name, f'{lat:.5f}', f'{lon:.5f}'])
                total += 1
print('convenience.csv:', total, '건')
EOF
