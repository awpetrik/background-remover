//! Pasca-proses segmentasi instans format YOLO-seg: parsing kandidat,
//! NMS per kelas, dan perakitan mask dari prototipe. Murni fungsi atas
//! tensor, tanpa sesi ONNX, agar bisa diuji deterministik.

/// Satu kandidat deteksi dalam koordinat ruang input model (mis. 640x640).
#[derive(Debug, Clone)]
pub struct RawDetection {
    pub class_id: usize,
    pub score: f32,
    /// Kotak: pusat x, pusat y, lebar, tinggi (ruang input model).
    pub cx: f32,
    pub cy: f32,
    pub bw: f32,
    pub bh: f32,
    /// Koefisien mask (panjang = jumlah kanal prototipe, biasanya 32).
    pub coeffs: Vec<f32>,
}

impl RawDetection {
    pub fn x0(&self) -> f32 {
        self.cx - self.bw / 2.0
    }
    pub fn y0(&self) -> f32 {
        self.cy - self.bh / 2.0
    }
    pub fn x1(&self) -> f32 {
        self.cx + self.bw / 2.0
    }
    pub fn y1(&self) -> f32 {
        self.cy + self.bh / 2.0
    }
}

/// Parse output YOLO-seg `output0` berbentuk [4 + kelas + koef][kandidat]
/// (channel-major). Menyimpan kandidat dengan skor kelas terbaik >= conf.
pub fn parse_yolo_output(
    output0: &[f32],
    channels: usize,
    candidates: usize,
    num_classes: usize,
    num_coeffs: usize,
    conf: f32,
) -> Vec<RawDetection> {
    debug_assert_eq!(channels, 4 + num_classes + num_coeffs);
    let at = |ch: usize, i: usize| output0[ch * candidates + i];
    let mut out = Vec::new();
    for i in 0..candidates {
        let mut best_class = 0usize;
        let mut best_score = f32::MIN;
        for c in 0..num_classes {
            let s = at(4 + c, i);
            if s > best_score {
                best_score = s;
                best_class = c;
            }
        }
        if best_score < conf {
            continue;
        }
        let coeff_base = 4 + num_classes;
        out.push(RawDetection {
            class_id: best_class,
            score: best_score,
            cx: at(0, i),
            cy: at(1, i),
            bw: at(2, i),
            bh: at(3, i),
            coeffs: (0..num_coeffs).map(|k| at(coeff_base + k, i)).collect(),
        });
    }
    out
}

fn iou(a: &RawDetection, b: &RawDetection) -> f32 {
    let x0 = a.x0().max(b.x0());
    let y0 = a.y0().max(b.y0());
    let x1 = a.x1().min(b.x1());
    let y1 = a.y1().min(b.y1());
    let inter = (x1 - x0).max(0.0) * (y1 - y0).max(0.0);
    let union = a.bw * a.bh + b.bw * b.bh - inter;
    if union <= 0.0 {
        0.0
    } else {
        inter / union
    }
}

/// NMS sadar kelas: urut skor menurun, tekan kotak sekelas yang tumpang
/// tindih di atas ambang IoU. Mengembalikan deteksi yang lolos, tetap
/// terurut skor menurun.
pub fn nms(mut dets: Vec<RawDetection>, iou_threshold: f32) -> Vec<RawDetection> {
    dets.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
    let mut kept: Vec<RawDetection> = Vec::new();
    for det in dets {
        if kept
            .iter()
            .any(|k| k.class_id == det.class_id && iou(k, &det) > iou_threshold)
        {
            continue;
        }
        kept.push(det);
    }
    kept
}

/// Rakit mask satu deteksi pada resolusi prototipe: sigmoid dari kombinasi
/// linear prototipe dengan koefisien deteksi.
pub fn assemble_mask(coeffs: &[f32], protos: &[f32], proto_w: u32, proto_h: u32) -> Vec<f32> {
    let px = (proto_w * proto_h) as usize;
    let channels = coeffs.len();
    debug_assert_eq!(protos.len(), channels * px);
    let mut out = vec![0f32; px];
    for (k, &c) in coeffs.iter().enumerate() {
        if c == 0.0 {
            continue;
        }
        let plane = &protos[k * px..(k + 1) * px];
        for i in 0..px {
            out[i] += c * plane[i];
        }
    }
    for v in out.iter_mut() {
        *v = 1.0 / (1.0 + (-*v).exp());
    }
    out
}

