#!/bin/bash
# 서울시 공원음수대(OA-20884) CSV를 내려받는다 (키 불필요, CP949 인코딩).
# 코스 보급 가이드 계획서(M12-1) — 좌표(X좌표(LNG)/Y좌표(LAT)) 포함, 주간 갱신.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw

curl -sS "https://datafile.seoul.go.kr/bigfile/iot/sheet/csv/download.do?infId=OA-20884&srvType=S&serviceKind=1&currentPageNo=1" \
  -o raw/seoul_water.csv
python3 -c "import csv; print('seoul_water.csv:', sum(1 for _ in csv.reader(open('raw/seoul_water.csv', encoding='cp949'))) - 1, '건')"
