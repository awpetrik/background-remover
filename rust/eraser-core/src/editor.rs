//! EditorEngine: sesi edit satu dokumen.
//!
//! Buffer piksel (asli dan hasil edit) tinggal di sini selama sesi; Kotlin
//! hanya memegang pratinjau. Operasi Fase 1: brush (erase/restore), crop,
//! undo/redo berbasis tile. Operasi Fase 2: apply_ai (mask saliency via
//! ModelRuntime), invert_mask, refine_edges, auto_color, magic. Lihat design
//! doc bagian 7.2.
//!
//! Offset kursor tidak dikenal modul ini: offset adalah urusan ruang layar
//! dan diterapkan Kotlin sebelum titik dikirim ke `brush`.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::inference::ModelRuntime;
use crate::{EngineError, ProgressCallback};

pub const TILE_SIZE: u32 = 256;
const MAX_UNDO_STEPS: usize = 30;
const MAX_UNDO_BYTES: usize = 256 * 1024 * 1024;
const MAX_PIXELS: u64 = 200_000_000;

#[derive(Clone, Copy, PartialEq, Eq, Debug, uniffi::Enum)]
pub enum BrushMode {
    Erase,
    Restore,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct ImagePoint {
    pub x: f32,
    pub y: f32,
}

#[derive(Clone, Copy, Debug, uniffi::Record)]
pub struct CropRect {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(uniffi::Record)]
pub struct PreviewImage {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// Keadaan sebelum sebuah operasi, untuk undo. Operasi lokal menyimpan tile
/// yang tersentuh saja; operasi yang mengubah dimensi menyimpan buffer penuh.
enum UndoEntry {
    Tiles {
        tiles: Vec<(u32, u32, Vec<u8>)>, // (tile_x, tile_y, piksel tile sebelum operasi)
    },
    Full {
        width: u32,
        height: u32,
        rgba: Vec<u8>,
        /// True hanya untuk operasi pengubah dimensi (crop): saat undo/redo
        /// menerapkannya, `original` disinkronkan ke buffer hasil. Operasi
        /// global tanpa ubah dimensi (AI, warna, refine) memakai false agar
        /// gambar asli untuk Restore tidak ikut bergeser.
        resync_original: bool,
    },
}

impl UndoEntry {
    fn bytes(&self) -> usize {
        match self {
            UndoEntry::Tiles { tiles } => tiles.iter().map(|(_, _, b)| b.len()).sum(),
            UndoEntry::Full { rgba, .. } => rgba.len(),
        }
    }
}

struct EngineState {
    width: u32,
    height: u32,
    original: Vec<u8>,
    current: Vec<u8>,
    undo: Vec<UndoEntry>,
    redo: Vec<UndoEntry>,
    undo_bytes: usize,
    redo_bytes: usize,
    /// Snapshot yang sedang dikumpulkan selama satu goresan (begin/end stroke).
    /// Tile yang sudah tercatat tidak ditimpa snapshot berikutnya dalam
    /// goresan yang sama, jadi isinya selalu keadaan sebelum goresan mulai.
    stroke_snapshot: Option<Vec<(u32, u32, Vec<u8>)>>,
    /// Hasil deteksi objek terakhir (Fase 3), mask pada resolusi terbatas.
    detections: Vec<StoredDetection>,
    /// Sesi prompt titik SAM yang aktif, bila ada.
    sam: Option<SamState>,
}

/// Deteksi yang disimpan engine; mask `f32` pada resolusi mask_width/height.
struct StoredDetection {
    label: String,
    score: f32,
    x: u32,
    y: u32,
    width: u32,
    height: u32,
    mask: Vec<f32>,
    mask_width: u32,
    mask_height: u32,
}

/// Keadaan prompt SAM: titik-titik pengguna dan mask biner ukuran penuh.
/// Titik disimpan dalam koordinat resolusi kerja (gambar diperkecil maks
/// 1024px untuk encoder), bukan koordinat dokumen penuh.
struct SamState {
    model_id: String,
    key: String,
    work_width: u32,
    work_height: u32,
    points: Vec<(f32, f32, bool)>,
    mask: Option<Vec<u8>>,
}

/// Objek terdeteksi yang dilaporkan ke Kotlin (mask tetap di engine).
#[derive(Debug, Clone, uniffi::Record)]
pub struct DetectedObject {
    pub index: u32,
    pub label: String,
    pub score: f32,
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

impl EngineState {
    fn tile_bounds(&self, tx: u32, ty: u32) -> (u32, u32, u32, u32) {
        let x0 = tx * TILE_SIZE;
        let y0 = ty * TILE_SIZE;
        let w = TILE_SIZE.min(self.width - x0);
        let h = TILE_SIZE.min(self.height - y0);
        (x0, y0, w, h)
    }

    fn read_tile(&self, tx: u32, ty: u32) -> Vec<u8> {
        let (x0, y0, w, h) = self.tile_bounds(tx, ty);
        let mut out = Vec::with_capacity((w * h * 4) as usize);
        for row in 0..h {
            let start = (((y0 + row) * self.width + x0) * 4) as usize;
            out.extend_from_slice(&self.current[start..start + (w * 4) as usize]);
        }
        out
    }

    fn write_tile(&mut self, tx: u32, ty: u32, data: &[u8]) {
        let (x0, y0, w, h) = self.tile_bounds(tx, ty);
        for row in 0..h {
            let dst = (((y0 + row) * self.width + x0) * 4) as usize;
            let src = (row * w * 4) as usize;
            self.current[dst..dst + (w * 4) as usize]
                .copy_from_slice(&data[src..src + (w * 4) as usize]);
        }
    }

    /// Simpan keadaan wilayah (daftar tile) sebelum operasi menimpa wilayah itu.
    fn snapshot_tiles(&self, tiles: &[(u32, u32)]) -> UndoEntry {
        UndoEntry::Tiles {
            tiles: tiles
                .iter()
                .map(|&(tx, ty)| (tx, ty, self.read_tile(tx, ty)))
                .collect(),
        }
    }

    fn snapshot_full(&self) -> UndoEntry {
        self.snapshot_full_with(false)
    }

    fn snapshot_full_with(&self, resync_original: bool) -> UndoEntry {
        UndoEntry::Full {
            width: self.width,
            height: self.height,
            rgba: self.current.clone(),
            resync_original,
        }
    }

    /// Pasang sebuah entry ke buffer aktif dan kembalikan entry berisi keadaan
    /// yang baru saja ditimpa (untuk tumpukan seberangnya).
    fn apply_entry(&mut self, entry: &UndoEntry) -> UndoEntry {
        match entry {
            UndoEntry::Tiles { tiles } => {
                let coords: Vec<(u32, u32)> = tiles.iter().map(|&(tx, ty, _)| (tx, ty)).collect();
                let inverse = self.snapshot_tiles(&coords);
                for (tx, ty, data) in tiles {
                    self.write_tile(*tx, *ty, data);
                }
                inverse
            }
            UndoEntry::Full {
                width,
                height,
                rgba,
                resync_original,
            } => {
                let inverse = self.snapshot_full_with(*resync_original);
                self.width = *width;
                self.height = *height;
                self.current = rgba.clone();
                inverse
            }
        }
    }

