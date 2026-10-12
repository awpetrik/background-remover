# Design doc: remake Background Eraser

Status: Fase 4 selesai (2026-10-12). Semua fase pembangunan selesai; tersisa keputusan pemilik (nama/package final O-1, hosting katalog O-5) dan uji perangkat fisik
Tanggal: 2026-10-12
Dokumen terkait: `BASECODE_ANALYSIS.md` (hasil bedah APK asli), `ARCHITECTURE_KOTLIN_RUST_AI.md` (keputusan stack)

## 1. Ringkasan

Remake dari Background Eraser (handyCloset) sebagai aplikasi Android baru. Kotlin + Compose Material 3 untuk UI, Rust untuk pengolahan gambar dan inference, model AI dalam format ONNX yang bisa ditambah lewat unduhan. Semua proses berjalan on-device; tidak ada server dan tidak ada iklan di v1.

APK asli dipakai sebagai acuan perilaku saja. Tidak ada kode smali, asset, ikon, teks, atau file model dari APK asli yang dipakai ulang.

## 2. Keputusan yang sudah diambil

| ID | Keputusan |
|---|---|
| D-1 | UI Kotlin + Jetpack Compose Material 3, satu Activity, Compose Navigation |
| D-2 | Pengolahan gambar dan inference di Rust (satu crate, satu `.so`), dipanggil dari Kotlin lewat UniFFI |
| D-3 | Format model ONNX, runtime ONNX Runtime lewat crate `ort`. Training dan konversi model di Python, di luar build app |
| D-4 | Target arm64-v8a saja, minSdk 29 |
| D-5 | Tanpa iklan, tanpa akun, tanpa analitik di v1. Monetisasi dibahas lagi setelah v1 rilis |
| D-6 | Editor satu layar. Crop dan smooth edge menjadi alat/panel di dalam editor, bukan layar terpisah |
| D-7 | Proyek tersimpan otomatis. Tidak ada dialog konfirmasi keluar editor |
| D-8 | Eksekusi per fase dengan gerbang approval. Build hanya atas perintah, selalu release |

## 3. Masalah terbuka

| ID | Masalah | Rekomendasi |
|---|---|---|
| O-1 | Nama dan package aplikasi belum ada | Putuskan sebelum Fase 0 selesai, package tidak bisa diganti setelah rilis. Placeholder scaffold: `id.eraserremake.app` |
| O-2 | Bahasa UI | Bahasa Indonesia sebagai default, string lewat resources agar English bisa ditambah tanpa ubah kode |
| O-3 | Lisensi bobot U2NetP untuk model bawaan | SELESAI 2026-10-12: repo resmi xuebinqin/U-2-Net berlisensi Apache-2.0 (file LICENSE dan metadata GitHub). Bobot resmi diunduh dari tautan Google Drive di README repo, dikonversi sendiri dengan tools/export_model.py, dan diverifikasi (1.131.181 parameter, selisih numerik vs PyTorch 2,99e-05, uji semantik lulus). Provenans lengkap di models/bundled/NOTICE.md |
| O-5 | Hosting katalog model dan file .onnx unduhan | Belum dipilih. Aplikasi sudah mendukung URL katalog yang diisi pengguna di Manajer Model; kandidat utama adalah GitHub Releases di repo milik Rivaldi (gratis, HTTPS, hash tetap diverifikasi di aplikasi). Tanpa hosting, katalog aktif adalah katalog bawaan aset yang hanya memuat model terbundel |
| O-4 | Warna seed fallback untuk perangkat tanpa dynamic color | Nilai sementara `#0B6B4F`, diganti setelah O-1 beres |

## 4. Ruang lingkup

Masuk v1: pilih foto, editor 8 alat, hapus background AI, manajer model, simpan/bagikan PNG dan JPG, proyek lokal, pengaturan dasar.

Di luar v1: server dan mode cloud, iOS/desktop, ganti background dengan gambar lain, kolase dan stiker, training model dari dalam aplikasi, sinkronisasi antar perangkat.

Catatan soal cakupan: mengganti background dan kolase adalah wilayah aplikasi PhotoLayers dari developer yang sama. Kalau nanti mau ditambah, itu fitur v2 dan perlu keputusan sendiri.

## 5. Layar

Lima destinasi: Beranda, Editor, Manajer Model, Pengaturan, Cara Pakai. Ekspor adalah bottom sheet dari Editor.

### 5.1 Beranda

