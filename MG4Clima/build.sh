#!/usr/bin/env bash
# Compila MG4 Clima (app unica per SWI68, SWI69, SWI132 e SWI133) senza Gradle: aapt2 -> javac -> D8 -> firma (v1+v2).
# Uso: ./build.sh            (versione da version.properties)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
T="$ROOT/../tools"
APP="$ROOT/app"
OUT="$ROOT/build"
ANDROID_JAR="$T/android.jar"
KEYSTORE="${KEYSTORE:-$ROOT/../keys/platform.p12}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-platform}"

source "$ROOT/version.properties"   # VERSION_CODE, VERSION_NAME
OUT_NAME="MG4Clima-$VERSION_NAME.apk"

# --- strumenti esterni (esclusi da git): se mancano li scarico dalle fonti ufficiali Google ---
# Versioni fissate e hash SHA-256 controllati (gli stessi del repository Mg4-Launcher-SWI68).
PLATFORM_URL="https://dl.google.com/android/repository/platform-28_r06.zip"
PLATFORM_SHA256="8452dbbf9668a428abb243c4f02a943b7aa83af3cca627629a15c4c09f28e7bd"
ANDROID_JAR_SHA256="d20853d289a5161d9cf1c1268d7923076487d4fa68c970b0733e2d5a0ccfc3c5"
R8_VERSION="8.5.35"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
R8_SHA256="4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294"
# libreria di firma (serve solo se d8.jar non la contiene già)
APKSIG_VERSION="8.5.2"
APKSIG_URL="https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/$APKSIG_VERSION/apksig-$APKSIG_VERSION.jar"
APKSIG_SHA256="c4f6fdf2148490296f422a7eb37647ea11d1c3b02f1f2349c33ce257f3c29b1f"

manca_strumento() {  # $1 = file da allegare, $2 = descrizione
  echo
  echo "ERRORE: non riesco a procurarmi tools/$1."
  echo "  Allega il file tools/$1 ($2) e rilancia: cd MG4Clima && ./build.sh"
  exit 1
}

hash_ok() {  # $1 = file, $2 = sha256 atteso
  [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ]
}

TMP_DL="$(mktemp -d)"
trap 'rm -rf "$TMP_DL"; rm -f "$ROOT/build/gen/com/mg4/clima/LogConfig.java"' EXIT

if [ ! -f "$T/android.jar" ]; then
  echo "==> tools/android.jar mancante: scarico la piattaforma Android API 28 (platform-28_r06)"
  if curl -fsSL --retry 3 -o "$TMP_DL/platform.zip" "$PLATFORM_URL" \
     && hash_ok "$TMP_DL/platform.zip" "$PLATFORM_SHA256" \
     && unzip -q -o "$TMP_DL/platform.zip" android-9/android.jar -d "$TMP_DL" \
     && hash_ok "$TMP_DL/android-9/android.jar" "$ANDROID_JAR_SHA256"; then
    mv "$TMP_DL/android-9/android.jar" "$T/android.jar"
    echo "    android.jar scaricato e verificato"
  else
    manca_strumento "android.jar" "android.jar della piattaforma Android API 28, dall'Android SDK"
  fi
fi

if [ ! -f "$T/d8.jar" ]; then
  echo "==> tools/d8.jar mancante: scarico R8/D8 $R8_VERSION da Google Maven"
  if curl -fsSL --retry 3 -o "$TMP_DL/r8.jar" "$R8_URL" \
     && hash_ok "$TMP_DL/r8.jar" "$R8_SHA256"; then
    mv "$TMP_DL/r8.jar" "$T/d8.jar"
    echo "    d8.jar scaricato e verificato"
  else
    manca_strumento "d8.jar" "D8/R8 $R8_VERSION, com.android.tools:r8 da Google Maven"
  fi
fi

SIGN_CP="$T/d8.jar"
if ! unzip -l "$T/d8.jar" 'com/android/apksig/ApkSigner.class' >/dev/null 2>&1; then
  if [ ! -f "$T/apksig.jar" ]; then
    echo "==> tools/apksig.jar mancante: scarico apksig $APKSIG_VERSION da Google Maven"
    if curl -fsSL --retry 3 -o "$TMP_DL/apksig.jar" "$APKSIG_URL" \
       && hash_ok "$TMP_DL/apksig.jar" "$APKSIG_SHA256"; then
      mv "$TMP_DL/apksig.jar" "$T/apksig.jar"
      echo "    apksig.jar scaricato e verificato"
    else
      manca_strumento "apksig.jar" "libreria di firma com.android.tools.build:apksig da Google Maven"
    fi
  fi
  SIGN_CP="$T/d8.jar:$T/apksig.jar"
