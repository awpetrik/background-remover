//! Runtime model ONNX: registrasi, sesi, dan inference mask saliency.
//!
//! Library ONNX Runtime dimuat dinamis (fitur `load-dynamic` crate `ort`):
//! di Android path-nya menunjuk `libonnxruntime.so` di nativeLibraryDir,
//! di host path datang dari ORT_DYLIB_PATH atau argumen konstruktor.
//! Sesi dibuat malas (lazy) pada pemakaian pertama per model.

use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

use ort::ep::XNNPACK;
use ort::session::{builder::GraphOptimizationLevel, Session};
use ort::value::Tensor;

use crate::{lock_err, EngineError};

/// Jenis model yang dikenal katalog. Jenis baru butuh update aplikasi
/// karena pra/pasca-prosesnya hidup di kode (design doc 8).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ModelKind {
    SalientMask,
    InstanceSeg,
    PointSam,
    Selfie,
}

struct ModelEntry {
    path: String,
    kind: ModelKind,
    input_width: u32,
    input_height: u32,
    session: Option<Session>,
    /// Khusus PointSam: sesi encoder (sesi decoder disimpan di `session`).
    encoder_session: Option<Session>,
}

/// Embedding gambar SAM yang tersimpan (slot tunggal; encode baru menimpa).
struct SamEmbedding {
    key: String,
    model_id: String,
    data: Vec<f32>,
    /// Skala koordinat asli -> ruang resize SAM (sisi terpanjang 1024).
    scale: f32,
    orig_width: u32,
    orig_height: u32,
}

struct RuntimeState {
    entries: HashMap<String, ModelEntry>,
    sam_embedding: Option<SamEmbedding>,
    /// Toggle NNAPI eksperimental (Pengaturan). Berlaku untuk sesi yang
    /// dibuat setelah toggle diubah; sesi yang sudah jalan tidak dibongkar.
    use_nnapi: bool,
}

/// Satu hasil segmentasi instans. Mask pada resolusi dokumen (atau lebih
/// kecil untuk dokumen besar; mask_width/height menyatakan resolusinya).
pub struct InstanceDetection {
    pub label: String,
    pub score: f32,
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
    pub mask: Vec<f32>,
    pub mask_width: u32,
    pub mask_height: u32,
}

/// Objek runtime model yang dibagikan ke EditorEngine untuk apply_ai.
#[derive(uniffi::Object)]
pub struct ModelRuntime {
    state: Mutex<RuntimeState>,
}

static ORT_INIT: OnceLock<()> = OnceLock::new();

fn init_ort(dylib_path: &str) {
    // OnceLock hanya dikonsumsi bila benar-benar ada kandidat path: konstruktor
    // tanpa path (mis. untuk registrasi saja) tidak boleh membakar kesempatan
    // inisialisasi untuk konstruktor berikutnya yang membawa path asli.
    let candidate = if !dylib_path.is_empty() {
        Some(dylib_path.to_string())
    } else {
        std::env::var("ORT_DYLIB_PATH").ok().filter(|p| !p.is_empty())
    };
    if let Some(path) = candidate {
        ORT_INIT.get_or_init(|| {
            // Hasil diabaikan dengan sadar: bila path salah, kegagalan muncul
            // sebagai error saat sesi pertama dibuat, dengan pesan dari ort.
            let _ = ort::init_from(path.as_str());
        });
    }
}

#[uniffi::export]
impl ModelRuntime {
    /// Buat runtime. `dylib_path` boleh kosong bila ORT_DYLIB_PATH terpasang
    /// atau library ditemukan lewat jalur pemuat sistem.
    #[uniffi::constructor]
    pub fn new(dylib_path: String) -> Result<Arc<Self>, EngineError> {
        init_ort(&dylib_path);
        Ok(Arc::new(Self {
            state: Mutex::new(RuntimeState {
                entries: HashMap::new(),
                sam_embedding: None,
                use_nnapi: false,
            }),
        }))
    }

