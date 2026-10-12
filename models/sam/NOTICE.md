# Model prompt titik: MobileSAM

| Item | Nilai |
|---|---|
| Model | MobileSAM (encoder TinyViT + decoder SAM) |
| Penulis | Chaoning Zhang et al. |
| Sumber | Repo resmi https://github.com/ChaoningZhang/MobileSAM (Apache-2.0), bobot `weights/mobile_sam.pt` dari repo yang sama |
| Lisensi | Apache License 2.0, terverifikasi 2026-10-12 dari metadata GitHub dan file LICENSE repo |
| File di sini | `encoder.onnx` (27.969.729 byte, SHA-256 `b7ff12c78aa8cc78d56e534a72d9c9363a013410d2433cd92031691560140290`) dan `decoder.onnx` (16.501.687 byte, SHA-256 `7954c85151c6ad8294be232c3c697efc99a8d913e364279a29eeb2d388bb1820`), hasil ekspor lokal dengan `tools/export_mobilesam.py` (opset 17) |
| Verifikasi | Paritas penuh dengan SamPredictor PyTorch pada gambar uji sintetis: IoU mask 1,000 dan cakupan subjek 1,000 |
| Kontrak | Encoder: input `image` 1x3x1024x1024 (resize sisi terpanjang 1024, normalisasi mean/std SAM, padding kanan-bawah), output `image_embeddings` 1x256x64x64. Decoder: input embeddings, `point_coords` (ruang 1024, titik padding (0,0) berlabel -1 ditambahkan sesuai konvensi predictor), `point_labels`, `mask_input` nol, `has_mask_input` 0, `orig_im_size`; output `masks` logits ukuran asli (ambang 0), `scores`, `low_res_masks` |
| Distribusi | Model install-only lewat Manajer Model (paket zip berisi encoder.onnx dan decoder.onnx), tidak terbundel di APK karena ukuran gabungan sekitar 44 MB |
