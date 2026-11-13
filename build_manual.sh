#!/usr/bin/env bash
# 可选：不依赖 Gradle 的手工构建（需 Android SDK platform-36 / build-tools 36.0.0）。
# 用法：ANDROID_HOME=/path/to/sdk ./build_manual.sh
# 签名密钥可通过 KEYSTORE / STORE_PASS / KEY_PASS 环境变量指定；未指定时自动生成一个临时密钥。
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build-manual"
APK="$OUT/vloader_1.1.apk"
KS="${KEYSTORE:-$OUT/dev.jks}"
STORE_PASS="${STORE_PASS:-m3u8dl2026}"
KEY_PASS="${KEY_PASS:-m3u8dl2026}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ"; do
    test -e "$f" || { echo "缺少：$f（请先安装 platforms;android-36 与 build-tools;36.0.0）"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"
cd "$PROJ"

"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
    --manifest app/src/main/AndroidManifest.xml \
    -R "$OUT/compiled/res.zip" --java "$OUT/gen" --auto-add-overlay \
    --min-sdk-version 26 --target-sdk-version 36 \
    --version-code 1 --version-name 1.1

find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$AJ" \
    -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --lib "$AJ" --min-api 26 --release --output "$OUT/dex" $(cat "$OUT/classlist.txt")
test -s "$OUT/dex/classes.dex" || { echo "dex 未生成"; exit 1; }

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias m3u8dl -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -dname "CN=M3U8 Downloader, OU=Dev, O=Personal, L=Shenzhen, ST=Guangdong, C=CN" \
        >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias m3u8dl \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$APK"
echo "产物：$APK"