    /// Daftarkan model dari file ONNX di disk. File harus ada; sesi belum
    /// dibuat di sini (dibuat saat inference pertama agar registrasi murah).
    pub fn register_model(
        &self,
        id: String,
        path: String,
        kind: ModelKind,
        input_width: u32,
        input_height: u32,
    ) -> Result<(), EngineError> {
        if id.is_empty() || input_width == 0 || input_height == 0 {
            return Err(EngineError::InvalidInput {
                detail: "id model kosong atau ukuran input nol".into(),
            });
        }
        if kind == ModelKind::PointSam {
            // Model prompt titik adalah pasangan file dalam satu direktori.
            let dir = Path::new(&path);
            if !dir.join("encoder.onnx").is_file() || !dir.join("decoder.onnx").is_file() {
                return Err(EngineError::InvalidInput {
                    detail: format!(
                        "direktori model SAM harus berisi encoder.onnx dan decoder.onnx: {path}"
                    ),
                });
            }
        } else if !Path::new(&path).is_file() {
            return Err(EngineError::InvalidInput {
                detail: format!("file model tidak ditemukan: {path}"),
            });
        }
        let mut state = self.state.lock().map_err(lock_err)?;
        state.entries.insert(
            id,
            ModelEntry {
                path,
                kind,
                input_width,
                input_height,
                session: None,
                encoder_session: None,
            },
        );
        Ok(())
    }

    /// Aktif/nonaktifkan NNAPI (eksperimental) untuk sesi yang dibuat
    /// setelah panggilan ini. Sesi yang sudah berjalan tidak berubah.
    pub fn set_use_nnapi(&self, enabled: bool) -> Result<(), EngineError> {
        let mut state = self.state.lock().map_err(lock_err)?;
        state.use_nnapi = enabled;
        Ok(())
    }

    pub fn use_nnapi(&self) -> bool {
        self.state.lock().map(|s| s.use_nnapi).unwrap_or(false)
    }

    pub fn is_registered(&self, id: String) -> bool {
        self.state
            .lock()
            .map(|s| s.entries.contains_key(&id))
            .unwrap_or(false)
    }

    pub fn registered_ids(&self) -> Vec<String> {
        self.state
            .lock()
            .map(|s| {
                let mut ids: Vec<String> = s.entries.keys().cloned().collect();
                ids.sort();
                ids
            })
            .unwrap_or_default()
    }

    /// Lepas model dan sesinya dari runtime. True bila model ada.
    pub fn unload_model(&self, id: String) -> bool {
        self.state
            .lock()
            .map(|mut s| s.entries.remove(&id).is_some())
            .unwrap_or(false)
    }
}

impl ModelRuntime {
    fn ensure_session(state: &mut RuntimeState, id: &str) -> Result<(u32, u32), EngineError> {
        let use_nnapi = state.use_nnapi;
        let entry = state.entries.get_mut(id).ok_or_else(|| {
            EngineError::InvalidInput {
                detail: format!("model tidak terdaftar: {id}"),
            }
        })?;
        if entry.session.is_none() {
            let session = build_session(&entry.path.clone(), use_nnapi)?;
            // Tes kontrak ringan per jenis: saliency 1 masuk/1 keluar,
            // segmentasi instans 1 masuk/2 keluar (deteksi + prototipe).
            let expected_outputs = match entry.kind {
                ModelKind::InstanceSeg => 2,
                _ => 1,
            };
            if session.inputs().len() != 1 || session.outputs().len() != expected_outputs {
                return Err(EngineError::Internal {
                    detail: format!(
                        "kontrak model {id} tidak cocok: butuh 1 input dan {expected_outputs} output, dapat {} input dan {} output",
                        session.inputs().len(),
                        session.outputs().len()
                    ),
                });
            }
            entry.session = Some(session);
        }
        Ok((entry.input_width, entry.input_height))
    }

    /// Jalankan model saliency pada gambar RGB (ukuran sembarang).
    /// Mengembalikan mask ternormalisasi [0,1] pada resolusi input model.
    pub(crate) fn infer_salient_mask(
        &self,
        id: &str,
        rgb: &[u8],
        width: u32,
        height: u32,
    ) -> Result<(Vec<f32>, u32, u32), EngineError> {
        let mut state = self.state.lock().map_err(lock_err)?;
        let (iw, ih) = Self::ensure_session(&mut state, id)?;
        let entry = state.entries.get_mut(id).ok_or_else(|| EngineError::Internal {
            detail: "model hilang setelah sesi dibuat".into(),
        })?;
        if entry.kind != ModelKind::SalientMask {
            return Err(EngineError::InvalidInput {
                detail: format!("jenis model {id} belum didukung untuk operasi ini"),
            });
        }
        let resized = crate::image::resize_bilinear_rgb(rgb, width, height, iw, ih);
        let tensor_data = build_tensor(&resized, iw, ih);
        let input = Tensor::from_array(([1usize, 3, ih as usize, iw as usize], tensor_data))
            .map_err(ort_err)?;
        let session = entry.session.as_mut().ok_or_else(|| EngineError::Internal {
            detail: "sesi model belum dibuat".into(),
        })?;
        let outputs = session.run(ort::inputs![input]).map_err(ort_err)?;
        let (_shape, data) = outputs[0].try_extract_tensor::<f32>().map_err(ort_err)?;
        let expected = (iw * ih) as usize;
        if data.len() < expected {
            return Err(EngineError::Internal {
                detail: format!(
                    "output model terlalu kecil: {} elemen, butuh {expected}",
                    data.len()
                ),
            });
        }
        Ok((min_max_normalize(&data[..expected]), iw, ih))
    }
}