Daftar proyek berupa grid thumbnail dua kolom, diurutkan dari yang terakhir diubah. Aksi utama satu: tombol "Pilih Foto" di bagian bawah layar, membuka Photo Picker sistem. Tidak ada layar perantara pemilihan album seperti aplikasi asli; galeri bawaan mereka tidak dibuat ulang karena Photo Picker sudah menangani izin terbatas Android 14+.

App bar berisi judul, pintasan Manajer Model, pintasan Pengaturan, dan overflow untuk Cara Pakai dan Tentang. Item yang destinasinya belum ada tidak ditampilkan.

Keadaan kosong: satu kalimat penjelasan cara kerja dan tombol Pilih Foto. Proyek dihapus dari menu pada thumbnail, dengan dialog konfirmasi. Menghapus proyek menghapus file kerja internalnya.

### 5.2 Editor

Susunan layar, atas ke bawah: top app bar, kanvas, panel konteks, rel alat.

Top app bar: tombol kembali, undo, redo, overflow (Ganti Foto, Atur Ulang Edit), dan tombol Simpan di kanan. Undo/redo tampil nonaktif saat tumpukannya kosong.

Kanvas menempati sisa ruang. Gambar digambar di atas pola checkerboard. Latar kanvas memakai abu gelap netral yang sama di tema terang dan gelap, karena warna latar yang berubah mengikuti tema membuat penilaian tepi hasil hapus jadi tidak konsisten. Gestur: cubit untuk zoom 0,5x s.d. 8x, geser dua jari untuk pan, satu jari menggambar sesuai alat aktif.

Panel konteks tingginya tetap (64dp) dan isinya milik alat yang sedang aktif. Isi panel maksimal satu slider, selalu dengan nama dan nilai angka. Alasan tinggi tetap: kanvas tidak bergeser naik-turun setiap ganti alat.

Rel alat di bawah panel, delapan alat, target sentuh minimal 48dp, urutan mengikuti frekuensi pakai: AI, Objek, Warna, Magic, Kuas, Pulihkan, Potong, Zoom. Alat aktif ditandai indikator pill Material 3.

Perilaku per alat:

| Alat | Cara kerja | Isi panel | Implementasi |
|---|---|---|---|
| AI | Segmentasi subjek satu ketuk, hasil jadi mask alfa | Pemilih model, tombol Hapus Background, sakelar Balik Hasil, slider Rapikan Tepi 0..5 | ONNX di Rust, refine tepi sesudah mask keluar |
| Objek | Ketuk objek atau pilih dari daftar hasil deteksi, tentukan simpan/hapus per objek | Chip daftar objek hasil deteksi; tombol install bila model objek belum ada | YOLO11n-seg / MobileSAM, Fase 3 |
| Warna | Ketuk titik, semua piksel berwarna mirip di seluruh gambar dihapus | Slider Toleransi 0..100, tombol Ambil Warna | Flood fill toleransi di Rust |
| Magic | Ketuk titik, hanya area tersambung dari titik itu yang dihapus | Slider Toleransi, slider Jangkauan | Region growing di Rust |
| Kuas | Hapus alfa dengan goresan | Slider Ukuran, slider Offset Kursor | Brush di Rust, pratinjau lingkaran mengikuti offset |
| Pulihkan | Kembalikan alfa dari gambar asli dengan goresan | Slider Ukuran, slider Offset Kursor | Sama dengan Kuas, arah alfa dibalik |
| Potong | Bingkai crop di kanvas | Chip rasio (Bebas, 1:1, 4:5, 3:4, 16:9), Terapkan, Batal | Operasi crop pada buffer Rust |
| Zoom | Pan/zoom satu jari | Pas Layar, 100% | Murni sisi Compose |

Dua hal dari aplikasi asli yang sengaja diubah:

1. Aplikasi asli tidak pernah menjelaskan beda Auto-Color dan Magic. Di sini panel Warna menulis "Hapus semua warna yang mirip di seluruh foto" dan panel Magic menulis "Hapus area yang tersambung dari titik yang diketuk". Satu kalimat per alat, di tempat alat itu dipakai.
2. Slider Cursor Offset di aplikasi asli menempel permanen di top bar editor. Di sini offset hanya muncul di panel Kuas dan Pulihkan, karena hanya di dua alat itu offset berpengaruh.

