#!/usr/bin/env python3
"""iOS ↔ Android 동기화 점검 — 한쪽에만 반영된 변경을 잡는다.

iOS(ios/RunWrap)가 사양 원본이고 Android(android/)는 이식본이다. 대응 규칙은 "파일 이름이 같다"
(Foo.swift ↔ Foo.kt)이고, 이름이 다르거나 한쪽에만 있는 것은 docs/parity.md에 적는다.

  python3 tools/ci/parity_check.py                 전체 점검: 대응 없는 파일, Android에 없는 iOS 테스트
  python3 tools/ci/parity_check.py --diff <기준>   변경 점검: <기준>(예: origin/dev) 이후 iOS 파일을 고쳤는데
                                                   Android 대응 파일도 parity.md도 그대로인 경우

문제가 있으면 목록을 찍고 1로 끝난다.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARITY = ROOT / "docs" / "parity.md"
IOS_DIRS = ["ios/RunWrap", "ios/RunWrapTests", "ios/RunWrapWidget"]
STRING = r'"((?:[^"\\]|\\.)*)"'


def load_parity():
    """parity.md 표 → (iOS 파일 → Android 파일 목록 | None(ios-only)), android-only 파일, ios-only 테스트 이름"""
    mapping, android_only, ios_only_tests = {}, set(), set()
    in_tests = False
    for line in PARITY.read_text(encoding="utf-8").splitlines() if PARITY.exists() else []:
        if line.startswith("#"):
            in_tests = "ios-only 테스트" in line
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or set(cells[0]) <= set("-: "):
            continue
        if in_tests:
            # 테스트 이름에 | 가 들어갈 수 있어 줄 전체에서 첫 백틱 구간을 이름으로 본다
            name = re.search(r"`(.+?)`\s*\|", line)
            if name:
                ios_only_tests.add(name.group(1))
            continue
        swift = re.findall(r"`([\w+]+\.swift)`", cells[0])
        kotlin = re.findall(r"`([\w+]+\.kt)`", cells[1])
        for name in swift:
            mapping[name] = None if "ios-only" in cells[1] else kotlin
        if not swift:
            android_only.update(kotlin)
    return mapping, android_only, ios_only_tests


def kotlin_files():
    return [p for p in (ROOT / "android").rglob("*.kt") if "build" not in p.relative_to(ROOT).parts]


def swift_files():
    return [p for d in IOS_DIRS for p in sorted((ROOT / d).glob("*.swift"))]


def check_all():
    mapping, android_only, ios_only_tests = load_parity()
    kt = kotlin_files()
    kt_names = {p.name for p in kt}
    swift = swift_files()
    problems = []

    for path in swift:
        if path.name in mapping:
            for name in mapping[path.name] or []:
                if name not in kt_names:
                    problems.append(f"parity.md가 가리키는 {name}이 없다 ({path.name})")
        elif path.stem + ".kt" not in kt_names:
            problems.append(f"Android 대응 없음: {path.relative_to(ROOT)} — {path.stem}.kt를 만들거나 docs/parity.md에 적는다")

    mapped = {name for names in mapping.values() for name in names or []}
    swift_stems = {p.stem for p in swift}
    for path in kt:
        if path.stem not in swift_stems and path.name not in mapped | android_only:
            problems.append(f"iOS 대응 없음: {path.relative_to(ROOT)} — docs/parity.md에 android-only 또는 대응을 적는다")

    display_names = set()
    for path in kt:
        if "/test/" in path.as_posix():
            text = path.read_text(encoding="utf-8")
            display_names.update(n.replace("\\$", "$") for n in re.findall(r"@DisplayName\(\s*" + STRING, text))
    for path in sorted((ROOT / "ios/RunWrapTests").glob("*.swift")):
        if path.name in mapping and mapping[path.name] is None:
            continue  # 파일째 ios-only인 테스트 파일은 이름을 하나씩 적지 않아도 된다
        text = path.read_text(encoding="utf-8")
        names = re.findall(r"@Test\(\s*" + STRING, text)
        names += re.findall(r"@Test(?:\((?!\s*\")[^)]*\))?\s+func\s+(\w+)", text)  # 이름 없는 @Test는 함수 이름
        for name in names:
            if name not in display_names and name not in ios_only_tests:
                problems.append(f"Android에 없는 테스트: {path.name} — {name}")
    return problems


def check_diff(base):
    mapping, _, _ = load_parity()
    git = lambda *a: subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    base = git("merge-base", "HEAD", base).strip()  # 기준 브랜치가 앞서 나간 변경을 내 변경으로 세지 않는다
    changed = set(git("diff", "--name-only", base).split()) | set(git("ls-files", "--others", "--exclude-standard").split())
    changed_kt = {Path(p).name for p in changed if p.endswith(".kt")}
    parity_added = "\n".join(l for l in git("diff", base, "--", "docs/parity.md").splitlines() if l.startswith("+"))
    problems = []
    for path in sorted(changed):
        p = Path(path)
        if p.suffix != ".swift" or p.parent.as_posix() not in IOS_DIRS:
            continue
        counterparts = mapping.get(p.name, [p.stem + ".kt"])
        if counterparts is None or changed_kt & set(counterparts + [p.stem + ".kt"]) or p.name in parity_added:
            continue
        problems.append(f"iOS만 바뀜: {path} → Android {', '.join(counterparts) or p.stem + '.kt'}에 반영하거나, "
                        f"반영할 것이 없으면 docs/parity.md에 {p.name}과 이유를 적는다")
    return problems


def main():
    args = sys.argv[1:]
    problems = check_diff(args[1]) if args[:1] == ["--diff"] and len(args) == 2 else check_all()
    for line in problems:
        print(line)
    if problems:
        print(f"\niOS ↔ Android 동기화 문제 {len(problems)}건 — docs/parity.md와 android/CLAUDE.md 참고", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