    fn finalize_stroke(&mut self) {
        if let Some(tiles) = self.stroke_snapshot.take() {
            if !tiles.is_empty() {
                self.push_undo(UndoEntry::Tiles { tiles });
            }
        }
    }

    fn push_undo(&mut self, entry: UndoEntry) {
        self.undo_bytes += entry.bytes();
        self.undo.push(entry);
        while self.undo.len() > MAX_UNDO_STEPS || self.undo_bytes > MAX_UNDO_BYTES {
            if self.undo.len() <= 1 {
                break;
            }
            let removed = self.undo.remove(0);
            self.undo_bytes -= removed.bytes();
        }
        self.redo.clear();
        self.redo_bytes = 0;
    }
}

#[derive(uniffi::Object)]
pub struct EditorEngine {
    state: Mutex<EngineState>,
}

impl EditorEngine {
    fn lock(&self) -> Result<MutexGuard<'_, EngineState>, EngineError> {
        self.state.lock().map_err(|_| EngineError::Internal {
            detail: "engine state lock poisoned".to_string(),
        })
    }
}

#[uniffi::export]
impl EditorEngine {
    #[uniffi::constructor]
    pub fn open(width: u32, height: u32, rgba: Vec<u8>) -> Result<Arc<Self>, EngineError> {
        if width == 0 || height == 0 {
            return Err(EngineError::InvalidInput {
                detail: format!("image dimensions must be positive, got {width}x{height}"),
            });
        }
        if u64::from(width) * u64::from(height) > MAX_PIXELS {
            return Err(EngineError::InvalidInput {
                detail: format!("image {width}x{height} exceeds the size limit"),
            });
        }
        let expected = (width as usize) * (height as usize) * 4;
        if rgba.len() != expected {
            return Err(EngineError::InvalidInput {
                detail: format!(
                    "RGBA buffer has {} bytes, expected {expected} for {width}x{height}",
                    rgba.len()
                ),
            });
        }
        Ok(Arc::new(Self {
            state: Mutex::new(EngineState {
                width,
                height,
                original: rgba.clone(),
                current: rgba,
                undo: Vec::new(),
                redo: Vec::new(),
                undo_bytes: 0,
                redo_bytes: 0,
                stroke_snapshot: None,
                detections: Vec::new(),
                sam: None,
            }),
        }))
    }

    pub fn width(&self) -> u32 {
        self.lock().map(|s| s.width).unwrap_or(0)
    }

    pub fn height(&self) -> u32 {
        self.lock().map(|s| s.height).unwrap_or(0)
    }

    pub fn can_undo(&self) -> bool {
        self.lock().map(|s| !s.undo.is_empty()).unwrap_or(false)
    }

    pub fn can_redo(&self) -> bool {
        self.lock().map(|s| !s.redo.is_empty()).unwrap_or(false)
    }

    /// Pratinjau RGBA, diskalakan agar sisi terpanjang tidak melebihi max_px.
    pub fn preview(&self, max_px: u32) -> Result<PreviewImage, EngineError> {
        let s = self.lock()?;
        if max_px == 0 {
            return Err(EngineError::InvalidInput {
                detail: "max_px must be at least 1".to_string(),
            });
        }
        let longest = s.width.max(s.height);
        if longest <= max_px {
            return Ok(PreviewImage {
                width: s.width,
                height: s.height,
                rgba: s.current.clone(),
            });
        }
        let scale = f64::from(max_px) / f64::from(longest);
        let out_w = ((f64::from(s.width) * scale).round() as u32).max(1);
        let out_h = ((f64::from(s.height) * scale).round() as u32).max(1);
        let mut rgba = vec![0u8; (out_w * out_h * 4) as usize];
        for oy in 0..out_h {
            let sy = ((u64::from(oy) * u64::from(s.height)) / u64::from(out_h)).min(u64::from(s.height - 1)) as u32;
            for ox in 0..out_w {
                let sx = ((u64::from(ox) * u64::from(s.width)) / u64::from(out_w)).min(u64::from(s.width - 1)) as u32;
                let src = ((sy * s.width + sx) * 4) as usize;
                let dst = ((oy * out_w + ox) * 4) as usize;
                rgba[dst..dst + 4].copy_from_slice(&s.current[src..src + 4]);
            }
        }
        Ok(PreviewImage {
            width: out_w,
            height: out_h,
            rgba,
        })
    }

    /// Buffer hasil edit resolusi penuh, untuk disimpan dan diekspor.
    pub fn current_rgba(&self) -> Result<Vec<u8>, EngineError> {
        Ok(self.lock()?.current.clone())
    }

    /// Satu goresan kuas. `points` adalah polyline dalam koordinat gambar.
    pub fn brush(
        &self,
        points: Vec<ImagePoint>,
        radius: f32,
        mode: BrushMode,
    ) -> Result<(), EngineError> {
        if points.is_empty() {
            return Ok(());
        }
        if !(radius.is_finite() && radius > 0.0 && radius <= 8192.0) {
            return Err(EngineError::InvalidInput {
                detail: format!("brush radius {radius} is out of range"),
            });
        }
        let mut s = self.lock()?;

        // Tile yang tersentuh goresan ini, untuk undo.
        let mut min_x = f32::INFINITY;
        let mut min_y = f32::INFINITY;
        let mut max_x = f32::NEG_INFINITY;
        let mut max_y = f32::NEG_INFINITY;
        for p in &points {
            min_x = min_x.min(p.x - radius);
            min_y = min_y.min(p.y - radius);
            max_x = max_x.max(p.x + radius);
            max_y = max_y.max(p.y + radius);
        }
        let tx0 = ((min_x.max(0.0) as u32) / TILE_SIZE).min(s.width / TILE_SIZE);
        let ty0 = ((min_y.max(0.0) as u32) / TILE_SIZE).min(s.height / TILE_SIZE);
        let tx1 = ((max_x.max(0.0) as u32) / TILE_SIZE).min(s.width.saturating_sub(1) / TILE_SIZE);
        let ty1 = ((max_y.max(0.0) as u32) / TILE_SIZE).min(s.height.saturating_sub(1) / TILE_SIZE);
        let mut tiles = Vec::new();
        for ty in ty0..=ty1 {
            for tx in tx0..=tx1 {
                tiles.push((tx, ty));
            }
        }
        if s.stroke_snapshot.is_some() {
            // Di dalam goresan: catat tile yang belum tercatat saja, jangan
            // buat langkah undo per segmen.
            let mut fresh: Vec<(u32, u32, Vec<u8>)> = Vec::new();
            {
                let snapshot = s.stroke_snapshot.as_ref().expect("checked above");
                for &(tx, ty) in &tiles {
                    if !snapshot.iter().any(|&(sx, sy, _)| sx == tx && sy == ty)
                        && !fresh.iter().any(|&(sx, sy, _)| sx == tx && sy == ty)
                    {
                        fresh.push((tx, ty, s.read_tile(tx, ty)));
                    }
                }
            }
            s.stroke_snapshot
                .as_mut()
                .expect("checked above")
                .extend(fresh);
        } else {
            let entry = s.snapshot_tiles(&tiles);
            s.push_undo(entry);
        }

        if points.len() == 1 {
            stamp(&mut s, points[0].x, points[0].y, radius, mode);
        } else {
            for pair in points.windows(2) {
                let (a, b) = (pair[0], pair[1]);
                let dist = ((b.x - a.x).powi(2) + (b.y - a.y).powi(2)).sqrt();
                let step = (radius * 0.25).max(1.0);
                let n = (dist / step).ceil() as u32;
                for i in 0..=n {
                    let t = if n == 0 { 0.0 } else { i as f32 / n as f32 };
                    stamp(&mut s, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, radius, mode);
                }
            }
        }
        Ok(())
    }