Proses AI di atas 400ms menampilkan progres di atas kanvas dengan tombol Batal. Di bawah itu tidak ada indikator selain perubahan kursor. Pesan "tidak ada objek ditemukan" dari model ditampilkan sebagai teks di panel AI, bukan dialog.

Ganti alat tidak membuang hasil edit. Semua alat bekerja pada satu dokumen dan satu riwayat undo.

### 5.3 Manajer Model

Dua seksi: Terpasang dan Katalog. Satu baris model berisi nama, kegunaan, ukuran, status, dan satu aksi yang relevan dengan statusnya (Install, Jadikan Default, Hapus, atau Batalkan Unduhan). Unduhan yang berjalan menampilkan progres persen pada barisnya.

Di atas daftar ada ringkasan pemakaian storage model dan tombol hapus semua model unduhan. Model bawaan tidak ikut terhapus.

Katalog adalah JSON statis yang diambil dari hosting file dan disimpan cache di perangkat. Saat offline, yang ditampilkan katalog cache terakhir dengan catatan tanggal ambilnya.

Unduhan berjalan lewat WorkManager agar tetap lanjut saat aplikasi ditutup. File yang selesai diunduh diverifikasi SHA-256-nya sebelum statusnya berubah jadi terpasang. File yang gagal verifikasi dihapus dan kesalahannya dicatat.

### 5.4 Ekspor

Bottom sheet dari Editor. Isi: pratinjau akhir, pilihan format PNG (transparan) atau JPG (dengan pemilih warna latar: putih, hitam, kustom), pilihan ukuran Asli atau Kecil (sisi terpanjang 1024px), lalu dua aksi: Simpan ke Galeri dan Bagikan.

Simpan memakai MediaStore ke folder `Pictures/<nama app>`. Selesai simpan, snackbar memberi aksi Lihat. Tidak ada layar selesai dan tidak ada promo aplikasi lain seperti SaveActivity di aplikasi asli.

### 5.5 Pengaturan dan Cara Pakai

Pengaturan berisi yang benar-benar berfungsi saja: tema (Sistem/Terang/Gelap), bahasa, akselerasi AI (Otomatis/CPU saja), haptik alat, ukuran cache proyek dan tombol bersihkan, versi dan lisensi pihak ketiga.

Cara Pakai adalah satu halaman gulir. Isinya penjelasan per alat dengan kalimat yang sama seperti yang tampil di panel editor, diperluas satu paragraf per alat. Tidak ada FAQ.

## 6. Design system

Material 3 dengan dynamic color sebagai sumber skema terang dan gelap. Perangkat tanpa dynamic color memakai seed fallback (O-4). Tidak ada warna aksen di luar skema M3.

Tipografi memakai skala M3 dan font sistem. Font kustom menunggu identitas nama selesai (O-1).

Radius mengikuti skala M3. Bentuk pill hanya untuk indikator alat aktif dan chip, sesuai pemakaian M3, bukan untuk semua tombol dan kartu.

Ikon memakai set Material Symbols dari Compose. Ikon untuk AI harus menunjuk ke tindakan memotong/menghapus; ikon sparkle dan sejenisnya tidak dipakai. Pemilihan ikon final per alat dilakukan saat implementasi, dengan uji sederhana: orang yang belum pernah melihat aplikasi ini harus bisa menebak fungsi alat dari ikon plus labelnya.

Gerakan dibatasi pada transisi panel (200ms), perpindahan destinasi bawaan M3, indikator progres, dan lingkaran kuas. Tidak ada animasi masuk berurutan dan tidak ada animasi dekoratif. Alat ini dipakai berulang untuk tugas yang sama; kecepatan tangan lebih penting daripada kesan pertama.

Aksesibilitas: target sentuh minimal 48dp, semua ikon punya content description, nilai slider terbaca screen reader, label di atas checkerboard memakai latar solid agar kontrasnya lolos AA, dan mode Zoom menyediakan navigasi kanvas satu jari bagi yang tidak bisa gestur cubit. Haptik bisa dimatikan di Pengaturan.

## 7. Arsitektur

### 7.1 Struktur repo

```
eraser-remake/
  app/                  Kotlin + Compose. Satu modul Gradle.
  rust/eraser-core/     Crate Rust, output liberaser_core.so (cdylib)
  models/
    catalog.json        Sumber katalog untuk Manajer Model
    bundled/            Model bawaan APK (u2netp.onnx, setelah O-3 beres)
  tools/
    export_model.py     Konversi dan kuantisasi model ke ONNX, dijalankan manual
    verify_catalog.py   Hitung SHA-256 dan ukuran, perbarui entri katalog
```

