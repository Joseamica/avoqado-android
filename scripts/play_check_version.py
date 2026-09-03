#!/usr/bin/env python3
"""¿Este versionCode ya está subido a Google Play?

Play rechaza un versionCode repetido, pero lo hace AL FINAL — después de que el
build firmado de ~40 minutos ya corrió. Preguntar antes cuesta dos segundos y una
llamada de sólo lectura.

Uso:
  scripts/play_check_version.py <versionCode>   → ¿está libre? sale 0 o 1
  scripts/play_check_version.py --next          → imprime el SIGUIENTE libre y sale 0

`--next` es lo que usa el carril automático: nadie tiene que acordarse de subir el
versionCode a mano en cada hotfix, y por venir del propio Play no puede chocar.
Credenciales: las mismas que play_release.py (PLAY_SERVICE_ACCOUNT_JSON o el archivo).

🔴 Un fallo de red o de permisos NO se traga: sale 1. Dejar pasar el build por no
haber podido preguntar es justo lo que este candado existe para evitar.
"""
import sys, os, json, time, urllib.request, urllib.error, urllib.parse

import jwt  # PyJWT

PKG = "com.avoqado.pos"
SA_PATH = os.path.expanduser(
    "~/Documents/Programming/Avoqado/avoqado-server/firebase-service-account.json")


def credenciales():
    crudo = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if crudo:
        return json.loads(crudo)
    if not os.path.exists(SA_PATH):
        sys.exit("ERROR: no hay credenciales (ni PLAY_SERVICE_ACCOUNT_JSON ni el archivo local)")
    return json.load(open(SA_PATH))


def token(sa):
    a = jwt.encode(
        {"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/androidpublisher",
         "aud": "https://oauth2.googleapis.com/token",
         "iat": int(time.time()), "exp": int(time.time()) + 3600},
        sa["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": a}).encode()
    return json.load(urllib.request.urlopen(
        urllib.request.Request("https://oauth2.googleapis.com/token", data=body)))["access_token"]


def main():
    if len(sys.argv) != 2:
        sys.exit("uso: play_check_version.py <versionCode> | --next")
    siguiente = sys.argv[1] == "--next"
    quiero = None if siguiente else int(sys.argv[1])

    sa = credenciales()
    H = {"Authorization": f"Bearer {token(sa)}", "Content-Type": "application/json"}
    base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"

    def api(url, method="GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        return json.load(urllib.request.urlopen(
            urllib.request.Request(url, headers=H, method=method, data=data)))

    eid = None
    try:
        eid = api(f"{base}/edits", "POST", body={})["id"]
        subidos = sorted(b["versionCode"] for b in api(f"{base}/edits/{eid}/bundles").get("bundles", []))
    except urllib.error.HTTPError as e:
        # Sin veredicto no se sigue: un 403 aquí suele significar que la cuenta de
        # servicio perdió permisos, y ése es exactamente el momento de parar.
        sys.exit(f"ERROR {e.code} consultando Play: {e.read().decode()[:300]}")
    finally:
        if eid:
            try:
                urllib.request.urlopen(urllib.request.Request(
                    f"{base}/edits/{eid}", headers=H, method="DELETE"))
            except Exception:
                pass  # el edit caduca solo; no vale tumbar la corrida por esto

    # --next imprime SOLO el número: la salida se consume desde el workflow.
    # 🔴 Si Play no devolvió nada, NO se inventa un 1: sería pisar la numeración
    # real de la tienda. Sin datos, no hay número.
    if siguiente:
        if not subidos:
            sys.exit("ERROR: Play no devolvió ningún versionCode; no se puede calcular el siguiente.")
        print(max(subidos) + 1)
        return 0

    print(f"Identidad: {sa['client_email']}")
    print(f"versionCodes ya en Play: {subidos[-8:] if subidos else '(ninguno)'}")

    if quiero in subidos:
        print(f"::error::El versionCode {quiero} YA está subido a Play. "
              f"Sube versionCode en app/build.gradle.kts antes de publicar.")
        return 1
    if subidos and quiero < max(subidos):
        print(f"::error::versionCode {quiero} es MENOR que el más alto en Play ({max(subidos)}). "
              f"Play sólo acepta números que suben.")
        return 1

    print(f"✓ versionCode {quiero} está libre.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
