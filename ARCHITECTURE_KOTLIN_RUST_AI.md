# ADR-001: Kotlin untuk UI, Rust untuk core, ONNX untuk model

Status: diterima (2026-10-12)
Konteks: remake Background Eraser, lihat `BASECODE_ANALYSIS.md` dan `REMAKE_DESIGN.md`

## Keputusan

Aplikasi dibagi tiga lapis:

- UI dan integrasi Android di Kotlin (Compose Material 3).
- Pengolahan gambar dan inference di Rust, satu crate yang dikompilasi jadi `liberaser_core.so`, dipanggil lewat UniFFI.
- Model AI didistribusikan sebagai file ONNX dan dijalankan dengan ONNX Runtime lewat crate `ort`. Model ditambah lewat unduhan dari katalog, bukan lewat update aplikasi.

Rust di sini adalah library on-device. Tidak ada server di v1. Kalau nanti ada kebutuhan server, bentuknya layanan katalog file model; inference tetap di perangkat.

Training dan konversi model tetap di Python (PyTorch, ekspor ke ONNX, kuantisasi). Tidak ada rencana melatih model di Kotlin atau Rust.

## Alasan

Pengolahan gambar di aplikasi ini isinya operasi per piksel pada buffer besar: flood fill, region growing, brush alfa, blur mask. Di Kotlin operasi seperti ini kalah cepat dari kode native dan menekan GC. APK asli pun memakai native (`liberaser.so`, C++) untuk persis fungsi-fungsi ini.

Rust dipilih di atas C++ karena dua hal: operasi buffer mentah di C++ adalah sumber bug memori yang umum, dan rayon membuat paralelisasi per tile mudah ditulis dengan aman. Kecepatan mentahnya setara. Kekurangan Rust di Android nyata tapi terbatas: toolchain build bertambah (cargo-ndk, UniFFI) dan contoh kodenya lebih sedikit. Untuk proyek ini keduanya bisa diterima.

Format model menentukan runtime. ONNX dipilih karena hampir semua model yang relevan tersedia dalam format ini: U2NetP, BiRefNet, RMBG, YOLO11 (deteksi dan instance segmentation), MobileSAM. Satu runtime melayani semua model di katalog, dan model baru bisa diterbitkan sebagai file.

## Alternatif yang dipertimbangkan

LiteRT dari Kotlin. Ini jalur yang dipakai APK asli (CompiledModel, akselerator CPU/GPU/NPU) dan akselerasi GPU-nya paling matang di Android. Ditolak sebagai jalur utama karena model harus dikonversi ke `.tflite` satu per satu, pilihan modelnya lebih sempit, dan inference akan tinggal di lapis Kotlin sementara sisa core di Rust, jadi status gambar menyeberangi dua runtime. LiteRT tetap opsi bila ada satu model spesifik yang terbukti jauh lebih cepat di sana; keputusan itu perlu datanya dulu.

tract-onnx (runtime ONNX murni Rust). Ukuran `.so` lebih kecil karena tidak ada library C++, tapi cakupan operator dan performanya di bawah ONNX Runtime. Model katalog yang kompleks berisiko gagal dimuat. Tidak dipakai.

Candle. Kuat untuk model transformer, belum matang untuk segmentation CNN di perangkat mobile. Tidak dipakai.

C++ untuk core. Lihat alasan di atas. Tidak ada kebutuhan OpenCV di aplikasi ini; semua algoritmanya cukup ditulis langsung.

Server inference (Axum + `ort` di server). Bisa menjalankan model yang terlalu berat untuk HP, misalnya BiRefNet ukuran penuh atau SAM 2. Ditolak untuk v1: foto harus diunggah, aplikasi mati tanpa koneksi, dan ada biaya server yang berjalan terus. Bisa ditambah belakangan sebagai mode opsional tanpa mengubah arsitektur, karena manajer model sudah memisahkan sumber model dari pemakaian model.

## Konsekuensi

- Build memerlukan toolchain Rust dan cargo-ndk di mesin build dan CI. Waktu build bertambah.
- `libonnxruntime.so` menambah belasan MB ke APK. Dikendalikan dengan target arm64 saja dan model besar tidak dibundel.
- Versi crate `ort` di-pin persis. Seri 2.0-rc pernah mengubah API antar rilis patch; naik versi harus lewat perubahan terpisah dengan tes kontrak model dijalankan ulang.
- Crash di kode native lebih sulit dibaca daripada crash Kotlin. Simbol rilis disimpan per versi.
- Deteksi objek (bounding box) tidak menghasilkan mask. Model deteksi dipakai untuk memberi daftar objek dan titik prompt; mask selalu dari model segmentasi. Alur alat Objek di design doc mengikuti ini.
