#!/usr/bin/env bash
# Manual APK build (no Gradle) — same pipeline as RailOne.
set -e
cd "$(dirname "$0")"
SDK=~/android-sdk
BT=$SDK/build-tools/33.0.2
AJAR=$SDK/platforms/android-33/android.jar
export JAVA_HOME=~/tools/jdk17
export PATH=$JAVA_HOME/bin:$BT:$PATH
KS=~/workspace/railone-android/RailOneProject/build_manual/debug.keystore

rm -rf build && mkdir -p build/gen build/classes build/dex
SRC=app/src/main

aapt2 compile --dir $SRC/res -o build/compiled.zip
aapt2 link -o build/unsigned.apk -I "$AJAR" --manifest $SRC/AndroidManifest.xml \
  -R build/compiled.zip -A $SRC/assets --java build/gen --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 33 --version-code 10 --version-name "10.0"

javac -encoding UTF-8 -source 8 -target 8 -cp "$AJAR" -d build/classes \
  $(find build/gen -name '*.java') $SRC/java/com/khelendra/magictransfer/MainActivity.java

d8 --lib "$AJAR" --output build/dex $(find build/classes -name '*.class')
# classes.dex MUST sit at the APK root, not under dex/
(cd build/dex && zip -q ../unsigned.apk classes.dex)
zipalign -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks "$KS" --ks-pass pass:android --out MagicTransfer.apk build/aligned.apk
apksigner verify MagicTransfer.apk
echo "OK -> MagicTransfer.apk"
