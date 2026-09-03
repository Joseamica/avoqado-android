#!/usr/bin/env python3
"""Avoqado Android — subida a Google Play (track producción) por API.

Sube el AAB, asigna el versionCode al track de producción con notas es-419
y confirma el edit — Google lo somete a su revisión automáticamente.

Uso:
  scripts/play_release.py <ruta.aab> <versionCode> [--notes archivo.txt]
      [--track production|internal|beta] [--status completed|draft]

  --status completed  (default) se envía a revisión de Google — el uso manual de siempre.
  --status draft      queda en Play Console esperando tu clic en "Iniciar lanzamiento".

Credenciales, por orden:
  1. Variable de entorno PLAY_SERVICE_ACCOUNT_JSON (el JSON completo). La usa
     GitHub Actions, donde no hay archivos locales.
  2. avoqado-server/firebase-service-account.json — el uso manual desde la Mac.
     (invitada 2026-07: firebase-adminsdk-whdtn@avoqado-d0a24.iam.gserviceaccount.com)

🔴 La cuenta de Firebase de arriba NO debe copiarse a los secretos de GitHub: además
de Play, abre Firestore, Auth, Storage y las push de toda la plataforma. Para CI se
usa una cuenta dedicada con permiso SÓLO de canales de prueba.

Requiere: pip install PyJWT cryptography (ya instalados en esta máquina).
"""
import sys, os, time, json, urllib.request, urllib.error, urllib.parse, argparse

import jwt  # PyJWT

PKG = "com.avoqado.pos"
SA_PATH = os.path.expanduser(
    "~/Documents/Programming/Avoqado/avoqado-server/firebase-service-account.json")


def cargar_credenciales():
    """La cuenta de servicio: de una variable de entorno o del archivo local.

    En GitHub Actions no hay archivo — el JSON llega en PLAY_SERVICE_ACCOUNT_JSON
    (un secreto del repo). En la Mac del founder no hay variable y se sigue leyendo
    el archivo de siempre, así que el uso manual NO cambia en nada.

    🔴 Y la variable NO debe traer la cuenta admin de Firebase: ésa abre Firestore,
    Auth, Storage y las notificaciones push de toda la plataforma. Para CI va una
    cuenta dedicada, invitada en Play Console SÓLO con permiso de canales de prueba.
    """
    crudo = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if crudo:
        try:
            return json.loads(crudo), "PLAY_SERVICE_ACCOUNT_JSON (entorno)"
        except json.JSONDecodeError as e:
            sys.exit(f"ERROR: PLAY_SERVICE_ACCOUNT_JSON no es JSON válido: {e}")
    if not os.path.exists(SA_PATH):
        sys.exit(f"ERROR: no hay credenciales — ni PLAY_SERVICE_ACCOUNT_JSON ni {SA_PATH}")
    return json.load(open(SA_PATH)), SA_PATH


def oauth_token():
    sa, origen = cargar_credenciales()
    # Se imprime QUIÉN publica: un 403 de Play no dice con qué identidad falló, y
    # con dos cuentas de servicio en juego adivinarlo cuesta media hora.
    print(f"▸ Identidad: {sa['client_email']}  ←  {origen}")
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
    ap.add_argument(
        "--status", default="completed", choices=["completed", "draft"],
        help="completed = se envía SOLO a revisión de Google (default, uso manual). "
             "draft = queda esperando tu clic en 'Iniciar lanzamiento' en Play Console.")
    a = ap.parse_args()

    # 🔴 Un robot NO publica a producción por su cuenta. Un APK tarda días en llegar
    # a los usuarios y no hay rollback rápido: la última palabra la da una persona.
    # El candado es sólo para CI — el uso manual desde la Mac queda igual que siempre.
    if os.environ.get("CI") and a.track == "production" and a.status == "completed":
        sys.exit("ERROR: desde CI, producción sólo se acepta con --status draft.\n"
                 "       El release queda en Play Console esperando tu confirmación.")

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
    release = {"versionCodes": [str(a.version_code)], "status": a.status}
    if notas:
        release["releaseNotes"] = [{"language": "es-419", "text": notas[:500]}]
    api(f"{base}/edits/{eid}/tracks/{a.track}", "PUT",
        body={"track": a.track, "releases": [release]})
    print(f"✓ Track {a.track} → versionCode {a.version_code} (status: {a.status})")

    api(f"{base}/edits/{eid}:commit", "POST", body={})
    if a.status == "draft":
        print("✓✓ EDIT CONFIRMADO — el release quedó en BORRADOR.")
        print("   Nadie lo recibe todavía: entra a Play Console → Test and release →")
        print(f"   {a.track} y presiona 'Review release' / 'Start rollout'.")
    elif a.track == "production":
        print("✓✓ EDIT CONFIRMADO — enviado a revisión de Google Play")
    else:
        # Los canales de prueba NO pasan por revisión: decir "enviado a revisión"
        # aquí haría creer que hay una espera que no existe.
        print(f"✓✓ EDIT CONFIRMADO — disponible para tus probadores de '{a.track}' en minutos.")
        print("   Sin revisión de Google: es la diferencia de este canal.")


if __name__ == "__main__":
    main()