Modularisasi Gradle ditunda. Satu modul cukup untuk ukuran aplikasi ini; memecahnya sekarang menambah waktu build tanpa manfaat.

Gradle menjalankan `cargo ndk -t arm64-v8a build --release` bila sumber Rust berubah, dan hasil `.so` masuk ke jniLibs hasil generate. File `.so` tidak dikomit.

### 7.2 Kontrak Kotlin dan Rust

Satu objek sesi per dokumen: `EditorEngine` di Rust, dibuat saat foto dibuka dan dibuang saat proyek ditutup.

```kotlin
interface EditorEngine {
    fun dimensions(): Size
    fun preview(maxPx: Int): ByteArray
    fun applyAi(modelId: String, progress: ProgressCallback): AiResult
    fun invertMask()
    fun refineEdges(level: Int)
    fun autoColor(x: Int, y: Int, tolerance: Int)
    fun magic(x: Int, y: Int, tolerance: Int, radius: Int)
    fun brush(points: List<PointF>, radius: Float, mode: BrushMode, offset: Float)
    fun crop(rect: Rect)
    fun undo(): Boolean
    fun redo(): Boolean
    fun exportPng(): ByteArray
    fun exportJpeg(bgColor: Int): ByteArray
}
```

Catatan kontrak:

- Decode dan encode file gambar dikerjakan codec Android. Yang dikirim ke Rust adalah piksel RGBA lewat direct ByteBuffer. HEIF dan WebP ditangani platform, Rust tidak perlu tahu format file.
- Buffer gambar asli, gambar hasil edit, dan mask kerja tinggal di Rust selama sesi. Kotlin hanya memegang pratinjau. Konsekuensinya, goresan kuas dan undo tidak menyalin bitmap penuh bolak-balik lewat FFI.
- Undo/redo memakai tumpukan tile 256x256 untuk perubahan lokal, dan snapshot mask untuk operasi global seperti AI dan crop. Batas riwayat 30 langkah atau 256 MB. Langkah tertua yang dibuang karena batas ini tidak diumumkan per kejadian; perilakunya ditulis di Cara Pakai.
- Satu goresan kuas adalah satu langkah undo, ditegakkan dengan transaksi `begin_stroke`/`end_stroke` di engine: segmen goresan di antaranya menumpuk snapshot tile dalam satu entry.
- Offset kursor diterapkan di Kotlin dalam ruang layar sebelum titik diubah ke koordinat gambar; tanda tangan `brush` tidak membawa parameter offset.
- Panggilan berat tidak pernah dari main thread. Kotlin memakai dispatcher khusus, Rust memakai thread pool rayon.
- Error menyeberang FFI sebagai hasil terstruktur berisi kode dan kunci pesan; teks tampil diterjemahkan di Kotlin. Panic Rust ditangkap di batas FFI dan diubah jadi error `INTERNAL`.

### 7.3 Engine Rust

Modul di dalam crate:

- `buffer`: buffer RGBA dan mask u8, pool buffer untuk dipakai ulang.
- `ops::flood`: flood fill toleransi warna, BFS per scanline.
- `ops::wand`: region growing untuk Magic.
- `ops::brush`: cap lingkaran bertepi lembut di sepanjang polyline goresan.
- `ops::refine`: erode 1px, blur mask bertingkat, buang spike. Ini padanan `nativeSmooth`, `nativeClip1Px`, `nativeRemoveSpike` dari liberaser bawaan APK asli.
- `ops::guided`: upsample mask dari resolusi inference ke resolusi penuh mengikuti tepi gambar asli.
- `inference`: sesi ONNX Runtime, pra-proses dan pasca-proses per jenis model.