    /// Mulai satu goresan: panggilan `brush` berikutnya sampai `end_stroke`
    /// menjadi satu langkah undo.
    pub fn begin_stroke(&self) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        if s.stroke_snapshot.is_none() {
            s.stroke_snapshot = Some(Vec::new());
        }
        Ok(())
    }

    /// Akhiri goresan dan jadikan snapshot-nya satu langkah undo.
    pub fn end_stroke(&self) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        Ok(())
    }

    /// Potong buffer ke rect yang diberikan. Original ikut dipotong agar
    /// Restore sesudah crop tetap konsisten dengan dokumen saat ini.
    pub fn crop(&self, rect: CropRect) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        // Dimensi berubah: deteksi dan prompt SAM lama tidak valid lagi.
        s.detections.clear();
        s.sam = None;
        if rect.width == 0 || rect.height == 0 {
            return Err(EngineError::InvalidInput {
                detail: "crop rect must not be empty".to_string(),
            });
        }
        if rect.x + rect.width > s.width || rect.y + rect.height > s.height {
            return Err(EngineError::InvalidInput {
                detail: format!(
                    "crop rect ({},{},{}x{}) is outside the image {}x{}",
                    rect.x, rect.y, rect.width, rect.height, s.width, s.height
                ),
            });
        }
        if rect.x == 0 && rect.y == 0 && rect.width == s.width && rect.height == s.height {
            return Ok(()); // tidak berubah, jangan catat sebagai langkah undo
        }
        let entry = s.snapshot_full_with(true);
        s.push_undo(entry);
        s.current = crop_buffer(&s.current, s.width, &rect);
        s.original = crop_buffer(&s.original, s.width, &rect);
        s.width = rect.width;
        s.height = rect.height;
        Ok(())
    }

    /// Terapkan model saliency: alfa dikalikan mask subjek dari model.
    /// Inference berjalan pada salinan RGB yang diperkecil (maks 1024px);
    /// mask hasil diperbesar lagi ke resolusi dokumen. Satu langkah undo.
    pub fn apply_ai(
        &self,
        runtime: Arc<ModelRuntime>,
        model_id: String,
        progress: Box<dyn ProgressCallback>,
    ) -> Result<(), EngineError> {
        progress.on_progress(5);
        let (rgb, w, h) = {
            let s = self.lock()?;
            small_rgb(&s, 1024)
        };
        progress.on_progress(15);
        let (mask, mw, mh) = runtime.infer_salient_mask(&model_id, &rgb, w, h)?;
        progress.on_progress(80);
        // Rapikan mask mengikuti tepi foto pada resolusi kerja (guided filter),
        // baru diperbesar ke resolusi dokumen.
        let work_mask = crate::image::resize_bilinear_f32(&mask, mw, mh, w, h);
        let refined = crate::image::guided_filter(&rgb, &work_mask, w, h, 4, 1e-3);
        let mut s = self.lock()?;
        s.finalize_stroke();
        let full_mask = crate::image::resize_bilinear_f32(&refined, w, h, s.width, s.height);
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for (i, m) in full_mask.iter().enumerate() {
            let idx = i * 4 + 3;
            let a = s.current[idx] as f32 * m.clamp(0.0, 1.0);
            s.current[idx] = a.round().clamp(0.0, 255.0) as u8;
        }
        drop(s);
        progress.on_progress(100);
        Ok(())
    }

    /// Balik mask: alfa baru = alfa asli - alfa sekarang. Selalu valid karena
    /// tidak ada operasi yang menaikkan alfa melebihi gambar asli.
    pub fn invert_mask(&self) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for i in 0..(s.width * s.height) as usize {
            let idx = i * 4 + 3;
            s.current[idx] = s.original[idx].saturating_sub(s.current[idx]);
        }
        Ok(())
    }

    /// Rapikan tepi (pengganti Smooth Edge aplikasi asli): despike pada level
    /// >= 3, erosi ringan, lalu blur kotak sesuai level. Level 0 = tanpa efek.
    pub fn refine_edges(&self, level: u8) -> Result<(), EngineError> {
        if level == 0 {
            return Ok(());
        }
        if level > 5 {
            return Err(EngineError::InvalidInput {
                detail: format!("level refine harus 0..=5, dapat {level}"),
            });
        }
        let mut s = self.lock()?;
        s.finalize_stroke();
        let (w, h) = (s.width, s.height);
        let mut alpha: Vec<u8> = s
            .current
            .chunks_exact(4)
            .map(|px| px[3])
            .collect();
        if level >= 3 {
            alpha = despike_plane(&alpha, w, h);
        }
        let erode_passes = (level / 2).min(2);
        for _ in 0..erode_passes {
            alpha = erode_plane(&alpha, w, h);
        }
        alpha = blur_plane(&alpha, w, h, u32::from(level));
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for (i, a) in alpha.iter().enumerate() {
            s.current[i * 4 + 3] = *a;
        }
        Ok(())
    }

    /// Hapus semua piksel yang warnanya mirip dengan titik contoh, di seluruh
    /// gambar (pengganti Auto-Color aplikasi asli). Toleransi 0..=100.
    pub fn auto_color(&self, x: u32, y: u32, tolerance: u8) -> Result<(), EngineError> {
        if tolerance > 100 {
            return Err(EngineError::InvalidInput {
                detail: format!("toleransi harus 0..=100, dapat {tolerance}"),
            });
        }
        let mut s = self.lock()?;
        s.finalize_stroke();
        let seed = sample_rgb(&s, x, y)?;
        let threshold = f32::from(tolerance) / 100.0 * 441.68;
        let threshold_sq = threshold * threshold;
        let entry = s.snapshot_full();
        let mut changed = false;
        for px in s.current.chunks_exact_mut(4) {
            if px[3] == 0 {
                continue;
            }
            let dr = px[0] as f32 - seed.0 as f32;
            let dg = px[1] as f32 - seed.1 as f32;
            let db = px[2] as f32 - seed.2 as f32;
            if dr * dr + dg * dg + db * db <= threshold_sq {
                px[3] = 0;
                changed = true;
            }
        }
        if changed {
            s.push_undo(entry);
        }
        Ok(())
    }

    /// Hapus wilayah terhubung yang warnanya mirip titik contoh, dibatasi
    /// lingkaran `radius` di sekitar titik (pengganti Magic aplikasi asli).
    /// Radius 0 berarti tanpa batas.
    pub fn magic(&self, x: u32, y: u32, tolerance: u8, radius: u32) -> Result<(), EngineError> {
        if tolerance > 100 {
            return Err(EngineError::InvalidInput {
                detail: format!("toleransi harus 0..=100, dapat {tolerance}"),
            });
        }
        let mut s = self.lock()?;
        s.finalize_stroke();
        let seed_rgb = sample_rgb(&s, x, y)?;
        let (w, h) = (s.width as i64, s.height as i64);
        let threshold = f32::from(tolerance) / 100.0 * 441.68;
        let threshold_sq = threshold * threshold;
        let radius_sq = i64::from(radius) * i64::from(radius);
        let mut visited = vec![false; (w * h) as usize];
        let mut queue: VecDeque<(i64, i64)> = VecDeque::new();
        let mut region: Vec<usize> = Vec::new();
        queue.push_back((i64::from(x), i64::from(y)));
        visited[(y as i64 * w + x as i64) as usize] = true;
        while let Some((cx, cy)) = queue.pop_front() {
            let idx = (cy * w + cx) as usize;
            let base = idx * 4;
            let dr = s.current[base] as f32 - seed_rgb.0 as f32;
            let dg = s.current[base + 1] as f32 - seed_rgb.1 as f32;
            let db = s.current[base + 2] as f32 - seed_rgb.2 as f32;
            if dr * dr + dg * dg + db * db > threshold_sq {
                continue;
            }
            region.push(idx);
            for (nx, ny) in [(cx - 1, cy), (cx + 1, cy), (cx, cy - 1), (cx, cy + 1)] {
                if nx < 0 || ny < 0 || nx >= w || ny >= h {
                    continue;
                }
                let nidx = (ny * w + nx) as usize;
                if visited[nidx] {
                    continue;
                }
                if radius > 0 {
                    let dx = nx - i64::from(x);
                    let dy = ny - i64::from(y);
                    if dx * dx + dy * dy > radius_sq {
                        continue;
                    }
                }
                visited[nidx] = true;
                queue.push_back((nx, ny));
            }
        }
        if region.is_empty() {
            return Ok(());
        }
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for idx in region {
            s.current[idx * 4 + 3] = 0;
        }
        Ok(())
    }

    /// Deteksi objek dengan model segmentasi instans. Hasil disimpan di
    /// engine; yang dikembalikan ke Kotlin hanya metadata untuk daftar pilih.
    pub fn detect_objects(
        &self,
        runtime: Arc<ModelRuntime>,
        model_id: String,
        progress: Box<dyn ProgressCallback>,
    ) -> Result<Vec<DetectedObject>, EngineError> {
        progress.on_progress(5);
        let (rgb, w, h) = {
            let s = self.lock()?;
            small_rgb(&s, 1024)
        };
        progress.on_progress(20);
        let found = runtime.run_instance_seg(&model_id, &rgb, w, h, 0.25)?;
        progress.on_progress(90);
        let mut s = self.lock()?;
        s.finalize_stroke();
        s.detections = found
            .into_iter()
            .map(|d| StoredDetection {
                label: d.label,
                score: d.score,
                x: d.x,
                y: d.y,
                width: d.width,
                height: d.height,
                mask: d.mask,
                mask_width: d.mask_width,
                mask_height: d.mask_height,
            })
            .collect();
        let out: Vec<DetectedObject> = s
            .detections
            .iter()
            .enumerate()
            .map(|(i, d)| DetectedObject {
                index: i as u32,
                label: d.label.clone(),
                score: d.score,
                x: d.x,
                y: d.y,
                width: d.width,
                height: d.height,
            })
            .collect();
        drop(s);
        progress.on_progress(100);
        Ok(out)
    }

    pub fn detection_count(&self) -> u32 {
        self.lock().map(|s| s.detections.len() as u32).unwrap_or(0)
    }

    pub fn clear_detections(&self) {
        if let Ok(mut s) = self.lock() {
            s.detections.clear();
        }
    }

    /// Terapkan pilihan objek: keep_only=true mempertahankan hanya objek
    /// terpilih (sisanya transparan); false menghapus objek terpilih.
    /// Satu langkah undo. Deteksi dibersihkan setelah diterapkan.
    pub fn apply_object_selection(
        &self,
        indices: Vec<u32>,
        keep_only: bool,
    ) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        if s.detections.is_empty() {
            return Err(EngineError::InvalidInput {
                detail: "belum ada hasil deteksi".into(),
            });
        }
        if indices.is_empty() {
            return Err(EngineError::InvalidInput {
                detail: "pilihan objek kosong".into(),
            });
        }
        let (w, h) = (s.width, s.height);
        let mut union = vec![0f32; (w * h) as usize];
        for &idx in &indices {
            let d = s
                .detections
                .get(idx as usize)
                .ok_or_else(|| EngineError::InvalidInput {
                    detail: format!("indeks objek tidak valid: {idx}"),
                })?;
            let full =
                crate::image::resize_bilinear_f32(&d.mask, d.mask_width, d.mask_height, w, h);
            for (u, v) in union.iter_mut().zip(full.iter()) {
                *u = u.max(*v);
            }
        }
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for (i, u) in union.iter().enumerate() {
            let idx = i * 4 + 3;
            let factor = if keep_only { *u } else { 1.0 - *u };
            let a = s.current[idx] as f32 * factor.clamp(0.0, 1.0);
            s.current[idx] = a.round().clamp(0.0, 255.0) as u8;
        }
        s.detections.clear();
        Ok(())
    }

    /// Mulai sesi prompt SAM: encode gambar saat ini dan kosongkan titik.
    pub fn sam_start(
        &self,
        runtime: Arc<ModelRuntime>,
        model_id: String,
        progress: Box<dyn ProgressCallback>,
    ) -> Result<(), EngineError> {
        progress.on_progress(5);
        let (rgb, w, h) = {
            let s = self.lock()?;
            small_rgb(&s, 1024)
        };
        let key = format!("sam-{}", next_sam_key());
        runtime.sam_encode(&model_id, &key, &rgb, w, h)?;
        progress.on_progress(90);
        let mut s = self.lock()?;
        s.finalize_stroke();
        s.sam = Some(SamState {
            model_id,
            key,
            work_width: w,
            work_height: h,
            points: Vec::new(),
            mask: None,
        });
        drop(s);
        progress.on_progress(100);
        Ok(())
    }

    pub fn sam_is_active(&self) -> bool {
        self.lock().map(|s| s.sam.is_some()).unwrap_or(false)
    }

    pub fn sam_point_count(&self) -> u32 {
        self.lock()
            .map(|s| s.sam.as_ref().map(|x| x.points.len() as u32).unwrap_or(0))
            .unwrap_or(0)
    }

    /// Tambah titik prompt dan kembalikan pratinjau mask (RGBA berwarna).
    pub fn sam_add_point(
        &self,
        runtime: Arc<ModelRuntime>,
        x: f32,
        y: f32,
        positive: bool,
    ) -> Result<PreviewImage, EngineError> {
        let (model_id, points) = {
            let mut s = self.lock()?;
            let (doc_w, doc_h) = (s.width, s.height);
            let sam = s.sam.as_mut().ok_or_else(|| EngineError::InvalidInput {
                detail: "sesi SAM belum dimulai".into(),
            })?;
            if sam.points.len() >= 20 {
                return Err(EngineError::InvalidInput {
                    detail: "maksimum 20 titik prompt".into(),
                });
            }
            // Titik datang dalam koordinat dokumen; simpan dalam koordinat
            // resolusi kerja yang dipakai embedding.
            let wx = x * sam.work_width as f32 / doc_w as f32;
            let wy = y * sam.work_height as f32 / doc_h as f32;
            sam.points.push((wx, wy, positive));
            (sam.model_id.clone(), sam.points.clone())
        };
        let logits = runtime.sam_decode(&model_id, &points)?;
        self.store_sam_mask(logits)
    }

    /// Urungkan titik prompt terakhir dan kembalikan pratinjau terbaru.
    pub fn sam_undo_point(
        &self,
        runtime: Arc<ModelRuntime>,
    ) -> Result<PreviewImage, EngineError> {
        let (model_id, points) = {
            let mut s = self.lock()?;
            let sam = s.sam.as_mut().ok_or_else(|| EngineError::InvalidInput {
                detail: "sesi SAM belum dimulai".into(),
            })?;
            sam.points.pop();
            (sam.model_id.clone(), sam.points.clone())
        };
        if points.is_empty() {
            let mut s = self.lock()?;
            if let Some(sam) = s.sam.as_mut() {
                sam.mask = None;
            }
            return self.sam_preview();
        }
        let logits = runtime.sam_decode(&model_id, &points)?;
        self.store_sam_mask(logits)
    }

    fn store_sam_mask(&self, logits: Vec<f32>) -> Result<PreviewImage, EngineError> {
        let mut s = self.lock()?;
        let (w, h) = (s.width, s.height);
        let (work_w, work_h) = match s.sam.as_ref() {
            Some(sam) => (sam.work_width, sam.work_height),
            None => {
                return Err(EngineError::InvalidInput {
                    detail: "sesi SAM sudah berakhir".into(),
                })
            }
        };
        if logits.len() != (work_w * work_h) as usize {
            return Err(EngineError::Internal {
                detail: "ukuran mask SAM tidak sama dengan resolusi kerja".into(),
            });
        }
        // Mask datang pada resolusi kerja; naikkan ke resolusi dokumen.
        let full = crate::image::resize_bilinear_f32(&logits, work_w, work_h, w, h);
        let mask: Vec<u8> = full
            .iter()
            .map(|&v| if v > 0.0 { 255u8 } else { 0u8 })
            .collect();
        match s.sam.as_mut() {
            Some(sam) => sam.mask = Some(mask),
            None => {
                return Err(EngineError::InvalidInput {
                    detail: "sesi SAM sudah berakhir".into(),
                })
            }
        }
        drop(s);
        self.sam_preview()
    }

    /// Pratinjau mask SAM saat ini sebagai RGBA berwarna (untuk overlay).
    pub fn sam_preview(&self) -> Result<PreviewImage, EngineError> {
        let s = self.lock()?;
        let (w, h) = (s.width, s.height);
        let scale = if w.max(h) > 512 {
            512.0 / w.max(h) as f32
        } else {
            1.0
        };
        let pw = ((w as f32 * scale).round() as u32).max(1);
        let ph = ((h as f32 * scale).round() as u32).max(1);
        let mut rgba = vec![0u8; (pw * ph * 4) as usize];
        if let Some(mask) = s.sam.as_ref().and_then(|x| x.mask.as_ref()) {
            for oy in 0..ph {
                for ox in 0..pw {
                    let sx = ((ox as f32 / scale) as u32).min(w - 1);
                    let sy = ((oy as f32 / scale) as u32).min(h - 1);
                    if mask[(sy * w + sx) as usize] > 0 {
                        let i = ((oy * pw + ox) * 4) as usize;
                        rgba[i] = 16;
                        rgba[i + 1] = 185;
                        rgba[i + 2] = 129;
                        rgba[i + 3] = 110;
                    }
                }
            }
        }
        Ok(PreviewImage {
            width: pw,
            height: ph,
            rgba,
        })
    }

    /// Terapkan mask SAM: keep_only=true mempertahankan wilayah mask saja.
    /// Sesi prompt diakhiri. Satu langkah undo.
    pub fn sam_apply(
        &self,
        runtime: Arc<ModelRuntime>,
        keep_only: bool,
    ) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        let sam = s.sam.take().ok_or_else(|| EngineError::InvalidInput {
            detail: "sesi SAM belum dimulai".into(),
        })?;
        let key = sam.key.clone();
        let mask = sam.mask.ok_or_else(|| EngineError::InvalidInput {
            detail: "belum ada mask; tambahkan titik prompt dulu".into(),
        })?;
        let entry = s.snapshot_full();
        s.push_undo(entry);
        for (i, m) in mask.iter().enumerate() {
            let idx = i * 4 + 3;
            let inside = *m > 0;
            if inside != keep_only {
                s.current[idx] = 0;
            }
        }
        drop(s);
        runtime.sam_drop(&key);
        Ok(())
    }

    /// Batalkan sesi prompt SAM tanpa mengubah gambar.
    pub fn sam_cancel(&self, runtime: Arc<ModelRuntime>) -> Result<(), EngineError> {
        let mut s = self.lock()?;
        if let Some(sam) = s.sam.take() {
            drop(s);
            runtime.sam_drop(&sam.key);
        }
        Ok(())
    }

    pub fn undo(&self) -> Result<bool, EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        let Some(entry) = s.undo.pop() else {
            return Ok(false);
        };
        s.undo_bytes -= entry.bytes();
        let inverse = s.apply_entry(&entry);
        if matches!(entry, UndoEntry::Full { resync_original: true, .. }) {
            // Dimensi berubah: original harus mengikuti keadaan buffer hasil undo.
            s.original = s.current.clone();
        }
        s.redo_bytes += inverse.bytes();
        s.redo.push(inverse);
        Ok(true)
    }

    pub fn redo(&self) -> Result<bool, EngineError> {
        let mut s = self.lock()?;
        s.finalize_stroke();
        let Some(entry) = s.redo.pop() else {
            return Ok(false);
        };
        s.redo_bytes -= entry.bytes();
        let inverse = s.apply_entry(&entry);
        if matches!(entry, UndoEntry::Full { resync_original: true, .. }) {
            s.original = s.current.clone();
        }
        s.undo_bytes += inverse.bytes();
        s.undo.push(inverse);
        Ok(true)
    }
}

