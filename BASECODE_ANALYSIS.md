# Catatan bedah: decompiled_0_s2l1.7z

Tanggal: 2026-10-12
Sumber: arsip `decompiled_0_s2l1.7z` (12 MB, 7.066 file), hasil decompile APK dengan apktool 2.11.1
Ekstrak lokal: `~/workspace/s2l1-remake/basecode/decompiled/`

## 1. Identitas paket

Dari `apktool.yml` dan `AndroidManifest.xml`:

| Field | Nilai |
|---|---|
| Nama aplikasi | Background Eraser |
| Developer | handyCloset |
| Package | `com.handycloset.android.eraser` |
| Versi | 35.3.0 (versionCode 36) |
| Nama file APK asal | `BackgroundEraser-merged.apk` |
| minSdk / targetSdk | 29 / 35 |
| Orientasi | Portrait, `resizeableActivity=false` |
| Application class | `com.handycloset.android.plslibrary.PLsApplication` |

Arsip ini bukan source code. Isinya smali hasil decompile DEX, resource XML, library native, dan satu asset model. Tidak ada proyek Gradle dan tidak ada sumber Kotlin/Java. Nama kelas milik aplikasi masih utuh, tetapi nama method dan field internal sudah ter-obfuscate (misalnya `EraserActivity.E(...)`, `EraserImageView.a()`), jadi logika hanya bisa dibaca sebagian dari tanda tangan method, pemanggilan JNI, layout, dan string.

## 2. Kode milik aplikasi

Kode sendiri kecil: `smali/com/handycloset/` total 18.363 baris smali. Sisanya library pihak ketiga (AndroidX, Play Services/AdMob, Firebase, LiteRT, runtime Kotlin) dan resource bawaan AppCompat/Material.

Kelas di `com.handycloset.android.eraser`:

| Kelas | Baris smali | Peran |
|---|---|---|
| `MainActivity` | 1.944 | Layar awal |
| `AlbumListActivity` | 1.138 | Daftar album galeri bawaan |
| `ImageListActivity` | 949 | Daftar foto dalam album |
| `CropActivity` + `CropImageView` | 1.071 + 1.722 | Crop |
| `EraserActivity` | 6.142 | Editor utama, kelas terbesar |
| `EraserImageView` | 2.237 | Kanvas editor (gambar, kursor, gestur) |
| `FeatherActivity` | 1.455 | Smooth Edge |
| `SaveActivity` | 1.019 | Simpan dan bagikan |
| `PLsAcknowledgementsActivity` | 158 | Lisensi pihak ketiga |

Di `com.handycloset.android.plslibrary` ada `PLsApplication`, `PLsGMSCheckActivity`, dan `PLsAdaptiveBannerLayout`. Awalan PLs muncul di semua nama resource (`pls_*`) dan juga di package aplikasi lain milik developer yang sama (`com.handycloset.android.photolayers`, `.stickers`, `.jpeg`, disebut di manifest `<queries>` dan di layar Save). Ini library bersama mereka, bukan bagian dari fitur eraser itu sendiri.

## 3. Alur dan fitur

Alurnya linear: Main, pilih foto, Crop, Eraser, Smooth Edge, Save.

MainActivity berisi lima tombol teks: How to use, FAQ, Help & Feedback (membuka email), "Select Photo (Default Photo Picker)", dan "Select Photo (Transparent color supported)". Jalur kedua membuka galeri bawaan (AlbumList, ImageList, RecyclerView) yang menangani akses foto terbatas Android 14+ lewat tombol "Select photos you allow this app to access" dan "Photos access settings".

CropActivity: crop bebas dengan pesan "Crop the unnecessary parts". Tidak ada pilihan rasio di layout-nya.

EraserActivity adalah layar inti. Enam mode, dipilih dari baris tombol bawah di `res/layout/activity_eraser.xml`:

| Mode | String | Kontrol | Implementasi |
|---|---|---|---|
| AI-Auto | `ai_auto` | tidak ada slider | Segmentasi model, bagian 4 |
| Auto-Color | `auto_color` | slider `auto_threshold` "Threshold of Similar Color" | `nativeAuto` |
| Magic | `magic` | slider `magic_seek_bar_title` "Expectant Magic Size" | `nativeMagic` |
| Manual | `manual` | slider `manual_size` | Brush pada `EraserImageView` |
| Repair | `repair` | slider `repair_size` | Brush pemulihan |
| Zoom | `zoom` | tidak ada slider | Pan/zoom kanvas |

Kontrol lain di layar yang sama: slider Cursor Offset yang menempel permanen di top bar, tombol BgColor untuk mengganti latar pratinjau (pola transparan atau warna), Undo, Redo, Help, Done. Kanvasnya `EraserImageView` di atas `View` berpola checkerboard (`pls_transparent_repeat_bright`). View ini memegang dua bitmap, `currentBitmap` dan `originalBitmap`, dan status kursor seperti `cursorOffsetDip`, `cursorRadiusDip`, `magicRadiusDip`.

Pesan error AI ada di string `error_ai_selfie`: "No object for cutout was found."

FeatherActivity (judul layar "Smooth Edge"): pilihan level Smooth 0 sampai 5 sebagai enam tombol angka, plus BgColor. Tombol kanan atasnya bertuliskan Save, jadi layar ini sekaligus titik simpan alternatif.

SaveActivity (judul "Save & Share"): menampilkan hasil, tombol Finish, dan tiga tombol yang membuka aplikasi lain milik developer yang sama: "[App] Change Background, Superimpose" (PhotoLayers), "[App] White Background, Save as JPEG", "[App] Use as WhatsApp Sticker". Hasil simpan masuk ke folder `Pictures/Eraser` (string `save_directory`), pesan suksesnya "Saved !".