fn build_session(path: &str, use_nnapi: bool) -> Result<Session, EngineError> {
    // XNNPACK memakai threadpool sendiri; threadpool intra-op sesi ditahan
    // di 1 agar tidak berebut inti (mengikuti panduan ort).
    let build = |with_nnapi: bool| -> Result<Session, EngineError> {
        let xnnpack = XNNPACK::default()
            .with_intra_op_num_threads(std::num::NonZeroUsize::new(4).expect("4 bukan nol"))
            .build();
        let builder = Session::builder()
            .map_err(ort_err)?
            .with_optimization_level(GraphOptimizationLevel::All)
            .map_err(ort_err)?
            .with_intra_threads(1)
            .map_err(ort_err)?;
        let mut builder = if with_nnapi {
            let nnapi = ort::ep::NNAPI::default().build();
            builder
                .with_execution_providers([nnapi, xnnpack])
                .map_err(ort_err)?
        } else {
            builder.with_execution_providers([xnnpack]).map_err(ort_err)?
        };
        builder.commit_from_file(path).map_err(ort_err)
    };
    if use_nnapi {
        // NNAPI tidak ada di semua lingkungan (mis. host Linux): gagal
        // membuatnya tidak boleh mematikan inference, kembali ke XNNPACK.
        build(true).or_else(|_| build(false))
    } else {
        build(false)
    }
}

impl ModelRuntime {
    fn ensure_sam_sessions(state: &mut RuntimeState, id: &str) -> Result<(), EngineError> {
        let use_nnapi = state.use_nnapi;
        let entry = state.entries.get_mut(id).ok_or_else(|| EngineError::InvalidInput {
            detail: format!("model tidak terdaftar: {id}"),
        })?;
        if entry.kind != ModelKind::PointSam {
            return Err(EngineError::InvalidInput {
                detail: format!("model {id} bukan PointSam"),
            });
        }
        if entry.encoder_session.is_none() {
            let path = Path::new(&entry.path).join("encoder.onnx");
            entry.encoder_session = Some(build_session(&path.to_string_lossy(), use_nnapi)?);
        }
        if entry.session.is_none() {
            let path = Path::new(&entry.path).join("decoder.onnx");
            let session = build_session(&path.to_string_lossy(), use_nnapi)?;
            if session.inputs().len() != 6 {
                return Err(EngineError::Internal {
                    detail: format!(
                        "kontrak decoder SAM {id} tidak cocok: butuh 6 input, dapat {}",
                        session.inputs().len()
                    ),
                });
            }
            entry.session = Some(session);
        }
        Ok(())
    }

