#!/usr/bin/env bash
# Build eraser_core.so for arm64-v8a and copy it into the app's jniLibs staging dir.
# Requires: cargo + rustup target aarch64-linux-android, Android NDK.
# Set ANDROID_NDK_HOME (or ANDROID_SDK_ROOT) if the NDK is not auto-detected.
set -euo pipefail

cd "$(dirname "$0")/eraser-core"

API=29
TARGET=aarch64-linux-android

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    SDK="${ANDROID_SDK_ROOT:-$HOME/sdks/android-sdk}"
    ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
fi
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
echo "NDK: $ANDROID_NDK_HOME"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$TOOLCHAIN/bin/llvm-ar"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"

cargo build --release --target "$TARGET" --lib

OUT="../../app/build/rustJniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "target/$TARGET/release/liberaser_core.so" "$OUT/"
echo "Copied to $OUT/liberaser_core.so"

# ONNX Runtime dibundel sebagai .so terpisah (dimuat dinamis oleh crate ort).
ORT_SO="third_party/onnxruntime/android/arm64-v8a/libonnxruntime.so"
if [ -f "$ORT_SO" ]; then
    cp "$ORT_SO" "$OUT/"
    echo "Copied to $OUT/libonnxruntime.so"
else
    echo "WARNING: $ORT_SO tidak ada; jalankan tools/fetch_onnxruntime.sh. Inference AI tidak akan jalan di APK ini." >&2
fi
