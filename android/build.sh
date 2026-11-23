#!/usr/bin/env bash
# 在手机上直接编译 APK：aapt(资源) → javac → d8 → zipalign → apksigner
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
SDK="${ANDROID_SDK:-/opt/android}"
ANDROID_JAR="$(find "$SDK" -name android.jar -path '*android-11*' | head -1)"
BT="$(find "$SDK" -name d8.jar | head -1)"
BT_DIR="$(dirname "$BT")"
KEYSTORE="${GHX_KEYSTORE:-keystore/ghx.jks}"
OUT="${1:-ghx.apk}"

[ -n "$ANDROID_JAR" ] || { echo "缺少 android.jar，先运行 setup-sdk.sh"; exit 1; }
[ -n "$BT" ] || { echo "缺少 d8.jar，先运行 setup-sdk.sh"; exit 1; }

rm -rf build
mkdir -p build/classes build/dex

echo "[1/6] 资源与清单"
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" -F build/base.apk

echo "[2/6] 编译 Java"
find src -name '*.java' > build/sources.txt
"$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -nowarn -d build/classes @build/sources.txt

echo "[3/6] 生成 dex"
find build/classes -name '*.class' > build/classes.txt
"$JAVA_HOME/bin/java" -cp "$BT" com.android.tools.r8.D8 --min-api 29 \
    --output build/dex @build/classes.txt
(cd build/dex && zip -q ../base.apk classes.dex)

echo "[4/6] 对齐"
zipalign -f 4 build/base.apk build/aligned.apk

echo "[5/6] 签名"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
      -storepass ghxandroid -keypass ghxandroid -alias ghx -keyalg RSA -keysize 2048 \
      -validity 10000 -dname "CN=ghx, O=ghx, C=CN" >/dev/null 2>&1
fi
"$JAVA_HOME/bin/java" -jar "$BT_DIR/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-pass pass:ghxandroid --key-pass pass:ghxandroid \
    --min-sdk-version 29 --out "$OUT" build/aligned.apk

echo "[6/6] 校验"
"$JAVA_HOME/bin/java" -jar "$BT_DIR/apksigner.jar" verify --min-sdk-version 29 "$OUT"
aapt dump badging "$OUT" | head -2
ls -lh "$OUT"