    /// Jalankan segmentasi instans (format YOLO-seg) pada gambar RGB.
    /// Mask hasil pada resolusi dokumen, dibatasi sisi terpanjang 512px.
    pub(crate) fn run_instance_seg(
        &self,
        id: &str,
        rgb: &[u8],
        width: u32,
        height: u32,
        conf: f32,
    ) -> Result<Vec<InstanceDetection>, EngineError> {
        let mut state = self.state.lock().map_err(lock_err)?;
        let (iw, ih) = Self::ensure_session(&mut state, id)?;
        let entry = state.entries.get_mut(id).ok_or_else(|| EngineError::Internal {
            detail: "model hilang setelah sesi dibuat".into(),
        })?;
        if entry.kind != ModelKind::InstanceSeg {
            return Err(EngineError::InvalidInput {
                detail: format!("model {id} bukan InstanceSeg"),
            });
        }
        // Letterbox ke ukuran input model, padding abu-abu 114.
        let scale = (iw as f32 / width as f32).min(ih as f32 / height as f32);
        let nw = ((width as f32 * scale).round() as u32).max(1);
        let nh = ((height as f32 * scale).round() as u32).max(1);
        let pad_x = (iw - nw) / 2;
        let pad_y = (ih - nh) / 2;
        let resized = crate::image::resize_bilinear_rgb(rgb, width, height, nw, nh);
        let mut canvas = vec![114u8; (iw * ih * 3) as usize];
        for y in 0..nh {
            for x in 0..nw {
                let src = ((y * nw + x) * 3) as usize;
                let dst = (((y + pad_y) * iw + (x + pad_x)) * 3) as usize;
                canvas[dst..dst + 3].copy_from_slice(&resized[src..src + 3]);
            }
        }
        let px = (iw * ih) as usize;
        let mut tensor_data = vec![0f32; px * 3];
        for c in 0..3usize {
            for i in 0..px {
                tensor_data[c * px + i] = canvas[i * 3 + c] as f32 / 255.0;
            }
        }
        let input = Tensor::from_array(([1usize, 3, ih as usize, iw as usize], tensor_data))
            .map_err(ort_err)?;
        let session = entry.session.as_mut().ok_or_else(|| EngineError::Internal {
            detail: "sesi model belum dibuat".into(),
        })?;
        let outputs = session.run(ort::inputs![input]).map_err(ort_err)?;
        let mut det: Option<(Vec<i64>, Vec<f32>)> = None;
        let mut proto: Option<(Vec<i64>, Vec<f32>)> = None;
        for i in 0..outputs.len() {
            let (shape, data) = outputs[i].try_extract_tensor::<f32>().map_err(ort_err)?;
            let dims: Vec<i64> = shape.iter().copied().collect();
            match dims.len() {
                3 => det = Some((dims, data.to_vec())),
                4 => proto = Some((dims, data.to_vec())),
                _ => {}
            }
        }
        let (det_shape, det_data) = det.ok_or_else(|| EngineError::Internal {
            detail: "output deteksi (rank 3) tidak ditemukan".into(),
        })?;
        let (proto_shape, proto_data) = proto.ok_or_else(|| EngineError::Internal {
            detail: "output prototipe mask (rank 4) tidak ditemukan".into(),
        })?;
        let channels = det_shape[1] as usize;
        let candidates = det_shape[2] as usize;
        let proto_c = proto_shape[1] as usize;
        let proto_h = proto_shape[2] as u32;
        let proto_w = proto_shape[3] as u32;
        if channels <= 4 + proto_c {
            return Err(EngineError::Internal {
                detail: format!("saluran output tidak masuk akal: {channels}"),
            });
        }
        let num_classes = channels - 4 - proto_c;
        let raw = crate::segment::parse_yolo_output(
            &det_data, channels, candidates, num_classes, proto_c, conf,
        );
        let kept = crate::segment::nms(raw, 0.45);

        // Resolusi mask keluaran: batasi sisi terpanjang 512.
        let longest = width.max(height) as f32;
        let mask_scale = if longest > 512.0 { 512.0 / longest } else { 1.0 };
        let mw = ((width as f32 * mask_scale).round() as u32).max(1);
        let mh = ((height as f32 * mask_scale).round() as u32).max(1);

        let mut results = Vec::new();
        for d in kept {
            let mut pmask =
                crate::segment::assemble_mask(&d.coeffs, &proto_data, proto_w, proto_h);
            let sx = proto_w as f32 / iw as f32;
            let sy = proto_h as f32 / ih as f32;
            crate::segment::crop_mask_to_box(
                &mut pmask,
                proto_w,
                proto_h,
                d.x0() * sx,
                d.y0() * sy,
                d.x1() * sx,
                d.y1() * sy,
            );
            // Naikkan mask ke ruang input model, lalu petakan ke dokumen.
            let input_mask =
                crate::image::resize_bilinear_f32(&pmask, proto_w, proto_h, iw, ih);
            let mut plane = vec![0f32; (mw * mh) as usize];
            let ox0 = (((d.x0() - pad_x as f32) / scale) * mask_scale)
                .floor()
                .max(0.0) as u32;
            let oy0 = (((d.y0() - pad_y as f32) / scale) * mask_scale)
                .floor()
                .max(0.0) as u32;
            let ox1 = (((d.x1() - pad_x as f32) / scale) * mask_scale)
                .ceil()
                .min(mw as f32) as u32;
            let oy1 = (((d.y1() - pad_y as f32) / scale) * mask_scale)
                .ceil()
                .min(mh as f32) as u32;
            for oy in oy0..oy1 {
                for ox in ox0..ox1 {
                    let orig_x = ox as f32 / mask_scale;
                    let orig_y = oy as f32 / mask_scale;
                    let in_x = (orig_x * scale + pad_x as f32).round() as i64;
                    let in_y = (orig_y * scale + pad_y as f32).round() as i64;
                    if in_x < 0 || in_y < 0 || in_x >= iw as i64 || in_y >= ih as i64 {
                        continue;
                    }
                    plane[(oy * mw + ox) as usize] =
                        input_mask[(in_y as u32 * iw + in_x as u32) as usize];
                }
            }
            let label = crate::segment::COCO_LABELS
                .get(d.class_id)
                .map(|s| s.to_string())
                .unwrap_or_else(|| format!("kelas {}", d.class_id));
            results.push(InstanceDetection {
                label,
                score: d.score,
                x: (((d.x0() - pad_x as f32) / scale).max(0.0)) as u32,
                y: (((d.y0() - pad_y as f32) / scale).max(0.0)) as u32,
                width: ((d.bw / scale) as u32).min(width),
                height: ((d.bh / scale) as u32).min(height),
                mask: plane,
                mask_width: mw,
                mask_height: mh,
            });
        }
        Ok(results)
    }