static SAM_KEY_COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

fn next_sam_key() -> u64 {
    SAM_KEY_COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
}

/// Salinan RGB dokumen diperkecil ke sisi terpanjang `max_side` (untuk input
/// inference; RGB utuh walau alfa sudah dihapus, sama seperti foto aslinya).
fn small_rgb(s: &EngineState, max_side: u32) -> (Vec<u8>, u32, u32) {
    let longest = s.width.max(s.height);
    let (tw, th) = if longest > max_side {
        let scale = max_side as f32 / longest as f32;
        (
            ((s.width as f32 * scale).round() as u32).max(1),
            ((s.height as f32 * scale).round() as u32).max(1),
        )
    } else {
        (s.width, s.height)
    };
    let small = crate::image::resize_bilinear_rgba(&s.current, s.width, s.height, tw, th);
    let mut rgb = Vec::with_capacity((tw * th * 3) as usize);
    for px in small.chunks_exact(4) {
        rgb.extend_from_slice(&px[..3]);
    }
    (rgb, tw, th)
}

fn sample_rgb(s: &EngineState, x: u32, y: u32) -> Result<(u8, u8, u8), EngineError> {
    if x >= s.width || y >= s.height {
        return Err(EngineError::InvalidInput {
            detail: format!("titik ({x},{y}) di luar gambar {}x{}", s.width, s.height),
        });
    }
    let idx = ((y * s.width + x) * 4) as usize;
    Ok((s.current[idx], s.current[idx + 1], s.current[idx + 2]))
}

