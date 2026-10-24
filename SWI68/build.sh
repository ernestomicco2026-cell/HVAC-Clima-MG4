#!/usr/bin/env bash
# Compila MG4 Clima (SWI68) senza Gradle: aapt2 -> javac -> D8 -> firma (v1+v2).
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
OUT_NAME="MG4Clima-SWI68-$VERSION_NAME.apk"

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

echo "==> configurazione invio log"
# Il token del repository privato dei log NON è nel repository: sta in tools/log-token.txt (escluso da git).
# Qui diventa una classe generata, offuscata con XOR (solo per non lasciarlo in chiaro nell'APK).
python3 - "$T/log-token.txt" "$OUT/gen/com/mg4/clima/LogConfig.java" <<'PY'
import os, random, sys
src, dst = sys.argv[1], sys.argv[2]
tok = open(src).read().strip() if os.path.exists(src) else ""
# solo caratteri validi
import re
tok = re.sub(r"[^A-Za-z0-9_]", "", tok)
key = [random.randrange(1, 255) for _ in tok] or [0]
enc = [ord(ch) ^ key[i] for i, ch in enumerate(tok)]
os.makedirs(os.path.dirname(dst), exist_ok=True)
open(dst, "w").write(
    "package com.mg4.clima;\n\n/** Generato da build.sh: non modificare, non salvare in git. */\n"
    "final class LogConfig {\n    private LogConfig() {}\n"
    "    private static final int[] A = {%s};\n    private static final int[] B = {%s};\n"
    "    static String token() {\n        char[] c = new char[A.length];\n"
    "        for (int i = 0; i < A.length; i++) c[i] = (char) (A[i] ^ B[i]);\n        return new String(c);\n    }\n}\n"
    % (",".join(map(str, enc)), ",".join(map(str, key[:len(enc)]))))
print("    token:", "presente" if tok else "ASSENTE (invio log disattivato in questo APK)")
PY

echo "==> java (javac, bytecode 8)"
find "$SRC" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# API Java 8 dal JDK (lambda), API Android da android.jar
if ! javac -nowarn -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
     -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  grep -v 'Picked up JAVA_TOOL_OPTIONS' "$OUT/javac.log"
  echo "compilazione fallita"; exit 1
fi
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
javac -nowarn -d "$OUT/tools" -cp "$T/d8.jar" "$T/src/Sign.java" 2>&1 | grep -v 'Picked up' || true
java -cp "$OUT/tools:$T/d8.jar" Sign "$KEYSTORE" "$KEY_PASS" "$KEY_ALIAS" \
  "$OUT/unsigned.apk" "$OUT/$OUT_NAME" 2>&1 | grep -v 'Picked up' || true
test -f "$OUT/$OUT_NAME"
ls -la "$OUT/$OUT_NAME"