    /// Encode gambar untuk prompt SAM dan simpan embedding-nya (slot tunggal).
    pub(crate) fn sam_encode(
        &self,
        id: &str,
        key: &str,
        rgb: &[u8],
        width: u32,
        height: u32,
    ) -> Result<(), EngineError> {
        const SAM_SIZE: u32 = 1024;
        const MEAN: [f32; 3] = [123.675, 116.28, 103.53];
        const STD: [f32; 3] = [58.395, 57.12, 57.375];
        let mut state = self.state.lock().map_err(lock_err)?;
        Self::ensure_sam_sessions(&mut state, id)?;
        let longest = width.max(height) as f32;
        let scale = SAM_SIZE as f32 / longest;
        let nw = ((width as f32 * scale).round() as u32).max(1);
        let nh = ((height as f32 * scale).round() as u32).max(1);
        let resized = crate::image::resize_bilinear_rgb(rgb, width, height, nw, nh);
        let px = (SAM_SIZE * SAM_SIZE) as usize;
        let mut tensor_data = vec![0f32; px * 3];
        for y in 0..nh {
            for x in 0..nw {
                let src = ((y * nw + x) * 3) as usize;
                let dst = (y * SAM_SIZE + x) as usize;
                for c in 0..3usize {
                    tensor_data[c * px + dst] =
                        (resized[src + c] as f32 - MEAN[c]) / STD[c];
                }
            }
        }
        let entry = state.entries.get_mut(id).ok_or_else(|| EngineError::Internal {
            detail: "model SAM hilang".into(),
        })?;
        let session = entry
            .encoder_session
            .as_mut()
            .ok_or_else(|| EngineError::Internal {
                detail: "sesi encoder SAM belum dibuat".into(),
            })?;
        let input = Tensor::from_array((
            [1usize, 3, SAM_SIZE as usize, SAM_SIZE as usize],
            tensor_data,
        ))
        .map_err(ort_err)?;
        // Salin embedding keluar lebih dulu: SessionOutputs meminjam sesi
        // (dan state), jadi embedding baru boleh disimpan setelahnya lepas.
        let emb_data: Vec<f32> = {
            let outputs = session.run(ort::inputs![input]).map_err(ort_err)?;
            let (_shape, data) = outputs[0].try_extract_tensor::<f32>().map_err(ort_err)?;
            if data.len() != 256 * 64 * 64 {
                return Err(EngineError::Internal {
                    detail: format!(
                        "ukuran embedding SAM tidak cocok: {} elemen",
                        data.len()
                    ),
                });
            }
            data.to_vec()
        };
        state.sam_embedding = Some(SamEmbedding {
            key: key.to_string(),
            model_id: id.to_string(),
            data: emb_data,
            scale,
            orig_width: width,
            orig_height: height,
        });
        Ok(())
    }