/// Erosi 3x3 (nilai minimum tetangga): menyusutkan wilayah opak 1px.
fn erode_plane(src: &[u8], w: u32, h: u32) -> Vec<u8> {
    let mut out = vec![0u8; src.len()];
    for y in 0..h as i64 {
        for x in 0..w as i64 {
            let mut min = u8::MAX;
            for dy in -1..=1i64 {
                for dx in -1..=1i64 {
                    let nx = (x + dx).clamp(0, w as i64 - 1);
                    let ny = (y + dy).clamp(0, h as i64 - 1);
                    min = min.min(src[(ny * w as i64 + nx) as usize]);
                }
            }
            out[(y * w as i64 + x) as usize] = min;
        }
    }
    out
}

/// Buang piksel menyendiri: piksel opak yang keempat tetangganya transparan
/// dibuat transparan, dan sebaliknya untuk lubang 1px di wilayah opak.
fn despike_plane(src: &[u8], w: u32, h: u32) -> Vec<u8> {
    let mut out = src.to_vec();
    if w < 3 || h < 3 {
        return out;
    }
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let idx = (y * w + x) as usize;
            let neighbors = [
                src[idx - 1],
                src[idx + 1],
                src[idx - w as usize],
                src[idx + w as usize],
            ];
            if src[idx] > 0 && neighbors.iter().all(|&n| n == 0) {
                out[idx] = 0;
            } else if src[idx] == 0 && neighbors.iter().all(|&n| n >= 250) {
                out[idx] = 255;
            }
        }
    }
    out
}