Profil rilis: `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, simbol di-strip dari `.so` rilis (disimpan terpisah per versi untuk membaca crash). Operasi per piksel diparalelkan per tile dengan rayon dan ditulis agar autovektorisasi NEON bekerja. Loop goresan kuas tidak boleh mengalokasikan memori.

### 7.4 Inference dan katalog model

Field `kind` pada entri katalog menentukan jalur pra/pasca-proses di Rust:

| kind | Untuk model | Catatan |
|---|---|---|
| `salient-mask` | U2NetP, BiRefNet, RMBG | Satu mask subjek dominan |
| `instance-seg` | YOLO11n-seg | Mask per objek + label |
| `point-sam` | MobileSAM | Dua file (encoder, decoder), prompt titik dari ketukan |

Model baru yang `kind`-nya sudah didukung bisa ditambah ke katalog tanpa update aplikasi. `kind` baru perlu update aplikasi. Batasan ini ditampilkan di halaman Manajer Model.

Entri katalog: `id`, `name`, `kind`, `description`, `file_url`, `sha256`, `size_bytes`, `input_size`, `version`, `min_app_version`.

Registri model di Rust menerima path file, bukan isi file. Sesi model dimuat malas saat pertama dipakai dan dilepas saat Android mengirim `onTrimMemory`.

Varian model: satu varian per model (FP16). Varian INT8 ditambah per model hanya setelah lolos uji kualitas pada set foto uji, bukan otomatis.

Provider eksekusi: XNNPACK CPU sebagai jalur utama. Provider hardware (NNAPI/QNN) ada di balik toggle eksperimental di Pengaturan. Kegagalan provider hardware selalu jatuh ke XNNPACK dan dicatat di log; proses AI tidak boleh gagal karena provider. Pertimbangannya ada di ADR: NNAPI sudah deprecated sejak Android 15 dan dukungan QNN per perangkat tidak merata, jadi keduanya tidak layak jadi jalur utama.

### 7.5 Data proyek

Room, satu tabel:

```
projects(id, created_at, updated_at, original_path, work_path, thumb_path, width, height)
```

Gambar asli disalin ke penyimpanan internal saat pertama dibuka, karena URI dari Photo Picker tidak berlaku permanen. Selama sesi, status edit ada di Rust. Saat proyek ditutup, buffer hasil edit disimpan sebagai PNG kerja (`work_path`) agar proyek bisa dilanjutkan persis dari titik terakhir.

Jurnal operasi yang bisa di-replay tidak dibuat di v1. Menyimpan PNG kerja lebih sederhana dan cukup untuk fitur lanjutkan proyek.

Ekspor selalu dirender dari buffer Rust resolusi penuh, tidak pernah dari pratinjau.

## 8. Target performa

Angka di bawah ini target, diukur di perangkat uji (satu HP kelas menengah milik Rivaldi, ditetapkan di Fase 0). Hasil ukur yang meleset dilaporkan dengan tahap penyebabnya.

| Metrik | Target | Cara ukur |
|---|---|---|
| Latensi goresan kuas di pratinjau | di bawah 16 ms per frame | Frame counter Compose + timer Rust |
| AI U2NetP, foto 12MP | di bawah 2 detik ujung ke ujung | Log waktu per tahap di engine |
| Warna / Magic, satu ketukan | di bawah 300 ms | Sama |
| Buka proyek 12MP sampai kanvas tampil | di bawah 1 detik | Macrobenchmark |
| Ukuran APK rilis arm64 termasuk model bawaan | di bawah 50 MB | Laporan build |
| Memori puncak saat edit foto 24MP | di bawah 512 MB | Android Profiler |

## 9. Fase

| Fase | Isi | Selesai bila |
|---|---|---|
| 0. Fondasi | Scaffold Gradle + crate Rust, jembatan UniFFI dua arah termasuk callback progres dan error terstruktur, navigasi 5 destinasi, tema M3 | Fungsi Rust terpanggil dari Compose dan sebaliknya, terbukti di perangkat uji. Selesai 2026-10-12: terbukti lewat tes JVM melawan build host Rust (BridgeTest 5/5) dan APK debug memuat `liberaser_core.so` arm64; uji di perangkat fisik menyusul sebagai langkah Rivaldi |
| 1. Editor inti | Beranda, proyek + autosave, Photo Picker, kanvas, Kuas, Pulihkan, Potong, Zoom, undo/redo, ekspor | Alur foto masuk sampai PNG transparan tersimpan berjalan penuh. Selesai 2026-10-12: terverifikasi lewat tes (cargo 16/16, EditorEngineTest 5/5 via binding yang sama) dan assembleDebug; uji perangkat fisik menyusul |
| 2. AI dan seleksi klasik | Manajer Model, U2NetP bawaan, alat AI, Warna, Magic, refine tepi | Hasil AI + koreksi manual lolos set foto uji (orang, produk, rambut). Selesai 2026-10-12: inference asli terverifikasi ujung ke ujung di tes Rust (29/29) dan tes JVM (AiEngineTest 5/5, termasuk applyAi pada gambar sintetis dengan model terbundel). Set foto uji di perangkat fisik menyusul bersama uji perangkat pertama. Library ONNX Runtime yang dibundel adalah build penuh Maven 1.28.0 (28,6 MB); pemangkasan ukuran masuk Fase 4 |
| 3. Objek | Model instance-seg / point-SAM sebagai model installable, alat Objek | Satu objek bisa dipertahankan dan sisanya dihapus dalam satu sesi. Selesai 2026-10-12: pasca-proses YOLO-seg (parsing, NMS per kelas, rakit mask dari prototipe) dan MobileSAM (encoder/decoder ONNX ekspor sendiri, paritas IoU 1,000 vs PyTorch) berjalan penuh di Rust; engine menyimpan deteksi dan sesi prompt (maks 20 titik, embedding satu slot di runtime); mask AI dan objek dirapikan guided filter mengikuti tepi foto. Model SAM didistribusikan sebagai zip (encoder.onnx + decoder.onnx) yang diekstrak DownloadWorker ke direktori model. Bukti: cargo test 41/41 (termasuk deteksi bus pada foto uji: 4 person + 1 bus, dan prompt SAM pada gambar sintetis), ObjekBindingTest JVM 2/2. YOLO11n-seg berlisensi AGPL-3.0 (lihat models/instance/NOTICE.md): hanya unduhan opsional, tidak terbundel |
| 4. Rilis | Pengaturan lengkap, Cara Pakai, string English, uji performa terhadap bagian 8, ikon dan nama final | Semua target terukur atau penyimpangannya tercatat, signing release siap. Selesai 2026-10-12 dengan penyimpangan tercatat berikut. (1) Ukuran APK release 36 MB, di atas target 25 MB: penyebab tunggalnya library ONNX Runtime build penuh 28,6 MB; R8 + shrinkResources sudah aktif (dex 3,0 MB). Menembus target butuh build ONNX Runtime reduced-ops khusus, pekerjaan terpisah di luar fase ini. (2) String English belum dibuat; struktur string sudah siap diterjemahkan, bahasa UI tetap Indonesia (O-2). (3) Nama dan package masih placeholder (O-1) dan ikon masih generik. (4) Signing: konfigurasi membaca app/key.properties dan scripts/build_release.sh menolak build release tanpa keystore (tidak ada fallback kunci debug); APK unsigned hasil mode --allow-unsigned hanya untuk audit isi. (5) Uji performa kanvas dan inference di perangkat fisik belum dilakukan (tidak ada perangkat di lingkungan build); seluruh verifikasi fungsi berjalan di host. Pengaturan kini persisten (tema, NNAPI eksperimental dengan fallback XNNPACK bila EP gagal) dan halaman Cara Pakai final |

Tes per fase: unit test Rust untuk tiap `ops` dengan fixture gambar, unit test Kotlin, dan tes kontrak model (bentuk tensor input/output diverifikasi saat model dimuat, model yang tidak cocok ditolak dengan error jelas).

## 10. Risiko

| ID | Risiko | Penanganan |
|---|---|---|
| R-1 | Lisensi bobot U2NetP publik tidak lolos untuk distribusi | Ganti model default ke model segmentasi berlisensi jelas; U2NetP hanya masuk katalog dengan atribusi sesuai lisensinya. File `.plsml` dari APK asli tidak dipakai dalam keadaan apa pun |
| R-2 | `libonnxruntime.so` belasan MB menekan batas ukuran APK | arm64 saja, model besar tidak dibundel. Kalau masih bengkak, evaluasi build ORT minimal per operator |
| R-3 | Crate `ort` bergerak cepat, seri 2.0-rc pernah breaking antar patch | Versi di-pin persis di Cargo.lock. Naik versi lewat perubahan terpisah dengan tes kontrak model dijalankan ulang |
| R-4 | Foto sangat besar menghabiskan memori | Sisi terpanjang di atas 8000px diturunkan skalanya sebelum edit, dengan pemberitahuan. Tidak ada pemotongan diam-diam |
| R-5 | Kebijakan Play Store soal konten terunduh | Model ONNX adalah data, diunduh dari katalog HTTPS milik sendiri dan diverifikasi hash-nya. Status kebijakan ini diverifikasi ulang sebelum rilis publik |
| R-6 | Crash di kode native sulit dibaca | Simbol rilis disimpan per versi, tidak ada `unwrap` pada jalur input pengguna di Rust |