fi

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/tools"

SRC="$APP/java"
MANIFEST="$APP/AndroidManifest.xml"
RENAME=()
# pacchetto di loghi radio integrato (app/assets/loghi)
ASSETS=()
if [ -d "$APP/assets" ]; then ASSETS=(-A "$APP/assets"); fi

echo "==> risorse (aapt2)"
"$T/aapt2" compile --dir "$APP/res" -o "$OUT/res/compiled.zip"
"$T/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" "${RENAME[@]}" "${ASSETS[@]}" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "$OUT/res/compiled.zip"

echo "==> token dei log"
# Il token NON è nel repository: sta in tools/log-token.txt (escluso da git) oppure, se il file manca,
# nella variabile d'ambiente MG4_LOG_TOKEN. Il token non viene mai stampato.
# Diventa una classe generata in build/ (esclusa da git), offuscata con XOR (solo per non lasciarlo in chiaro nell'APK),
# e il sorgente generato viene cancellato subito dopo la compilazione.
#   tools/log-token.txt / MG4_LOG_TOKEN  → LogConfig  (invio dei log nel repository privato dei log)
gen_token() {
python3 - "$1" "$2" "$3" <<'PY'
import os, random, re, sys
src, dst, cls = sys.argv[1], sys.argv[2], sys.argv[3]
if os.path.exists(src):
    tok, fonte = open(src).read().strip(), "tools/log-token.txt"
else:
    tok, fonte = os.environ.get("MG4_LOG_TOKEN", "").strip(), "variabile MG4_LOG_TOKEN"
tok = re.sub(r"[^A-Za-z0-9_]", "", tok)
key = [random.randrange(1, 255) for _ in tok] or [0]
enc = [ord(ch) ^ key[i] for i, ch in enumerate(tok)]
os.makedirs(os.path.dirname(dst), exist_ok=True)
open(dst, "w").write(
    "package com.mg4.clima;\n\n/** Generato da build.sh: non modificare, non salvare in git. */\n"
    "final class %s {\n    private %s() {}\n"
    "    private static final int[] A = {%s};\n    private static final int[] B = {%s};\n"
    "    static String token() {\n        char[] c = new char[A.length];\n"
    "        for (int i = 0; i < A.length; i++) c[i] = (char) (A[i] ^ B[i]);\n        return new String(c);\n    }\n}\n"
    % (cls, cls, ",".join(map(str, enc)), ",".join(map(str, key[:len(enc)]))))
if tok:
    print("    %s: token presente (da %s)" % (cls, fonte))
else:
    print("    ATTENZIONE: token assente (né tools/log-token.txt né MG4_LOG_TOKEN).")
    print("    Compilo lo stesso, ma questo APK NON invierà i log.")
PY
}
gen_token "$T/log-token.txt" "$OUT/gen/com/mg4/clima/LogConfig.java" LogConfig

echo "==> java (javac, bytecode 8)"
find "$SRC" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# API Java 8 dal JDK (lambda), API Android da android.jar
if ! javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
     -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  grep -v 'Picked up JAVA_TOOL_OPTIONS' "$OUT/javac.log"
  rm -f "$OUT/gen/com/mg4/clima/LogConfig.java"
  echo "compilazione fallita"; exit 1
fi
rm -f "$OUT/gen/com/mg4/clima/LogConfig.java"   # non lascio in giro il sorgente col token
grep -v -E '^(Picked up JAVA_TOOL_OPTIONS|Note: )' "$OUT/javac.log" || true

echo "==> dex (D8)"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$T/d8.jar" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' || true
test -f "$OUT/dex/classes.dex" || { echo "D8 fallito"; exit 1; }

echo "==> pacchetto"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)

echo "==> firma"
javac -nowarn -d "$OUT/tools" -cp "$SIGN_CP" "$T/src/Sign.java" 2>&1 | grep -v 'Picked up' || true
java -cp "$OUT/tools:$SIGN_CP" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" \
  "$OUT/unsigned.apk" "$OUT/$OUT_NAME" 2>&1 | grep -v 'Picked up' || true
test -f "$OUT/$OUT_NAME"
ls -la "$OUT/$OUT_NAME"
