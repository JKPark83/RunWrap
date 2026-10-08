#!/usr/bin/env bash
# iOS ↔ Android 동기화 경고 (Stop)
#
# 왜: iOS(사양 원본)만 고치고 Android 이식본을 잊으면 두 앱이 조용히 갈라진다. CI(android-test.yml)가
# PR에서 막지만, 그때는 맥락이 사라진 뒤다 — 세션이 끝나기 전에 한 번 알려 같은 세션에서 반영하게 한다.
# 반영할 것이 없는 iOS 전용 변경이면 docs/parity.md에 파일 이름과 이유를 적으면 통과한다.
#
# 실패 방식: git·python·jq가 없거나 기준 브랜치를 못 찾으면 exit 0으로 조용히 통과시킨다.
# 이미 이 훅 때문에 이어서 작업 중이면(stop_hook_active) 다시 막지 않는다 — 한 번만 알린다.
set -uo pipefail

payload=$(cat)
command -v jq >/dev/null || exit 0
[ "$(printf '%s' "$payload" | jq -r '.stop_hook_active // false' 2>/dev/null)" = "true" ] && exit 0

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
base=$(git merge-base HEAD origin/dev 2>/dev/null) || exit 0
out=$(python3 tools/ci/parity_check.py --diff "$base" 2>/dev/null) && exit 0
[ -z "$out" ] && exit 0

{
  echo "iOS만 바뀌고 Android에 반영되지 않은 변경이 있습니다 (tools/ci/parity_check.py):"
  echo "$out"
  echo "→ Android 대응 파일에 같은 변경을 옮기거나(/port-feature), 옮길 것이 없으면 docs/parity.md에 적으세요."
} >&2
exit 2