Format gambar yang didukung menurut string `load_error`: PNG, BMP, GIF, WebP, JPG.

## 4. AI dan kode native

Library native di `lib/arm64-v8a/` (hanya ABI ini):

| File | Ukuran | Isi |
|---|---|---|
| `liberaser.so` | 331 KB | Lima fungsi JNI milik aplikasi, di bawah |
| `libLiteRt.so` | 5,1 MB | Runtime LiteRT (Google) |
| `libLiteRtClGlAccelerator.so` | 2,7 MB | Akselerator GPU LiteRT |
| `libdatastore_shared_counter.so` | 7 KB | Bawaan AndroidX DataStore |

Fungsi JNI di `liberaser.so`:

```
Java_com_handycloset_android_eraser_EraserActivity_nativeAuto([IIIIII)Z
Java_com_handycloset_android_eraser_EraserActivity_nativeMagic([I[IIIIII)Z
Java_com_handycloset_android_eraser_FeatherActivity_nativeSmooth
Java_com_handycloset_android_eraser_FeatherActivity_nativeClip1Px
Java_com_handycloset_android_eraser_FeatherActivity_nativeRemoveSpike
```

`nativeAuto` dan `nativeMagic` menerima array piksel `int[]` beserta lebar/tinggi, artinya piksel disalin dari JVM ke native pada setiap pemanggilan. Tiga fungsi Feather bekerja pada mask tepi: penghalusan bertingkat, pemotongan 1 piksel tepi, dan pembuangan spike.

Model AI ada di `assets/hc_eraser_ml_4.plsml`, 4.774.172 byte. Isinya file TFLite: magic `TFL3` di offset 4. String di dalam model menunjukkan arsitektur U2NETP (`model.u2net.U2NETP_wrapped`), dikonversi dengan litert_torch, dengan nama tensor `serving_default`, input `args_0`, output `output_0`. Model dimuat dari kelas ter-obfuscate `smali/I0/f.smali` yang mereferensikan `com.google.ai.edge.litert.CompiledModel` dan `Accelerator`, jadi inference berjalan lewat API CompiledModel LiteRT dengan pilihan akselerator CPU/GPU/NPU.

U2-Net adalah model riset terbuka, jadi arsitektur yang sama bisa dipakai ulang dari bobot publik. File `.plsml` di arsip ini adalah hasil konversi developer aplikasi ini dan tidak dipakai di remake.

## 5. Iklan dan layanan pihak ketiga

- AdMob: adaptive banner muncul di layout Main, Crop, Eraser, Feather, Save, dan galeri. App ID AdMob tertanam di string resources (tidak disalin ke dokumen ini). Ada juga `offline_ads_dialog.xml`, menu Consent untuk iklan EEA, dan rating prompt bawaan library PLs.
- Firebase: Analytics dan Crashlytics terdaftar di manifest, tetapi keduanya dimatikan lewat meta-data `firebase_analytics_collection_enabled=false` dan `firebase_crashlytics_collection_enabled=false`.
- Izin: INTERNET, ACCESS_NETWORK_STATE, READ_EXTERNAL_STORAGE (maks SDK 32), READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED, WAKE_LOCK, FOREGROUND_SERVICE dan FOREGROUND_SERVICE_DATA_SYNC.
- `uses-feature android.hardware.npu` dengan `required=false`.

## 6. UI yang teramati dari resource

Tema yang dipakai keluarga `PLsAppTheme...ActionBar` berbasis AppCompat, bukan Material Components. Nilai warna utama dari `res/values/colors.xml`:

| Resource | Nilai |
|---|---|
| `pls_main_background` | `#f1f1f1` |
| `pls_light_blue` (primary) | `#007aff` |
| `pls_dark_blue` (primary dark) | `#004488` |
| `pls_accent_blue` | `#0000ff` |
| `pls_main_button_text_enabled` | `#212121` |
| `pls_status_bar` | `#000000` |

Semua tombol adalah `TextView` dengan `drawableTop` atau `drawableLeft` dan style khusus (`PLsMainButton`, `PLsBarImageButton`, `PLsEraserModeSelectButton`). Tidak ada Button atau IconButton Material di layout milik aplikasi. Top bar dan bottom bar tersusun manual dari ConstraintLayout, `View` pemisah 1-2dp, dan Guideline persen (di editor: 20%, 74%, 87%).

Tinggi chrome di editor, dari layout: top bar 56dp termasuk slider offset permanen, baris kontrol BgColor/slider/Undo/Redo, baris pemilih 6 mode, lalu banner iklan beserta margin atas-bawahnya. Kanvas mendapat sisa ruang setelah semuanya.

Tidak ditemukan tema gelap khusus aplikasi, dynamic color, atau komponen Material 3 di resource.

## 7. Batasan catatan ini

Karena sumbernya smali ter-obfuscate, nilai ambang dan urutan langkah di dalam `nativeAuto`, `nativeMagic`, dan fungsi Feather tidak bisa dibaca persis; yang tercatat di sini adalah tanda tangan dan perilaku yang teramati dari layout, string, dan pemanggilan. Parameter model (ukuran input tensor, normalisasi) juga tidak diverifikasi dari file ini. Untuk remake, perilaku fungsi-fungsi itu ditulis ulang dari spesifikasinya (flood fill toleransi, region growing, penghalusan mask), bukan diterjemahkan dari smali.