/// Blur kotak separabel dengan jendela geser, radius dalam piksel.
fn blur_plane(src: &[u8], w: u32, h: u32, radius: u32) -> Vec<u8> {
    if radius == 0 || w == 0 || h == 0 {
        return src.to_vec();
    }
    let r = radius as i64;
    let (w64, h64) = (w as i64, h as i64);
    // Lolos horizontal.
    let mut tmp = vec![0u8; src.len()];
    for y in 0..h64 {
        let row = (y * w64) as usize;
        let mut sum: u32 = 0;
        for k in -r..=r {
            sum += u32::from(src[row + k.clamp(0, w64 - 1) as usize]);
        }
        for x in 0..w64 {
            let count = (2 * r + 1) as u32;
            tmp[row + x as usize] = (sum / count) as u8;
            let leave = (x - r).clamp(0, w64 - 1) as usize;
            let enter = (x + r + 1).clamp(0, w64 - 1) as usize;
            sum = sum - u32::from(src[row + leave]) + u32::from(src[row + enter]);
        }
    }
    // Lolos vertikal.
    let mut out = vec![0u8; src.len()];
    for x in 0..w64 {
        let mut sum: u32 = 0;
        for k in -r..=r {
            sum += u32::from(tmp[(k.clamp(0, h64 - 1) * w64 + x) as usize]);
        }
        for y in 0..h64 {
            let count = (2 * r + 1) as u32;
            out[(y * w64 + x) as usize] = (sum / count) as u8;
            let leave = (y - r).clamp(0, h64 - 1) as usize;
            let enter = (y + r + 1).clamp(0, h64 - 1) as usize;
            sum = sum - u32::from(tmp[(leave as i64 * w64 + x) as usize])
                + u32::from(tmp[(enter as i64 * w64 + x) as usize]);
        }
    }
    out
}

fn crop_buffer(buf: &[u8], width: u32, rect: &CropRect) -> Vec<u8> {
    let mut out = Vec::with_capacity((rect.width * rect.height * 4) as usize);
    for row in 0..rect.height {
        let start = (((rect.y + row) * width + rect.x) * 4) as usize;
        out.extend_from_slice(&buf[start..start + (rect.width * 4) as usize]);
    }
    out
}