    /// Decode prompt titik menjadi mask logits ukuran gambar asli.
    pub(crate) fn sam_decode(
        &self,
        id: &str,
        points: &[(f32, f32, bool)],
    ) -> Result<Vec<f32>, EngineError> {
        let mut state = self.state.lock().map_err(lock_err)?;
        let emb = state
            .sam_embedding
            .as_ref()
            .filter(|e| e.model_id == id)
            .ok_or_else(|| EngineError::InvalidInput {
                detail: "embedding SAM belum dibuat; mulai sesi prompt dulu".into(),
            })?;
        let (orig_w, orig_h, scale) = (emb.orig_width, emb.orig_height, emb.scale);
        let emb_data = emb.data.clone();
        Self::ensure_sam_sessions(&mut state, id)?;
        // Konvensi predictor: titik padding (0,0) berlabel -1 di akhir.
        let mut coords: Vec<f32> = Vec::with_capacity((points.len() + 1) * 2);
        let mut labels: Vec<f32> = Vec::with_capacity(points.len() + 1);
        for &(x, y, positive) in points {
            coords.push(x * scale);
            coords.push(y * scale);
            labels.push(if positive { 1.0 } else { -1.0 });
        }
        coords.push(0.0);
        coords.push(0.0);
        labels.push(-1.0);
        let n = labels.len();
        let emb_tensor =
            Tensor::from_array(([1usize, 256, 64, 64], emb_data)).map_err(ort_err)?;
        let coords_tensor =
            Tensor::from_array(([1usize, n, 2], coords)).map_err(ort_err)?;
        let labels_tensor = Tensor::from_array(([1usize, n], labels)).map_err(ort_err)?;
        let mask_tensor =
            Tensor::from_array(([1usize, 1, 256, 256], vec![0f32; 256 * 256]))
                .map_err(ort_err)?;
        let has_mask_tensor =
            Tensor::from_array(([1usize], vec![0f32])).map_err(ort_err)?;
        let size_tensor = Tensor::from_array((
            [2usize],
            vec![orig_h as f32, orig_w as f32],
        ))
        .map_err(ort_err)?;
        let entry = state.entries.get_mut(id).ok_or_else(|| EngineError::Internal {
            detail: "model SAM hilang".into(),
        })?;
        let session = entry.session.as_mut().ok_or_else(|| EngineError::Internal {
            detail: "sesi decoder SAM belum dibuat".into(),
        })?;
        let outputs = session
            .run(ort::inputs![
                "image_embeddings" => emb_tensor,
                "point_coords" => coords_tensor,
                "point_labels" => labels_tensor,
                "mask_input" => mask_tensor,
                "has_mask_input" => has_mask_tensor,
                "orig_im_size" => size_tensor,
            ])
            .map_err(ort_err)?;
        let (shape, data) = outputs[0].try_extract_tensor::<f32>().map_err(ort_err)?;
        let expected = (orig_w * orig_h) as usize;
        if data.len() != expected {
            return Err(EngineError::Internal {
                detail: format!(
                    "ukuran mask SAM tidak cocok: {} elemen (shape {:?}), butuh {expected}",
                    data.len(),
                    shape.iter().copied().collect::<Vec<_>>()
                ),
            });
        }
        Ok(data.to_vec())
    }

    /// Buang embedding SAM untuk kunci sesi tertentu.
    pub(crate) fn sam_drop(&self, key: &str) {
        if let Ok(mut state) = self.state.lock() {
            if state.sam_embedding.as_ref().map(|e| e.key.as_str()) == Some(key) {
                state.sam_embedding = None;
            }
        }
    }
}

/// Bangun tensor NCHW ternormalisasi ImageNet dari RGB ukuran model.
/// Konvensi U2Net: skala [0,1] dulu, lalu (x - mean) / std per kanal.
pub(crate) fn build_tensor(rgb: &[u8], width: u32, height: u32) -> Vec<f32> {
    const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
    const STD: [f32; 3] = [0.229, 0.224, 0.225];
    let px = (width * height) as usize;
    let mut out = vec![0f32; px * 3];
    for c in 0..3usize {
        for i in 0..px {
            let v = rgb[i * 3 + c] as f32 / 255.0;
            out[c * px + i] = (v - MEAN[c]) / STD[c];
        }
    }
    out
}

/// Normalisasi min-max ke [0,1] (normP pada kode resmi U2Net).
pub(crate) fn min_max_normalize(plane: &[f32]) -> Vec<f32> {
    let mut lo = f32::INFINITY;
    let mut hi = f32::NEG_INFINITY;
    for &v in plane {
        if v < lo {
            lo = v;
        }
        if v > hi {
            hi = v;
        }
    }
    let range = hi - lo;
    if range <= f32::EPSILON {
        return vec![0.0; plane.len()];
    }
    plane.iter().map(|&v| (v - lo) / range).collect()
}

