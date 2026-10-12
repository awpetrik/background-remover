#!/usr/bin/env python3
"""Ekspor U2NetP (PyTorch) ke ONNX untuk eraser-remake.

Sumber bobot dan definisi arsitektur: repo resmi U-2-Net
(https://github.com/xuebinqin/U-2-Net, Apache-2.0). Skrip ini mengunduh
`model/u2net.py` resmi ke tools/work/ bila belum ada, memuat bobot
`u2netp.pth`, dan mengekspor hanya output utama (d0) sebagai tensor mask.

Pra-proses yang harus menyertai model ini (dilakukan engine Rust):
resize 320x320, skala ke [0,1], normalisasi mean/std ImageNet.
Pasca-proses: normalisasi min-max mask ke [0,1] (normP di kode resmi).

Pakai:
    python export_model.py --weights work/u2netp.pth --out ../models/bundled/u2netp.onnx
"""

import argparse
import hashlib
import os
import sys
import urllib.request

WORK_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work")
U2NET_PY_URL = (
    "https://raw.githubusercontent.com/xuebinqin/U-2-Net/master/model/u2net.py"
)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_u2net_source():
    os.makedirs(WORK_DIR, exist_ok=True)
    target = os.path.join(WORK_DIR, "u2net.py")
    if not os.path.exists(target):
        print(f"mengunduh definisi model resmi -> {target}")
        urllib.request.urlretrieve(U2NET_PY_URL, target)
    return target


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", required=True, help="path u2netp.pth resmi")
    ap.add_argument("--out", required=True, help="path keluaran .onnx")
    ap.add_argument("--opset", type=int, default=14)
    args = ap.parse_args()

    import torch

    source = ensure_u2net_source()
    sys.path.insert(0, WORK_DIR)
    import importlib.util

    spec = importlib.util.spec_from_file_location("u2net_official", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    net = module.U2NETP(3, 1)
    state = torch.load(args.weights, map_location="cpu")
    net.load_state_dict(state)
    net.eval()
    params = sum(p.numel() for p in net.parameters())
    print(f"U2NETP dimuat: {params} parameter")
    if not (1_000_000 < params < 1_300_000):
        print(
            "PERINGATAN: jumlah parameter di luar rentang U2NETP (~1,13 juta). "
            "File bobot mungkin bukan u2netp.pth.",
            file=sys.stderr,
        )

    class Wrapper(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, x):
            return self.inner(x)[0]

    wrapped = Wrapper(net)
    wrapped.eval()
    dummy = torch.randn(1, 3, 320, 320)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    torch.onnx.export(
        wrapped,
        dummy,
        args.out,
        input_names=["input"],
        output_names=["output"],
        opset_version=args.opset,
        dynamic_axes=None,
        dynamo=False,
    )
    print(f"tertulis: {args.out} ({os.path.getsize(args.out)} byte)")
    print(f"sha256: {sha256_of(args.out)}")

    # Verifikasi numerik PyTorch vs ONNX pada input acak yang sama.
    import numpy as np
    import onnxruntime as ort

    with torch.no_grad():
        ref = wrapped(dummy).numpy()
    sess = ort.InferenceSession(args.out, providers=["CPUExecutionProvider"])
    got = sess.run(None, {"input": dummy.numpy()})[0]
    diff = float(np.abs(ref - got).max())
    print(f"selisih maks PyTorch vs ONNX: {diff:.2e}")
    if diff > 1e-3:
        print("GAGAL: selisih terlalu besar", file=sys.stderr)
        return 1
    print("verifikasi numerik OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
