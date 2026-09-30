#!/usr/bin/env python3
"""App Store 배포 프로비저닝 프로파일을 App Store Connect REST API로 만들어 러너에 설치한다.

왜 자동 서명(-allowProvisioningUpdates)을 쓰지 않나:
  Xcode 26.6 자동 서명은 App Group 엔타이틀먼트가 있으면 xcbuild/v1/appGroups를 조회하는데,
  이 엔드포인트가 API 키 세션에 401을 돌려준다(certificates·bundleIds는 200). 그래서 위젯(App Group)을
  추가한 뒤부터 CI 아카이브가 "Authentication failed: Make sure a bearer token was provided"로 깨졌다.
  REST의 /v1/profiles는 같은 키로 정상 동작하므로, 프로파일을 여기서 직접 만들어 수동 서명한다.

동작: 번들 ID별로 이름 "<PREFIX> <bundle id>"인 IOS_APP_STORE 프로파일을 (있으면 지우고) 새로 만들어
  ~/Library/MobileDevice/Provisioning Profiles/ 와 Xcode UserData 경로 양쪽에 설치한다.
  프로파일에 들어가는 capability는 포털의 번들 ID 설정을 따른다(HealthKit·iCloud·App Groups 등).

환경변수: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8_PATH, DIST_CERT_SHA1(키체인에 설치한 배포 인증서 지문),
  BUNDLE_IDS(쉼표 구분), PROFILE_PREFIX(기본 "RunWrap CI AppStore").
"""
import base64, hashlib, json, os, sys, time, urllib.parse, urllib.request, urllib.error
import jwt

BASE = "https://api.appstoreconnect.apple.com/v1"
key = open(os.environ["ASC_KEY_P8_PATH"]).read()
now = int(time.time())
TOKEN = jwt.encode({"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"},
                   key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code}: {e.read().decode()[:600]}")


def paged(path):
    out = []
    while path:
        data = call("GET", path)
        out += data.get("data", [])
        nxt = data.get("links", {}).get("next")
        path = nxt[len(BASE):] if nxt else None
    return out


prefix = os.environ.get("PROFILE_PREFIX", "RunWrap CI AppStore")
bundle_ids = [b.strip() for b in os.environ["BUNDLE_IDS"].split(",") if b.strip()]
want_sha1 = os.environ["DIST_CERT_SHA1"].replace(":", "").upper()

# 1) 키체인에 설치한 배포 인증서와 같은 인증서를 포털에서 찾는다 (DER SHA-1 지문으로 대조)
cert_id = None
for c in paged("/certificates?filter[certificateType]=DISTRIBUTION,IOS_DISTRIBUTION&limit=200"):
    der = base64.b64decode(c["attributes"]["certificateContent"])
    if hashlib.sha1(der).hexdigest().upper() == want_sha1:
        cert_id = c["id"]
        print(f"배포 인증서 매칭: {c['attributes']['name']} (만료 {c['attributes']['expirationDate'][:10]})")
        break
if not cert_id:
    sys.exit("키체인의 Apple Distribution 인증서와 일치하는 포털 인증서가 없다 — DIST_CERT_P12_BASE64 시크릿을 확인")

# 2) 번들 ID 리소스 id
all_bundles = {b["attributes"]["identifier"]: b["id"] for b in paged("/bundleIds?limit=200")}

dest_dirs = [os.path.expanduser("~/Library/MobileDevice/Provisioning Profiles"),
             os.path.expanduser("~/Library/Developer/Xcode/UserData/Provisioning Profiles")]
for d in dest_dirs:
    os.makedirs(d, exist_ok=True)

for bid in bundle_ids:
    if bid not in all_bundles:
        sys.exit(f"포털에 번들 ID {bid}가 없다 — developer.apple.com에서 먼저 등록")
    name = f"{prefix} {bid}"
    # 3) 같은 이름의 기존 프로파일은 지우고 새로 만든다 (capability 변경을 매번 반영)
    for p in paged("/profiles?filter[name]=" + urllib.parse.quote(name) + "&limit=200"):
        if p["attributes"]["name"] == name:
            call("DELETE", f"/profiles/{p['id']}")
            print(f"기존 프로파일 삭제: {name}")
    created = call("POST", "/profiles", {"data": {"type": "profiles", "attributes": {"name": name, "profileType": "IOS_APP_STORE"},
                                                  "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": all_bundles[bid]}},
                                                                    "certificates": {"data": [{"type": "certificates", "id": cert_id}]}}}})
    attrs = created["data"]["attributes"]
    content = base64.b64decode(attrs["profileContent"])
    for d in dest_dirs:
        with open(os.path.join(d, attrs["uuid"] + ".mobileprovision"), "wb") as f:
            f.write(content)
    print(f"프로파일 생성·설치: {name} state={attrs['profileState']} uuid={attrs['uuid']}")