/// Cap lingkaran bertepi lembut di (cx, cy). Erase menurunkan alfa; Restore
/// mengembalikan alfa ke nilai gambar asli. RGB tidak pernah diubah brush.
fn stamp(s: &mut EngineState, cx: f32, cy: f32, radius: f32, mode: BrushMode) {
    let x0 = (cx - radius).floor().max(0.0) as u32;
    let y0 = (cy - radius).floor().max(0.0) as u32;
    let x1 = ((cx + radius).ceil() as u32).min(s.width);
    let y1 = ((cy + radius).ceil() as u32).min(s.height);
    let inner = radius * 0.7;
    for y in y0..y1 {
        for x in x0..x1 {
            let dx = x as f32 + 0.5 - cx;
            let dy = y as f32 + 0.5 - cy;
            let d = (dx * dx + dy * dy).sqrt();
            if d >= radius {
                continue;
            }
            let fall = if d <= inner {
                1.0
            } else {
                1.0 - (d - inner) / (radius - inner)
            };
            let idx = ((y * s.width + x) * 4 + 3) as usize;
            let a = f32::from(s.current[idx]);
            let new_a = match mode {
                BrushMode::Erase => a * (1.0 - fall),
                BrushMode::Restore => {
                    let orig = f32::from(s.original[idx]);
                    a + (orig - a) * fall
                }
            };
            s.current[idx] = new_a.round().clamp(0.0, 255.0) as u8;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine(w: u32, h: u32, alpha: u8) -> Arc<EditorEngine> {
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for px in rgba.chunks_exact_mut(4) {
            px[0] = 200;
            px[1] = 100;
            px[2] = 50;
            px[3] = alpha;
        }
        EditorEngine::open(w, h, rgba).unwrap()
    }

    fn alpha_at(e: &EditorEngine, x: u32, y: u32) -> u8 {
        let s = e.lock().unwrap();
        s.current[((y * s.width + x) * 4 + 3) as usize]
    }

    #[test]
    fn open_validates_buffer() {
        assert!(EditorEngine::open(2, 2, vec![0u8; 15]).is_err());
        assert!(EditorEngine::open(0, 2, vec![]).is_err());
        assert!(EditorEngine::open(2, 2, vec![0u8; 16]).is_ok());
    }

    #[test]
    fn erase_reduces_alpha_and_restore_brings_it_back() {
        let e = engine(40, 40, 255);
        e.brush(vec![ImagePoint { x: 20.0, y: 20.0 }], 6.0, BrushMode::Erase)
            .unwrap();
        assert_eq!(alpha_at(&e, 20, 20), 0);
        assert_eq!(alpha_at(&e, 0, 0), 255);
        e.brush(vec![ImagePoint { x: 20.0, y: 20.0 }], 6.0, BrushMode::Restore)
            .unwrap();
        assert_eq!(alpha_at(&e, 20, 20), 255);
    }

    #[test]
    fn polyline_stroke_is_continuous() {
        let e = engine(60, 20, 255);
        e.brush(
            vec![
                ImagePoint { x: 5.0, y: 10.0 },
                ImagePoint { x: 55.0, y: 10.0 },
            ],
            3.0,
            BrushMode::Erase,
        )
        .unwrap();
        for x in [10, 25, 40] {
            assert_eq!(alpha_at(&e, x, 10), 0, "gap at x={x}");
        }
    }

    #[test]
    fn undo_restores_tiles_and_redo_reapplies() {
        let e = engine(600, 40, 255); // melewati batas tile 256
        e.brush(vec![ImagePoint { x: 300.0, y: 20.0 }], 5.0, BrushMode::Erase)
            .unwrap();
        assert_eq!(alpha_at(&e, 300, 20), 0);
        assert!(e.can_undo());
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 300, 20), 255);
        assert!(e.can_redo());
        assert!(e.redo().unwrap());
        assert_eq!(alpha_at(&e, 300, 20), 0);
        assert!(!e.redo().unwrap());
    }

    #[test]
    fn new_stroke_clears_redo() {
        let e = engine(30, 30, 255);
        let p = vec![ImagePoint { x: 15.0, y: 15.0 }];
        e.brush(p.clone(), 4.0, BrushMode::Erase).unwrap();
        e.undo().unwrap();
        assert!(e.can_redo());
        e.brush(p, 4.0, BrushMode::Erase).unwrap();
        assert!(!e.can_redo());
    }

    #[test]
    fn crop_changes_dimensions_and_undo_restores_them() {
        let e = engine(100, 80, 255);
        e.crop(CropRect {
            x: 10,
            y: 5,
            width: 40,
            height: 30,
        })
        .unwrap();
        assert_eq!((e.width(), e.height()), (40, 30));
        assert!(e.undo().unwrap());
        assert_eq!((e.width(), e.height()), (100, 80));
        assert!(e.redo().unwrap());
        assert_eq!((e.width(), e.height()), (40, 30));
    }

    #[test]
    fn crop_rejects_out_of_bounds() {
        let e = engine(50, 50, 255);
        assert!(e
            .crop(CropRect {
                x: 40,
                y: 0,
                width: 20,
                height: 10
            })
            .is_err());
        assert_eq!((e.width(), e.height()), (50, 50));
        assert!(!e.can_undo());
    }

    #[test]
    fn preview_scales_to_max_px() {
        let e = engine(400, 200, 255);
        let p = e.preview(100).unwrap();
        assert_eq!((p.width, p.height), (100, 50));
        assert_eq!(p.rgba.len(), 100 * 50 * 4);
        let full = e.preview(10_000).unwrap();
        assert_eq!((full.width, full.height), (400, 200));
    }

    #[test]
    fn stroke_transaction_is_one_undo_step() {
        let e = engine(80, 30, 255);
        e.begin_stroke().unwrap();
        // Dua segmen dalam satu goresan, pindah lintas tile.
        e.brush(vec![ImagePoint { x: 10.0, y: 15.0 }], 4.0, BrushMode::Erase)
            .unwrap();
        e.brush(vec![ImagePoint { x: 60.0, y: 15.0 }], 4.0, BrushMode::Erase)
            .unwrap();
        assert_eq!(alpha_at(&e, 10, 15), 0);
        assert_eq!(alpha_at(&e, 60, 15), 0);
        e.end_stroke().unwrap();
        // Satu undo harus mengembalikan keduanya.
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 10, 15), 255);
        assert_eq!(alpha_at(&e, 60, 15), 255);
        assert!(!e.can_undo());
    }

    #[test]
    fn empty_stroke_creates_no_undo_step() {
        let e = engine(30, 30, 255);
        e.begin_stroke().unwrap();
        e.end_stroke().unwrap();
        assert!(!e.can_undo());
    }

    #[test]
    fn brush_outside_image_is_harmless() {
        let e = engine(20, 20, 255);
        e.brush(vec![ImagePoint { x: -50.0, y: -50.0 }], 5.0, BrushMode::Erase)
            .unwrap();
        assert_eq!(alpha_at(&e, 10, 10), 255);
    }

    fn two_color_engine() -> Arc<EditorEngine> {
        // Separuh kiri merah, separuh kanan biru, semua opak.
        let (w, h) = (40u32, 20u32);
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                let i = ((y * w + x) * 4) as usize;
                if x < 20 {
                    rgba[i] = 255;
                } else {
                    rgba[i + 2] = 255;
                }
                rgba[i + 3] = 255;
            }
        }
        EditorEngine::open(w, h, rgba).unwrap()
    }

    #[test]
    fn auto_color_removes_matching_color_globally() {
        let e = two_color_engine();
        e.auto_color(5, 10, 10).unwrap();
        assert_eq!(alpha_at(&e, 5, 10), 0);
        assert_eq!(alpha_at(&e, 15, 10), 0, "merah jauh dari titik ikut hilang");
        assert_eq!(alpha_at(&e, 30, 10), 255, "biru tidak tersentuh");
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 5, 10), 255);
    }

    #[test]
    fn magic_removes_only_connected_region() {
        let e = two_color_engine();
        e.magic(5, 10, 10, 0).unwrap();
        assert_eq!(alpha_at(&e, 19, 10), 0, "merah terhubung hilang sampai batas");
        assert_eq!(alpha_at(&e, 30, 10), 255, "biru utuh");
    }

    #[test]
    fn magic_respects_radius() {
        let e = two_color_engine();
        e.magic(2, 10, 10, 5).unwrap();
        assert_eq!(alpha_at(&e, 4, 10), 0, "dalam radius hilang");
        assert_eq!(alpha_at(&e, 15, 10), 255, "luar radius aman walau terhubung");
    }

    #[test]
    fn invert_mask_flips_alpha_against_original() {
        let e = engine(20, 20, 255);
        e.brush(vec![ImagePoint { x: 10.0, y: 10.0 }], 4.0, BrushMode::Erase)
            .unwrap();
        assert_eq!(alpha_at(&e, 10, 10), 0);
        e.invert_mask().unwrap();
        assert_eq!(alpha_at(&e, 10, 10), 255, "yang terhapus jadi opak");
        assert_eq!(alpha_at(&e, 0, 0), 0, "yang opak jadi transparan");
        // Undo invert mengembalikan hasil brush, dan original tidak bergeser:
        // Restore harus tetap bisa mengembalikan ke 255 di titik brush.
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 10, 10), 0);
    }

    #[test]
    fn refine_edges_softens_step_and_level_zero_is_noop() {
        let (w, h) = (30u32, 10u32);
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                let i = ((y * w + x) * 4) as usize;
                rgba[i] = 120;
                rgba[i + 3] = if x < 15 { 255 } else { 0 };
            }
        }
        let e = EditorEngine::open(w, h, rgba).unwrap();
        e.refine_edges(0).unwrap();
        assert!(!e.can_undo(), "level 0 tidak boleh mencatat undo");
        e.refine_edges(2).unwrap();
        let edge = alpha_at(&e, 14, 5);
        assert!(
            edge > 0 && edge < 255,
            "piksel tepi harus melunak, dapat {edge}"
        );
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 14, 5), 255);
    }

    #[test]
    fn refine_edges_rejects_level_above_five() {
        let e = engine(10, 10, 255);
        assert!(e.refine_edges(6).is_err());
    }

    struct NoopProgress;
    impl crate::ProgressCallback for NoopProgress {
        fn on_progress(&self, _percent: u32) {}
    }

    fn host_runtime() -> Option<Arc<crate::inference::ModelRuntime>> {
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let dylib = manifest.join("third_party/onnxruntime/linux-x64/libonnxruntime.so");
        if !dylib.is_file() {
            eprintln!("lewati: lib ORT host belum diunduh");
            return None;
        }
        Some(
            crate::inference::ModelRuntime::new(dylib.to_string_lossy().into_owned()).unwrap(),
        )
    }

    #[test]
    fn sam_flow_selects_square_and_undoes() {
        let Some(rt) = host_runtime() else { return };
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let dir = manifest.join("../../models/sam");
        assert!(dir.join("encoder.onnx").is_file());
        rt.register_model(
            "sam".into(),
            dir.to_string_lossy().into_owned(),
            crate::inference::ModelKind::PointSam,
            1024,
            1024,
        )
        .unwrap();
        // Dokumen 256x256: bujursangkar putih di tengah, latar gelap.
        let (w, h) = (256u32, 256u32);
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                let i = ((y * w + x) * 4) as usize;
                if (64..192).contains(&x) && (64..192).contains(&y) {
                    rgba[i] = 255;
                    rgba[i + 1] = 255;
                    rgba[i + 2] = 255;
                }
                rgba[i + 3] = 255;
            }
        }
        let e = EditorEngine::open(w, h, rgba).unwrap();
        e.sam_start(rt.clone(), "sam".into(), Box::new(NoopProgress))
            .unwrap();
        assert!(e.sam_is_active());
        let preview = e.sam_add_point(rt.clone(), 128.0, 128.0, true).unwrap();
        assert_eq!(e.sam_point_count(), 1);
        // Pratinjau: piksel tengah berwarna (alfa > 0), sudut transparan.
        let pw = preview.width;
        let center_px = preview.rgba[((128 * pw + 128) * 4 + 3) as usize];
        assert!(center_px > 0, "pratinjau tengah harus berwarna");
        assert_eq!(preview.rgba[3], 0, "pratinjau sudut harus transparan");
        e.sam_apply(rt.clone(), true).unwrap();
        assert!(!e.sam_is_active());
        assert_eq!(alpha_at(&e, 128, 128), 255, "subjek dipertahankan");
        assert_eq!(alpha_at(&e, 4, 4), 0, "latar terhapus");
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, 4, 4), 255, "undo mengembalikan latar");
    }

    #[test]
    fn detect_and_keep_only_bus() {
        let Some(rt) = host_runtime() else { return };
        let manifest = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let rgb_path = manifest.join("../../tools/work/test_bus.rgb");
        if !rgb_path.is_file() {
            eprintln!("lewati: fixture test_bus.rgb belum dibuat");
            return;
        }
        let size = std::fs::read_to_string(manifest.join("../../tools/work/test_bus.size"))
            .unwrap();
        let mut parts = size.split_whitespace();
        let (w, h): (u32, u32) = (
            parts.next().unwrap().parse().unwrap(),
            parts.next().unwrap().parse().unwrap(),
        );
        let rgb = std::fs::read(rgb_path).unwrap();
        let mut rgba = vec![0u8; (w * h * 4) as usize];
        for i in 0..(w * h) as usize {
            rgba[i * 4..i * 4 + 3].copy_from_slice(&rgb[i * 3..i * 3 + 3]);
            rgba[i * 4 + 3] = 255;
        }
        let model = manifest.join("../../models/instance/yolo11n-seg.onnx");
        rt.register_model(
            "yolo".into(),
            model.to_string_lossy().into_owned(),
            crate::inference::ModelKind::InstanceSeg,
            640,
            640,
        )
        .unwrap();
        let e = EditorEngine::open(w, h, rgba).unwrap();
        let found = e
            .detect_objects(rt.clone(), "yolo".into(), Box::new(NoopProgress))
            .unwrap();
        assert!(found.len() >= 3, "deteksi: {}", found.len());
        let bus = found.iter().find(|d| d.label == "bus").unwrap();
        // Titik jauh di luar bus (pojok atas): setelah keep-only bus, transparan.
        e.apply_object_selection(vec![bus.index], true).unwrap();
        assert_eq!(e.detection_count(), 0);
        let bus_center_x = bus.x + bus.width / 2;
        let bus_center_y = bus.y + bus.height / 2;
        assert!(
            alpha_at(&e, bus_center_x, bus_center_y) > 0,
            "tengah bus harus tetap terlihat"
        );
        assert!(e.undo().unwrap());
        assert_eq!(alpha_at(&e, bus_center_x, bus_center_y), 255);
    }
}
