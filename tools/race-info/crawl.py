#!/usr/bin/env python3
"""로드런(roadrun.co.kr) 마라톤 일정 크롤러 — Races.json 생성 (계획서 M13-1).

로드런 기본 목록(schedule/list.php)은 오늘 이후 대회만 노출한다. 목록에서
대회 번호(no)를 모은 뒤 상세(schedule/view.php?no=)를 순회하며 구조화 필드를
뽑는다. 서버 응답이 CP949라 cp949로 디코드하고, 소규모 사이트 예의로
요청 사이에 간격(기본 0.6초)을 둔다.

참가비·기념품은 로드런에 구조화 필드가 없다 — 기타소개(note)에 자유 텍스트로
실리는 경우만 그대로 담고, 별도 파싱은 하지 않는다(틀린 값 노출 방지, 기획서 §4.14).

사용법: python3 crawl.py [-o ../../ios/RunWrap/Races.json] [--limit N]
"""

import argparse
import json
import re
import sys
import time
import urllib.request
from datetime import datetime, timezone, timedelta

BASE = "http://roadrun.co.kr/schedule"
UA = "RunWrapBot/1.0 (+https://github.com/JKPark83/RunWrap)"
KST = timezone(timedelta(hours=9))


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as res:
        return res.read().decode("cp949", errors="replace")


def strip_tags(html: str) -> str:
    """태그 제거 — <br>만 줄바꿈으로 살린다 (기타소개의 줄 구조 유지)."""
    text = re.sub(r"<br\s*/?>", "\n", html, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("&nbsp;", " ").replace("&amp;", "&")
    text = re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", " ", text)  # 제어문자 제거 (원문에 VT 등이 섞임)
    lines = [ln.strip() for ln in text.split("\n")]
    return "\n".join(ln for ln in lines if ln).strip()


def field(html: str, label: str) -> str | None:
    """상세 페이지의 '라벨 셀(#86B7DF) → 값 셀(white)' 짝에서 값을 뽑는다."""
    m = re.search(
        label + r"</p>\s*</td>\s*<td[^>]*bgcolor=\"?white\"?[^>]*>(.*?)</td>",
        html, re.S)
    if not m:
        return None
    text = strip_tags(m.group(1))
    return text or None


DATE_RE = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일")


def parse_date(text: str) -> str | None:
    m = DATE_RE.search(text)
    if not m:
        return None
    y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
    try:
        return datetime(y, mo, d).strftime("%Y-%m-%d")
    except ValueError:
        return None


def parse_detail(no: int, html: str) -> dict | None:
    """상세 HTML → 대회 dict. 이름·날짜가 없으면 None (필수 필드)."""
    name = field(html, "대회명")
    when = field(html, "대회일시")
    if not name or not when:
        return None
    date = parse_date(when)
    if not date:
        return None

    race: dict = {"id": no, "name": name, "date": date}

    m = re.search(r"출발시간\s*[:：]\s*([0-2]?\d:\d{2})", when)
    if m:
        race["startTime"] = m.group(1)

    if region := field(html, "대회지역"):
        race["region"] = region
    if place := field(html, "대회장소"):
        race["place"] = place
    if host := field(html, "주최단체"):
        race["host"] = host

    if categories := field(html, "대회종목"):
        cats = [c.strip() for c in categories.split(",") if c.strip()]
        if cats:
            race["categories"] = cats

    # 접수기간: "2026년3월26일~2026년7월30일" — 물결 앞뒤로 나눠 각각 날짜 파싱
    if period := field(html, "접수기간"):
        parts = re.split(r"[~∼]", period, maxsplit=1)
        if start := parse_date(parts[0]):
            race["registerStart"] = start
        if len(parts) > 1 and (end := parse_date(parts[1])):
            race["registerEnd"] = end

    # 홈페이지: 표시 텍스트가 아니라 href 속성이 정확하다
    m = re.search(
        r"홈페이지</p>\s*</td>\s*<td.*?href=\"(https?://[^\"]+)\"",
        html, re.S)
    if m:
        race["homepage"] = m.group(1)

    # 대회장 지도 좌표 (네이버 지도 스크립트에서)
    m = re.search(r"naver\.maps\.LatLng\(\s*([\d.]+)\s*,\s*([\d.]+)\s*\)", html)
    if m:
        race["lat"] = float(m.group(1))
        race["lon"] = float(m.group(2))

    if note := field(html, "기타소개"):
        race["note"] = note

    return race


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--output", default="races.json")
    ap.add_argument("--limit", type=int, default=0, help="상세 조회 수 제한 (테스트용)")
    ap.add_argument("--delay", type=float, default=0.6, help="요청 간격(초)")
    args = ap.parse_args()

    listing = fetch(f"{BASE}/list.php")
    ids = list(dict.fromkeys(
        int(m) for m in re.findall(r"view\.php\?no=(\d+)", listing)))
    if not ids:
        print("목록에서 대회 번호를 찾지 못함 — 사이트 구조 변경 의심", file=sys.stderr)
        return 1
    if args.limit:
        ids = ids[:args.limit]
    print(f"대회 {len(ids)}건 상세 조회 시작", file=sys.stderr)

    races, dropped = [], []
    missing: dict[str, int] = {}
    for i, no in enumerate(ids):
        try:
            race = parse_detail(no, fetch(f"{BASE}/view.php?no={no}"))
        except Exception as e:  # 네트워크 오류 등 — 한 건 실패로 전체를 멈추지 않는다
            print(f"no={no} 오류: {e}", file=sys.stderr)
            race = None
        if race:
            races.append(race)
            for key in ("startTime", "region", "place", "host", "categories",
                        "registerStart", "registerEnd", "homepage", "note"):
                if key not in race:
                    missing[key] = missing.get(key, 0) + 1
        else:
            dropped.append(no)
        if (i + 1) % 50 == 0:
            print(f"{i + 1}/{len(ids)} 처리", file=sys.stderr)
        time.sleep(args.delay)

    races.sort(key=lambda r: (r["date"], r["id"]))
    doc = {
        "generatedAt": datetime.now(KST).strftime("%Y-%m-%dT%H:%M:%S+09:00"),
        "source": "roadrun.co.kr",
        "races": races,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    ok = len(races)
    print(f"파싱 성공 {ok}/{len(ids)} ({ok / len(ids):.1%}), 탈락 no={dropped}",
          file=sys.stderr)
    for key, count in sorted(missing.items()):
        print(f"  {key} 누락: {count}/{ok}", file=sys.stderr)
    # 성공률이 크게 무너지면 실패 처리 — Actions에서 구조 변경을 알아채는 장치
    return 0 if ok / len(ids) >= 0.8 else 1


if __name__ == "__main__":
    sys.exit(main())
