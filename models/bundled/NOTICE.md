# Model terbundel: U2NetP

| Item | Nilai |
|---|---|
| Model | U2NetP (varian ringan dari U-2-Net) |
| Penulis | Xuebin Qin, Zichen Zhang, Chenyang Huang, Masood Dehghan, Osmar R. Zaiane, Martin Jagersand |
| Sumber kode dan bobot | Repo resmi https://github.com/xuebinqin/U-2-Net (Apache-2.0); bobot `u2netp.pth` dari tautan Google Drive resmi di README repo (file id `1rbSTGKAE-MTxBYHd-51l2hMOQPT_7EPy`) |
| Lisensi | Apache License 2.0. Teks lisensi ada di repo sumber; verifikasi dilakukan 2026-10-12 dari file LICENSE repo dan metadata GitHub (SPDX: Apache-2.0) |
| File di sini | `u2netp.onnx`, hasil konversi lokal dengan `tools/export_model.py` (opset 14, input `input` 1x3x320x320, output `output` mask 1x1x320x320) |
| SHA-256 file | `87d7f434f1e9c09f130e342815f1cc795e5d19129d41111e3873255f14378cfa` |
| Verifikasi konversi | State dict termuat strict ke arsitektur resmi (1.131.181 parameter); selisih numerik maksimum PyTorch vs ONNX 2,99e-05; uji semantik gambar sintetis lulus (mask 1,0 di subjek, 0,0 di latar) |
| Pra-proses wajib | Resize 320x320, skala [0,1], normalisasi mean [0.485, 0.456, 0.406] dan std [0.229, 0.224, 0.225] |
| Pasca-proses wajib | Normalisasi min-max mask ke [0,1] (normP pada kode resmi) |

Model `.plsml` dari aplikasi acuan tidak dipakai dalam bentuk apa pun. File ini
dihasilkan ulang dari bobot resmi di atas dan dapat direproduksi dengan
menjalankan ulang skrip ekspor pada bobot yang sama.
