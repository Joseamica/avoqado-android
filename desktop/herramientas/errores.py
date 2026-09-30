#!/usr/bin/env python3
"""Resume los errores de compilación de Kotlin/KSP de una corrida de avq-verify.

Uso: python3 errores.py ~/.claude/avq-verify/run-avoqado-android.XXXXXX/out-local.txt
Imprime el total, las referencias sin resolver más frecuentes (= el siguiente sustituto a escribir),
los demás mensajes agrupados y los archivos con más errores.
"""
import collections
import re
import sys

texto = open(sys.argv[1], encoding="utf-8", errors="replace").read()
errores = re.findall(r"^e: (?:\[ksp\] )?(?:file://)?(\S+?\.kt):(\d+)(?::\d+)? (.*)$", texto, re.M)
print(f"errores: {len(errores)}")
refs = collections.Counter()
otros = collections.Counter()
for _, _, msg in errores:
    m = re.match(r"Unresolved reference '?([\w.]+)'?", msg)
    if m:
        refs[m.group(1)] += 1
    else:
        otros[re.sub(r"'[^']*'", "'…'", msg)[:100]] += 1
archivos = collections.Counter(re.sub(r".*/java/com/avoqado/pos/|.*/kotlin/", "", f) for f, _, _ in errores)
for titulo, c in (("sin resolver", refs), ("otros", otros), ("archivos", archivos)):
    print(f"\n== {titulo}")
    for k, n in c.most_common(40):
        print(f"{n:5}  {k}")
