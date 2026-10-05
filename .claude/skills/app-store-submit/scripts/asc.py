"""런미새 App Store Connect 제출 헬퍼 — 버전 생성·빌드 연결·문구·심사 메모·제출.

인증: 환경 변수 ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH(.p8). 의존성: PyJWT + cryptography.
"""
import json, os, sys, time, urllib.error, urllib.request

import jwt

APP_ID = "6800630601"
NOTES_LIMIT = 4000


def token():
    key = open(os.environ["ASC_KEY_PATH"]).read()
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
        key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"]},
    )


def call(method, path, body=None):
    req = urllib.request.Request(
        "https://api.appstoreconnect.apple.com" + path, method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"ASC {e.code}: {e.read().decode()[:1000]}")


def find_version(version):
    vs = call("GET", f"/v1/apps/{APP_ID}/appStoreVersions?filter[versionString]={version}&filter[platform]=IOS")
    return vs["data"][0] if vs["data"] else None


def status():
    for v in call("GET", f"/v1/apps/{APP_ID}/appStoreVersions?limit=5")["data"]:
        a = v["attributes"]
        print("version", a["versionString"], a["appStoreState"], a["releaseType"])
    for b in call("GET", f"/v1/builds?filter[app]={APP_ID}&sort=-uploadedDate&limit=5&include=preReleaseVersion")["data"]:
        a = b["attributes"]
        print("build", a["version"], a["processingState"], "encryption:", a.get("usesNonExemptEncryption"))
    open_states = "READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES"
    for s in call("GET", f"/v1/reviewSubmissions?filter[app]={APP_ID}&filter[state]={open_states}")["data"]:
        print("open submission", s["id"], s["attributes"]["state"])


def prepare(version, build_number, whats_new_path, notes_path):
    whats_new = open(whats_new_path).read().strip()
    notes = open(notes_path).read().strip()
    if len(notes) > NOTES_LIMIT:
        sys.exit(f"심사 메모가 {len(notes)}자 — {NOTES_LIMIT}자 이하로 줄여야 한다")

    v = find_version(version)
    if not v:
        v = call("POST", "/v1/appStoreVersions", {"data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version, "releaseType": "AFTER_APPROVAL"},
            "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]
    vid = v["id"]

    builds = call("GET", f"/v1/builds?filter[app]={APP_ID}&filter[version]={build_number}"
                         f"&filter[preReleaseVersion.version]={version}")["data"]
    if not builds:
        sys.exit(f"빌드 {version} ({build_number})를 찾지 못했다")
    if builds[0]["attributes"]["processingState"] != "VALID":
        sys.exit("빌드 처리가 끝나지 않았다: " + builds[0]["attributes"]["processingState"])
    call("PATCH", f"/v1/appStoreVersions/{vid}/relationships/build",
         {"data": {"type": "builds", "id": builds[0]["id"]}})

    for loc in call("GET", f"/v1/appStoreVersions/{vid}/appStoreVersionLocalizations")["data"]:
        call("PATCH", f"/v1/appStoreVersionLocalizations/{loc['id']}", {"data": {
            "type": "appStoreVersionLocalizations", "id": loc["id"], "attributes": {"whatsNew": whats_new}}})

    detail = call("GET", f"/v1/appStoreVersions/{vid}/appStoreReviewDetail").get("data")
    if detail:
        call("PATCH", f"/v1/appStoreReviewDetails/{detail['id']}", {"data": {
            "type": "appStoreReviewDetails", "id": detail["id"], "attributes": {"notes": notes}}})
    else:
        sys.exit("심사 정보(연락처)가 없다 — 웹에서 한 번 채운 뒤 다시 실행")
    print("prepared", version, build_number, "version id", vid)


def submit(version):
    v = find_version(version)
    if not v:
        sys.exit(f"버전 {version}이 없다 — prepare 먼저")
    s = call("POST", "/v1/reviewSubmissions", {"data": {
        "type": "reviewSubmissions", "attributes": {"platform": "IOS"},
        "relationships": {"app": {"data": {"type": "apps", "id": APP_ID}}}}})["data"]
    call("POST", "/v1/reviewSubmissionItems", {"data": {
        "type": "reviewSubmissionItems",
        "relationships": {"reviewSubmission": {"data": {"type": "reviewSubmissions", "id": s["id"]}},
                          "appStoreVersion": {"data": {"type": "appStoreVersions", "id": v["id"]}}}}})
    r = call("PATCH", f"/v1/reviewSubmissions/{s['id']}", {"data": {
        "type": "reviewSubmissions", "id": s["id"], "attributes": {"submitted": True}}})
    print("submitted", s["id"], r["data"]["attributes"]["state"])


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["status"]
    {"status": status, "prepare": prepare, "submit": submit}[cmd](*args)
