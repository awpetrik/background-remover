#!/usr/bin/env bash
# Unduh library ONNX Runtime untuk eraser-core:
# - Android arm64-v8a (dari AAR Maven) untuk dibundel ke APK
# - Linux x64 (dari rilis GitHub) untuk tes host
# Hasilnya masuk rust/eraser-core/third_party/onnxruntime/ dan tidak dikomit.
set -euo pipefail

ORT_VERSION="${ORT_VERSION:-1.28.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/rust/eraser-core/third_party/onnxruntime"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$DEST/android/arm64-v8a" "$DEST/linux-x64"

AAR_URL="https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${ORT_VERSION}/onnxruntime-android-${ORT_VERSION}.aar"
LINUX_URL="https://github.com/microsoft/onnxruntime/releases/download/v${ORT_VERSION}/onnxruntime-linux-x64-${ORT_VERSION}.tgz"

echo "Mengunduh $AAR_URL"
curl -sL --fail "$AAR_URL" -o "$TMP/ort-android.aar"
unzip -o -q "$TMP/ort-android.aar" -d "$TMP/aar"
cp "$TMP/aar/jni/arm64-v8a/libonnxruntime.so" "$DEST/android/arm64-v8a/"

echo "Mengunduh $LINUX_URL"
curl -sL --fail "$LINUX_URL" -o "$TMP/ort-linux.tgz"
tar -xzf "$TMP/ort-linux.tgz" -C "$TMP"
LINUX_LIB="$(ls "$TMP"/onnxruntime-linux-x64-*/lib/libonnxruntime.so.*.*.* 2>/dev/null | head -1)"
if [ -z "$LINUX_LIB" ]; then
  LINUX_LIB="$(ls "$TMP"/onnxruntime-linux-x64-*/lib/libonnxruntime.so | head -1)"
fi
cp "$LINUX_LIB" "$DEST/linux-x64/libonnxruntime.so"

ls -lh "$DEST/android/arm64-v8a/libonnxruntime.so" "$DEST/linux-x64/libonnxruntime.so"
echo "ONNX Runtime $ORT_VERSION siap di $DEST"