fn ort_err<T>(e: ort::Error<T>) -> EngineError {
    EngineError::Internal {
        detail: format!("onnx runtime: {e}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_tensor_normalizes() {
        // Satu piksel putih: kanal R = (1 - 0.485)/0.229 ~ 2.2489.
        let t = build_tensor(&[255, 0, 128], 1, 1);
        assert!((t[0] - 2.2489).abs() < 0.001);
        // Satu piksel hitam: kanal G = (0 - 0.456)/0.224 ~ -2.0357.
        let t2 = build_tensor(&[0, 0, 0], 1, 1);
        assert!((t2[1] - (-2.0357)).abs() < 0.001);
    }

    #[test]
    fn min_max_normalize_scales() {
        let out = min_max_normalize(&[2.0, 4.0, 6.0]);
        assert_eq!(out, vec![0.0, 0.5, 1.0]);
        let flat = min_max_normalize(&[3.0, 3.0]);
        assert_eq!(flat, vec![0.0, 0.0]);
    }

    #[test]
    fn register_rejects_missing_file() {
        let rt = ModelRuntime::new(String::new()).unwrap();
        let r = rt.register_model(
            "x".into(),
            "/tidak/ada/model.onnx".into(),
            ModelKind::SalientMask,
            320,
            320,
        );
        assert!(matches!(r, Err(EngineError::InvalidInput { .. })));
    }

    /// Toggle NNAPI di host tanpa NNAPI: pembuatan sesi harus jatuh kembali
    /// ke XNNPACK dan inference tetap jalan.
    #[test]
    fn nnapi_toggle_falls_back_gracefully() {
        let Some(dylib) = host_dylib() else { return };
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let model = manifest.join("../../models/bundled/u2netp.onnx");
        assert!(model.is_file());
        let rt = ModelRuntime::new(dylib.to_string_lossy().into_owned()).unwrap();
        assert!(!rt.use_nnapi());
        rt.set_use_nnapi(true).unwrap();
        assert!(rt.use_nnapi());
        rt.register_model(
            "u2netp".into(),
            model.to_string_lossy().into_owned(),
            ModelKind::SalientMask,
            320,
            320,
        )
        .unwrap();
        let rgb = vec![0u8; 64 * 64 * 3];
        let (mask, mw, mh) = rt.infer_salient_mask("u2netp", &rgb, 64, 64).unwrap();
        assert_eq!((mw, mh), (320, 320));
        assert_eq!(mask.len(), 320 * 320);
        rt.set_use_nnapi(false).unwrap();
        assert!(!rt.use_nnapi());
    }

    fn host_dylib() -> Option<std::path::PathBuf> {
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let dylib = manifest.join("third_party/onnxruntime/linux-x64/libonnxruntime.so");
        if dylib.is_file() {
            Some(dylib)
        } else {
            eprintln!("lewati: lib ORT host belum diunduh (tools/fetch_onnxruntime.sh)");
            None
        }
    }

    fn load_bus_fixture() -> Option<(Vec<u8>, u32, u32)> {
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let rgb_path = manifest.join("../../tools/work/test_bus.rgb");
        let size_path = manifest.join("../../tools/work/test_bus.size");
        if !rgb_path.is_file() {
            eprintln!("lewati: fixture test_bus.rgb belum dibuat");
            return None;
        }
        let size = std::fs::read_to_string(size_path).ok()?;
        let mut parts = size.split_whitespace();
        let w: u32 = parts.next()?.parse().ok()?;
        let h: u32 = parts.next()?.parse().ok()?;
        let rgb = std::fs::read(rgb_path).ok()?;
        assert_eq!(rgb.len(), (w * h * 3) as usize);
        Some((rgb, w, h))
    }

    /// Segmentasi instans ujung ke ujung pada foto uji yang sama dengan
    /// referensi Ultralytics (5 deteksi: 4 person, 1 bus pada conf 0.25).
    #[test]
    fn instance_seg_on_bus_photo() {
        let Some(dylib) = host_dylib() else { return };
        let Some((rgb, w, h)) = load_bus_fixture() else {
            return;
        };
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let model = manifest.join("../../models/instance/yolo11n-seg.onnx");
        assert!(model.is_file(), "model instans harus ada: {}", model.display());
        let rt = ModelRuntime::new(dylib.to_string_lossy().into_owned()).unwrap();
        rt.register_model(
            "yolo".into(),
            model.to_string_lossy().into_owned(),
            ModelKind::InstanceSeg,
            640,
            640,
        )
        .unwrap();
        let found = rt.run_instance_seg("yolo", &rgb, w, h, 0.25).unwrap();
        let labels: Vec<&str> = found.iter().map(|d| d.label.as_str()).collect();
        assert!(found.len() >= 3, "deteksi terlalu sedikit: {labels:?}");
        assert!(labels.contains(&"bus"), "bus tidak terdeteksi: {labels:?}");
        assert!(labels.contains(&"person"), "person tidak terdeteksi: {labels:?}");
        let bus = found.iter().find(|d| d.label == "bus").unwrap();
        let mask_sum: f32 = bus.mask.iter().sum();
        assert!(mask_sum > 1000.0, "mask bus terlalu kecil: {mask_sum}");
        assert!(bus.score > 0.5, "skor bus rendah: {}", bus.score);
    }

    /// SAM ujung ke ujung: encode gambar sintetis, prompt satu titik positif
    /// di tengah bujursangkar, mask harus menutupi bujursangkar.
    #[test]
    fn sam_prompt_on_synthetic() {
        let Some(dylib) = host_dylib() else { return };
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let dir = manifest.join("../../models/sam");
        assert!(
            dir.join("encoder.onnx").is_file(),
            "model SAM harus ada: {}",
            dir.display()
        );
        let rt = ModelRuntime::new(dylib.to_string_lossy().into_owned()).unwrap();
        rt.register_model(
            "sam".into(),
            dir.to_string_lossy().into_owned(),
            ModelKind::PointSam,
            1024,
            1024,
        )
        .unwrap();
        let (w, h) = (256u32, 256u32);
        let mut rgb = vec![0u8; (w * h * 3) as usize];
        for y in 64..192 {
            for x in 64..192 {
                let i = ((y * w + x) * 3) as usize;
                rgb[i] = 255;
                rgb[i + 1] = 255;
                rgb[i + 2] = 255;
            }
        }
        rt.sam_encode("sam", "test-key", &rgb, w, h).unwrap();
        let mask = rt.sam_decode("sam", &[(128.0, 128.0, true)]).unwrap();
        assert_eq!(mask.len(), (w * h) as usize);
        let center = mask[(128 * w + 128) as usize];
        let corner = mask[0];
        assert!(center > 0.0, "pusat harus di dalam mask (logit {center})");
        assert!(corner <= 0.0, "sudut harus di luar mask (logit {corner})");
        let inside = (64..192)
            .flat_map(|y| (64..192).map(move |x| (y * w + x) as usize))
            .filter(|&i| mask[i] > 0.0)
            .count();
        let coverage = inside as f32 / (128.0 * 128.0);
        assert!(coverage > 0.9, "cakupan bujursangkar {coverage}");
        rt.sam_drop("test-key");
        assert!(rt.sam_decode("sam", &[(128.0, 128.0, true)]).is_err());
    }

    /// Tes kontrak penuh melawan model terbundel asli. Butuh library ORT
    /// host (tools/fetch_onnxruntime.sh) dan file models/bundled/u2netp.onnx.
    /// Dilewati otomatis bila salah satunya tidak ada (mis. CI tanpa unduhan).
    #[test]
    fn u2netp_contract_and_semantics() {
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let model = manifest.join("../../models/bundled/u2netp.onnx");
        assert!(
            model.is_file(),
            "model terbundel harus ada (dikomit di repo): {}",
            model.display()
        );
        let dylib = manifest.join("third_party/onnxruntime/linux-x64/libonnxruntime.so");
        if !dylib.is_file() {
            eprintln!("lewati: lib ORT host belum diunduh (tools/fetch_onnxruntime.sh)");
            return;
        }
        let rt = ModelRuntime::new(dylib.to_string_lossy().into_owned()).unwrap();
        rt.register_model(
            "u2netp".into(),
            model.to_string_lossy().into_owned(),
            ModelKind::SalientMask,
            320,
            320,
        )
        .unwrap();
        // Gambar sintetis 64x64: bujur sangkar putih di tengah, latar hitam.
        let mut rgb = vec![0u8; 64 * 64 * 3];
        for y in 16..48 {
            for x in 16..48 {
                let i = (y * 64 + x) * 3;
                rgb[i] = 255;
                rgb[i + 1] = 255;
                rgb[i + 2] = 255;
            }
        }
        let (mask, mw, mh) = rt.infer_salient_mask("u2netp", &rgb, 64, 64).unwrap();
        assert_eq!((mw, mh), (320, 320));
        assert_eq!(mask.len(), 320 * 320);
        let center = mask[(160 * 320 + 160) as usize];
        let corner = mask[0];
        assert!(center > 0.8, "mask tengah {center}");
        assert!(corner < 0.2, "mask sudut {corner}");
    }
}
