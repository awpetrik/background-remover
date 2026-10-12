# eraser-remake

Remake Background Eraser (handyCloset) sebagai aplikasi Android baru: Kotlin + Compose Material 3 untuk UI, Rust untuk pengolahan gambar dan inference, model AI berformat ONNX yang bisa ditambah lewat unduhan.

Dokumen:
- `REMAKE_DESIGN.md` design doc (keputusan, layar, fase)
- `ARCHITECTURE_KOTLIN_RUST_AI.md` ADR-001 keputusan stack
- `BASECODE_ANALYSIS.md` catatan bedah APK asli

Status: Fase 4 selesai. Semua fase pembangunan (0 sampai 4) tuntas; lihat design doc bagian 9 untuk cakupan tiap fase dan catatan penyimpangan Fase 4.

Build rilis:

    scripts/build_release.sh               # butuh app/key.properties (keystore sendiri)
    scripts/build_release.sh --allow-unsigned   # APK unsigned, hanya untuk audit isi

Rilis memakai R8 + shrinkResources, versi 1.0.0 (versionCode 2). Tanpa key.properties skrip berhenti dengan pesan eksplisit; tidak ada fallback ke kunci debug. Lint lolos dengan baseline yang hanya memuat 3 temuan NewApi pada kode generate UniFFI (pemilihan Cleaner lewat refleksi dengan fallback JNA).

Alat Objek punya dua mode. Deteksi menjalankan model segmentasi instans format YOLO-seg (pasca-proses NMS dan perakitan mask di Rust), menampilkan daftar objek yang bisa dipilih banyak sekaligus, lalu Simpan Terpilih atau Hapus Terpilih. Ketuk menjalankan MobileSAM: sesi prompt dengan titik positif/negatif (maks 20), pratinjau mask langsung di kanvas, lalu Simpan Wilayah atau Hapus Wilayah. Mask dari AI, deteksi, dan SAM dirapikan dengan guided filter mengikuti tepi foto. Model Objek bersifat install-only lewat Manajer Model; model SAM dikemas sebagai zip berisi `encoder.onnx` dan `decoder.onnx`.

Model Fase 3 di `models/sam/` (MobileSAM, Apache-2.0, ekspor lokal `tools/export_mobilesam.py`, paritas IoU 1,000) dan `models/instance/` (YOLO11n-seg, AGPL-3.0, hanya unduhan opsional). Provenans masing-masing di NOTICE.md di foldernya.

Yang sudah jalan: proyek lokal (Room) dengan autosave, Photo Picker, editor satu layar dengan 8 alat (Objek tampil nonaktif sampai Fase 3), undo/redo tile 256px di Rust dengan satu goresan sebagai satu langkah, ekspor PNG/JPG ke galeri (MediaStore) dan bagikan. Alat AI menjalankan model saliency ONNX lewat crate `ort` (XNNPACK) dengan progres, Balik Hasil, dan Rapikan Tepi 0..5; Warna menghapus warna serupa secara global dan Magic menghapus wilayah terhubung, keduanya lewat ketukan di foto. Manajer Model mengelola model terpasang (Room v2), katalog JSON, dan unduhan WorkManager dengan verifikasi SHA-256.

Model bawaan `models/bundled/u2netp.onnx` adalah hasil konversi sendiri dari bobot resmi U-2-Net (Apache-2.0); provenans dan cara reproduksi ada di `models/bundled/NOTICE.md` dan `tools/export_model.py`.

Library ONNX Runtime tidak dikomit. Jalankan sekali sebelum build:

    tools/fetch_onnxruntime.sh

Skrip itu mengunduh ONNX Runtime 1.28.0 (AAR Maven untuk Android arm64 dan tarball Linux x64 untuk tes host) ke `rust/eraser-core/third_party/onnxruntime/`. Build Android menyalin `libonnxruntime.so` ke APK lewat `rust/build-android.sh`; tes JVM membaca library Linux dari path yang sama.

## Struktur

```
app/                Aplikasi Android (satu modul Gradle)
rust/eraser-core/   Crate Rust, output liberaser_core.so
rust/build-android.sh
models/             Katalog dan model bawaan (Fase 2)
tools/              Skrip konversi model (Fase 2)
basecode/           Ekstrak decompile APK asli, bahan referensi, tidak masuk build
```

## Prasyarat

- JDK 17
- Android SDK + NDK (target arm64-v8a, API 29)
- Rust stable dengan target `aarch64-linux-android`:

```
rustup target add aarch64-linux-android
```

## Build

File biner tidak disimpan di git, siapkan dulu sebelum build pertama:

1. Wrapper jar Gradle: jalankan `gradle wrapper --gradle-version 9.6.0` sekali dengan Gradle lokal, atau buka proyek di Android Studio.
2. Model bawaan: jalankan tools/export_model.py (lihat docstring skripnya), lalu salin hasilnya ke app/src/main/assets/models/u2netp.onnx.
3. Library ONNX Runtime: tools/fetch_onnxruntime.sh.

```
./gradlew :app:assembleDebug     # .so Rust ikut dibangun lewat task buildRustSo
./gradlew :app:testDebugUnitTest # termasuk tes jembatan UniFFI melawan build host Rust
```

Katalog model untuk rilis ada di models/catalog-v1/catalog.json; URL-nya menunjuk ke GitHub Release `models-v1` di repo ini (file yolo11n-seg.onnx dan mobilesam.zip di folder yang sama, siap diunggah sebagai aset rilis).

Binding Kotlin UniFFI ter-generate dan dikomit di `app/src/main/java/uniffi/eraser_core/`. Setelah mengubah API Rust, generate ulang:

```
cd rust/eraser-core
cargo build --lib
cargo run --bin uniffi-bindgen -- generate \
  --library target/debug/liberaser_core.so \
  --language kotlin --out-dir /tmp/bindgen-out
cp /tmp/bindgen-out/uniffi/eraser_core/eraser_core.kt \
  ../../app/src/main/java/uniffi/eraser_core/
```

## Catatan

- Package `id.eraserremake.app` adalah placeholder (design doc, masalah O-1). Nama dan package final diputuskan sebelum rilis pertama.
- Nama field pada error Rust jangan `message`: binding hasil generate akan bertabrakan dengan properti `Throwable.message` di Kotlin. Di crate ini field-nya bernama `detail`.
