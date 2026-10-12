#!/usr/bin/env bash
# Build APK RELEASE eraser-remake + verifikasi hasil.
#
# Aturan proyek (disepakati dengan pemilik): tidak ada fallback diam-diam ke
# kunci debug. Tanpa app/key.properties build DITOLAK, kecuali flag
# --allow-unsigned diberikan secara eksplisit (hasilnya APK unsigned yang
# hanya berguna untuk pemeriksaan isi, BUKAN untuk diinstall sebagai rilis).
#
# key.properties (tidak dikomit):
#   storeFile=path/ke/keystore.jks
#   storePassword=...
#   keyAlias=...
#   keyPassword=...
# Buat keystore sekali:
#   keytool -genkeypair -v -keystore eraser-remake.jks -alias eraser \
#     -keyalg RSA -keysize 4096 -validity 10000
set -euo pipefail

cd "$(dirname "$0")/.."

ALLOW_UNSIGNED=0
for arg in "$@"; do
    case "$arg" in
        --allow-unsigned) ALLOW_UNSIGNED=1 ;;
        *) echo "argumen tidak dikenal: $arg" >&2; exit 2 ;;
    esac
done

die() { echo "GAGAL: $*" >&2; exit 1; }

if [ ! -f app/key.properties ] && [ "$ALLOW_UNSIGNED" -ne 1 ]; then
    die "app/key.properties tidak ada. Buat keystore rilis lalu isi file itu, atau jalankan dengan --allow-unsigned untuk APK periksa-isi (tidak bisa diinstall sebagai rilis)."
fi

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ -z "${JAVA_HOME:-}" ]; then
    if [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
        JAVA_HOME="$(/usr/libexec/java_home)"
    else
        JAVA_HOME="$HOME/sdks/jdk-17"
    fi
    export JAVA_HOME
fi
export JAVA_OPTS="-Djava.net.preferIPv4Stack=true"
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    if [ "$(uname -s)" = "Darwin" ]; then
        ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    else
        ANDROID_SDK_ROOT="$HOME/sdks/android-sdk"
    fi
    export ANDROID_SDK_ROOT
fi
export PATH="$HOME/.cargo/bin:$PATH"

bash gradlew :app:assembleRelease --no-daemon

APK_DIR=app/build/outputs/apk/release
if [ -f app/key.properties ]; then
    APK="$APK_DIR/app-release.apk"
else
    APK="$APK_DIR/app-release-unsigned.apk"
fi
[ -f "$APK" ] || die "APK tidak ditemukan di $APK"

AAPT=$(ls "$ANDROID_SDK_ROOT"/build-tools/*/aapt2 2>/dev/null | sort | tail -1 || true)
if [ -n "${AAPT:-}" ]; then
    "$AAPT" dump badging "$APK" | head -3
fi

if [ -f app/key.properties ]; then
    APKSIGNER=$(ls "$ANDROID_SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)
    [ -n "${APKSIGNER:-}" ] || die "apksigner tidak ditemukan di build-tools"
    "$APKSIGNER" verify --print-certs "$APK" | head -4
    echo "Tanda tangan: TERPASANG (rilis)"
else
    echo "Tanda tangan: TIDAK ADA (mode --allow-unsigned, hanya untuk periksa isi)"
fi

echo "Ukuran: $(du -h "$APK" | cut -f1)  ($APK)"
if command -v sha256sum >/dev/null 2>&1; then
    echo "SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
else
    echo "SHA-256: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
fi