/// Nol-kan mask di luar kotak deteksi (koordinat ruang prototipe).
pub fn crop_mask_to_box(
    mask: &mut [f32],
    proto_w: u32,
    proto_h: u32,
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
) {
    for y in 0..proto_h {
        for x in 0..proto_w {
            let (fx, fy) = (x as f32, y as f32);
            if fx < x0 || fx > x1 || fy < y0 || fy > y1 {
                mask[(y * proto_w + x) as usize] = 0.0;
            }
        }
    }
}

/// Label 80 kelas COCO, urutan standar dataset.
pub const COCO_LABELS: [&str; 80] = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
    "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
    "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
];

#[cfg(test)]
mod tests {
    use super::*;

    fn det(class_id: usize, score: f32, cx: f32, cy: f32, size: f32) -> RawDetection {
        RawDetection {
            class_id,
            score,
            cx,
            cy,
            bw: size,
            bh: size,
            coeffs: vec![0.0; 32],
        }
    }

    #[test]
    fn parse_filters_by_confidence_and_picks_best_class() {
        // 2 kelas + 4 koefisien contoh kecil: channels=4+2+2=8, kandidat=3.
        let (ch, n, nc, nk) = (8usize, 3usize, 2usize, 2usize);
        let mut out = vec![0f32; ch * n];
        let mut set = |c: usize, i: usize, v: f32| out[c * n + i] = v;
        // Kandidat 0: kelas 1 skor 0.9.
        set(0, 0, 100.0);
        set(1, 0, 100.0);
        set(2, 0, 50.0);
        set(3, 0, 50.0);
        set(4, 0, 0.1);
        set(5, 0, 0.9);
        // Kandidat 1: semua kelas di bawah ambang.
        set(4, 1, 0.2);
        set(5, 1, 0.1);
        // Kandidat 2: kelas 0 skor 0.3.
        set(4, 2, 0.3);
        set(5, 2, 0.05);
        let dets = parse_yolo_output(&out, ch, n, nc, nk, 0.25);
        assert_eq!(dets.len(), 2);
        assert_eq!(dets[0].class_id, 1);
        assert!((dets[0].score - 0.9).abs() < 1e-6);
        assert_eq!(dets[1].class_id, 0);
    }

    #[test]
    fn nms_suppresses_same_class_overlap_only() {
        let dets = vec![
            det(0, 0.9, 100.0, 100.0, 50.0),
            det(0, 0.8, 105.0, 100.0, 50.0), // tumpang tindih berat, kelas sama
            det(1, 0.7, 102.0, 100.0, 50.0), // posisi sama, kelas beda: lolos
            det(0, 0.6, 400.0, 400.0, 50.0), // jauh: lolos
        ];
        let kept = nms(dets, 0.45);
        assert_eq!(kept.len(), 3);
        assert!((kept[0].score - 0.9).abs() < 1e-6);
        assert!(kept.iter().any(|d| d.class_id == 1));
    }

    #[test]
    fn assemble_mask_applies_sigmoid() {
        // Prototipe kanal 0 konstan 0 -> sigmoid(0) = 0.5; kanal 1 konstan 2.
        let (pw, ph) = (2u32, 2u32);
        let mut protos = vec![0f32; 2 * 4];
        for i in 0..4 {
            protos[4 + i] = 2.0;
        }
        let mask = assemble_mask(&[1.0, 0.0], &protos, pw, ph);
        assert!(mask.iter().all(|&v| (v - 0.5).abs() < 1e-6));
        let mask2 = assemble_mask(&[0.0, 1.0], &protos, pw, ph);
        assert!(mask2.iter().all(|&v| (v - 0.8808).abs() < 0.001));
    }

    #[test]
    fn crop_mask_zeroes_outside_box() {
        let mut mask = vec![1.0f32; 16];
        crop_mask_to_box(&mut mask, 4, 4, 1.0, 1.0, 2.0, 2.0);
        assert_eq!(mask[0], 0.0);
        assert_eq!(mask[(1 * 4 + 1) as usize], 1.0);
        assert_eq!(mask[(3 * 4 + 3) as usize], 0.0);
    }

    #[test]
    fn coco_labels_count() {
        assert_eq!(COCO_LABELS.len(), 80);
        assert_eq!(COCO_LABELS[0], "person");
        assert_eq!(COCO_LABELS[5], "bus");
    }
}
