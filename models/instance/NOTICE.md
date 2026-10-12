# Model segmentasi instans: YOLO11n-seg

| Item | Nilai |
|---|---|
| Model | YOLO11n-seg (Ultralytics), 80 kelas COCO |
| Sumber bobot | Rilis resmi Ultralytics (`yolo11n-seg.pt`), diekspor ke ONNX (input 640x640) dengan perangkat Ultralytics |
| Lisensi | AGPL-3.0 (lisensi Ultralytics untuk bobot dan perangkatnya). Konsekuensi: file model ini hanya didistribusikan sebagai unduhan opsional yang dipilih pengguna, tidak terbundel di APK. Keputusan distribusi aplikasi secara keseluruhan perlu meninjau ulang status ini sebelum rilis publik |
| File di sini | `yolo11n-seg.onnx`, SHA-256 `2c9a7e9bde8045020015cddc56b16fd6cfdba4815f78c0061cdb47b6de61560c` |
| Kontrak | Input `images` 1x3x640x640 (letterbox, skala /255). Output `output0` 1x116x8400 (4 kotak + 80 skor kelas + 32 koefisien mask per kandidat) dan `output1` 1x32x160x160 (prototipe mask). Pasca-proses (ambang 0,25, NMS IoU 0,45, rakit mask dari prototipe) diimplementasikan di Rust dan diuji terhadap keluaran referensi Ultralytics pada foto uji yang sama (5 deteksi: 4 person, 1 bus) |
| Distribusi | Model install-only lewat Manajer Model |
