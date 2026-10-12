#!/usr/bin/env python3
"""Ekspor MobileSAM ke dua ONNX untuk eraser-remake: encoder + decoder.

Sumber: repo resmi MobileSAM (https://github.com/ChaoningZhang/MobileSAM,
Apache-2.0), bobot `weights/mobile_sam.pt` dari repo yang sama. Repo di-clone
ke tools/work/mobilesam-repo oleh langkah persiapan (lihat README proyek).

Keluaran (ke models/sam/):
- encoder.onnx: input `image` 1x3x1024x1024, output `image_embeddings` 1x256x64x64
- decoder.onnx: input image_embeddings, point_coords, point_labels,
  mask_input, has_mask_input, orig_im_size; output mask ukuran asli + skor.

Pra-proses SAM (dilakukan engine Rust): resize sisi terpanjang ke 1024,
normalisasi mean/std SAM, padding kanan-bawah ke 1024x1024. Koordinat titik
diskalakan ke ruang 1024 yang sama.

Pakai:
    python export_mobilesam.py --repo work/mobilesam-repo --out ../models/sam
"""

import argparse
import hashlib
import os

import sys


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="path clone MobileSAM")
    ap.add_argument("--out", required=True, help="direktori keluaran")
    args = ap.parse_args()

    repo = os.path.abspath(args.repo)
    checkpoint = os.path.join(repo, "weights", "mobile_sam.pt")
    if not os.path.isfile(checkpoint):
        print(f"checkpoint tidak ada: {checkpoint}", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)
    sys.path.insert(0, repo)

    import torch

    from mobile_sam import sam_model_registry

    sam = sam_model_registry["vit_t"](checkpoint=checkpoint)
    sam.eval()

    # 1) Encoder.
    encoder_path = os.path.join(args.out, "encoder.onnx")
    dummy = torch.randn(1, 3, 1024, 1024)
    with torch.no_grad():
        ref = sam.image_encoder(dummy)
    print(f"encoder output: {tuple(ref.shape)}")
    torch.onnx.export(
        sam.image_encoder,
        dummy,
        encoder_path,
        input_names=["image"],
        output_names=["image_embeddings"],
        opset_version=17,
        dynamo=False,
    )
    print(f"tertulis: {encoder_path} ({os.path.getsize(encoder_path)} byte)")
    print(f"sha256: {sha256_of(encoder_path)}")

    # 2) Decoder: prompt encoder + mask decoder dibungkus OnnxModel resmi,
    # diekspor dengan exporter legacy (skrip repo kini memakai torch.export
    # yang konflik dengan kode model ini).
    decoder_path = os.path.abspath(os.path.join(args.out, "decoder.onnx"))
    from mobile_sam.utils.onnx import SamOnnxModel as OnnxModel

    onnx_model = OnnxModel(sam, return_single_mask=True)
    onnx_model.eval()
    dummy_inputs = (
        torch.randn(1, 256, 64, 64),  # image_embeddings
        torch.tensor([[[128.0, 128.0]]]),  # point_coords (ruang 1024)
        torch.tensor([[1.0]]),  # point_labels
        torch.zeros(1, 1, 256, 256),  # mask_input
        torch.zeros(1),  # has_mask_input
        torch.tensor([256.0, 256.0]),  # orig_im_size (tinggi, lebar)
    )
    torch.onnx.export(
        onnx_model,
        dummy_inputs,
        decoder_path,
        input_names=[
            "image_embeddings",
            "point_coords",
            "point_labels",
            "mask_input",
            "has_mask_input",
            "orig_im_size",
        ],
        output_names=["masks", "scores", "low_res_masks"],
        opset_version=17,
        dynamic_axes={
            "point_coords": {1: "num_points"},
            "point_labels": {1: "num_points"},
            "masks": {2: "height", 3: "width"},
        },
        dynamo=False,
    )
    print(f"tertulis: {decoder_path} ({os.path.getsize(decoder_path)} byte)")
    print(f"sha256: {sha256_of(decoder_path)}")

    # 3) Verifikasi paritas: SamPredictor (PyTorch) vs jalur ONNX pada gambar
    # sintetis (bujursangkar putih di atas latar hitam), prompt satu titik
    # positif di tengah bujursangkar.
    import numpy as np
    import onnxruntime as ort

    from mobile_sam import SamPredictor

    img = np.zeros((256, 256, 3), dtype=np.uint8)
    img[64:192, 64:192] = 255

    predictor = SamPredictor(sam)
    predictor.set_image(img)
    masks_ref, scores_ref, _ = predictor.predict(
        point_coords=np.array([[128, 128]]),
        point_labels=np.array([1]),
        multimask_output=False,
    )
    ref_mask = masks_ref[0]
    print(f"PyTorch: skor {scores_ref[0]:.3f}, mask {int(ref_mask.sum())} px")

    # Jalur ONNX dengan pra-proses SAM yang sama seperti predictor.
    from mobile_sam.utils.transforms import ResizeLongestSide

    transform = ResizeLongestSide(1024)
    resized = transform.apply_image_torch(
        torch.as_tensor(img).permute(2, 0, 1)[None].float()
    )
    x = (resized - sam.pixel_mean) / sam.pixel_std
    pad_h = 1024 - x.shape[2]
    pad_w = 1024 - x.shape[3]
    x = torch.nn.functional.pad(x, (0, pad_w, 0, pad_h)).numpy()

    enc = ort.InferenceSession(encoder_path, providers=["CPUExecutionProvider"])
    emb = enc.run(None, {"image": x})[0]
    print(f"embeddings: {emb.shape}")

    coords = transform.apply_coords(
        np.array([[128.0, 128.0]]), (256, 256)
    ).astype(np.float32)
    # Konvensi predictor: titik padding (0,0) berlabel -1 selalu ditambahkan
    # bila tidak ada prompt kotak. Engine Rust memakai konvensi yang sama.
    coords = np.concatenate([coords, np.array([[0.0, 0.0]], dtype=np.float32)])
    dec = ort.InferenceSession(decoder_path, providers=["CPUExecutionProvider"])
    dec_out = dec.run(
        None,
        {
            "image_embeddings": emb,
            "point_coords": coords[None],
            "point_labels": np.array([[1.0, -1.0]], dtype=np.float32),
            "mask_input": np.zeros((1, 1, 256, 256), dtype=np.float32),
            "has_mask_input": np.array([0.0], dtype=np.float32),
            "orig_im_size": np.array([256.0, 256.0], dtype=np.float32),
        },
    )
    onnx_mask = dec_out[0][0, 0] > 0.0
    print(f"ONNX: mask {int(onnx_mask.sum())} px, skor {float(dec_out[1].flat[0]):.3f}")
    inter = np.logical_and(ref_mask, onnx_mask).sum()
    union = np.logical_or(ref_mask, onnx_mask).sum()
    iou = float(inter) / float(max(union, 1))
    print(f"IoU PyTorch vs ONNX: {iou:.3f}")
    if iou < 0.85:
        print("GAGAL: IoU terlalu rendah", file=sys.stderr)
        return 1
    square = np.zeros((256, 256), dtype=bool)
    square[64:192, 64:192] = True
    cover = float(np.logical_and(onnx_mask, square).sum()) / float(square.sum())
    print(f"c cakupan bujursangkar oleh mask ONNX: {cover:.3f}".replace("c ", ""))
    if cover < 0.8:
        print("GAGAL: mask tidak menutupi subjek", file=sys.stderr)
        return 1
    print("verifikasi MobileSAM OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
