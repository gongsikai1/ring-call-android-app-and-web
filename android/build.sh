#!/usr/bin/env bash
# 手工构建 APK（无需 Gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${SDK:-/home/user/build/sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/home/user/build/jdk-17.0.21+9}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$HERE/out"
ABI_KEEP="${ABI_KEEP:-arm64-v8a armeabi-v7a}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM_JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay "$OUT/res.zip"

echo "[3/7] javac"
javac -nowarn -source 8 -target 8 -bootclasspath "$PLATFORM_JAR:$BT/core-lambda-stubs.jar" \
  -classpath "$HERE/libs/okhttp-3.14.9.jar:$HERE/libs/okio-1.17.2.jar:$HERE/libs/webrtc_ext/classes.jar" \
  -d "$OUT/classes" \
  $(find "$HERE/src" "$OUT/gen" -name '*.java')

echo "[4/7] d8 (dex)"
"$BT/d8" --release --min-api 24 --lib "$PLATFORM_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$HERE/libs/okhttp-3.14.9.jar" "$HERE/libs/okio-1.17.2.jar" "$HERE/libs/webrtc_ext/classes.jar"

echo "[5/7] 打包 dex + so"
cp "$OUT/base.apk" "$OUT/apk/app.apk"
cd "$OUT/apk"
zip -q -j app.apk "$OUT/dex/classes.dex"
for abi in $ABI_KEEP; do
  mkdir -p "lib/$abi"
  cp "$HERE/libs/webrtc_ext/jni/$abi/"*.so "lib/$abi/"
done
zip -q -r app.apk lib
rm -rf lib
unzip -l app.apk | head -12

echo "[6/7] zipalign"
"$BT/zipalign" -p -f 4 app.apk app-aligned.apk

echo "[7/7] 签名"
KS="$HERE/ringcall.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass ringcall123 -keypass ringcall123 \
    -alias ringcall -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=RingCall, OU=Dev, O=RingCall, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:ringcall123 --key-pass pass:ringcall123 \
  --out "$OUT/RingCall.apk" app-aligned.apk
"$BT/apksigner" verify "$OUT/RingCall.apk"
ls -lh "$OUT/RingCall.apk"
echo "OK -> $OUT/RingCall.apk"
