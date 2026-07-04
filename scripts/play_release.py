#!/usr/bin/env python3
"""Avoqado Android — subida a Google Play (track producción) por API.

Sube el AAB, asigna el versionCode al track de producción con notas es-419
y confirma el edit — Google lo somete a su revisión automáticamente.

Uso:
  scripts/play_release.py <ruta.aab> <versionCode> [--notes archivo.txt]
      [--track production|internal|beta]

Credenciales: cuenta de servicio con permisos de Versiones en Play Console
  (invitada 2026-07: firebase-adminsdk-whdtn@avoqado-d0a24.iam.gserviceaccount.com)
  JSON en: avoqado-server/firebase-service-account.json
Requiere: pip install PyJWT cryptography (ya instalados en esta máquina).
"""
import sys, os, time, json, urllib.request, urllib.error, urllib.parse, argparse

import jwt  # PyJWT

PKG = "com.avoqado.pos"
SA_PATH = os.path.expanduser(
    "~/Documents/Programming/Avoqado/avoqado-server/firebase-service-account.json")


def oauth_token():
    sa = json.load(open(SA_PATH))
    assertion = jwt.encode(
        {"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
         "aud": "https://oauth2.googleapis.com/token",
         "iat": int(time.time()), "exp": int(time.time()) + 3600},
        sa["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}).encode()
    return json.load(urllib.request.urlopen(
        urllib.request.Request("https://oauth2.googleapis.com/token", data=body)))["access_token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("aab")
    ap.add_argument("version_code", type=int)
    ap.add_argument("--notes", help="archivo de texto con las notas (español)")
    ap.add_argument("--track", default="production")
    a = ap.parse_args()

    tok = oauth_token()
    H = {"Authorization": f"Bearer {tok}"}
    base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"

    def api(url, method="GET", body=None, raw=None, ctype="application/json"):
        data = raw if raw is not None else (json.dumps(body).encode() if body else None)
        req = urllib.request.Request(url, headers={**H, "Content-Type": ctype},
                                     method=method, data=data)
        try:
            return json.load(urllib.request.urlopen(req))
        except urllib.error.HTTPError as e:
            print(f"ERROR {e.code} {method} {url}:", e.read().decode()[:500])
            sys.exit(1)

    edit = api(f"{base}/edits", "POST", body={})
    eid = edit["id"]
    print("✓ Edit:", eid)

    print("▸ Subiendo AAB…", os.path.basename(a.aab))
    bundle = api(
        f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}/edits/{eid}/bundles",
        "POST", raw=open(a.aab, "rb").read(), ctype="application/octet-stream")
    print(f"✓ AAB subido — versionCode {bundle['versionCode']}")
    assert bundle["versionCode"] == a.version_code, "versionCode del AAB no coincide"

    notas = open(a.notes).read().strip() if a.notes else ""
    release = {"versionCodes": [str(a.version_code)], "status": "completed"}
    if notas:
        release["releaseNotes"] = [{"language": "es-419", "text": notas[:500]}]
    api(f"{base}/edits/{eid}/tracks/{a.track}", "PUT",
        body={"track": a.track, "releases": [release]})
    print(f"✓ Track {a.track} → versionCode {a.version_code}")

    api(f"{base}/edits/{eid}:commit", "POST", body={})
    print("✓✓ EDIT CONFIRMADO — enviado a revisión de Google Play")


if __name__ == "__main__":
    main()
