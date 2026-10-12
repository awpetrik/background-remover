//! eraser-core: mesin pengolahan gambar dan inference untuk remake Background Eraser.
//!
//! Fungsi bebas di berkas ini membuktikan jembatan UniFFI dua arah (panggilan
//! Kotlin -> Rust, callback Rust -> Kotlin, error terstruktur). Mesin edit
//! yang sebenarnya ada di modul `editor` (`EditorEngine`, design doc 7.2).

pub mod editor;
pub mod image;
pub mod inference;
pub mod segment;

use std::fmt;

/// Error seragam untuk mutex yang poisoned di seluruh crate.
pub(crate) fn lock_err<T>(_: std::sync::PoisonError<T>) -> EngineError {
    EngineError::Internal {
        detail: "kunci internal rusak (poisoned)".to_string(),
    }
}

uniffi::setup_scaffolding!();

/// Error yang menyeberang FFI. Varian membawa pesan untuk log; teks yang
/// ditampilkan ke pengguna diterjemahkan di Kotlin dari jenis variannya.
#[derive(Debug, uniffi::Error)]
pub enum EngineError {
    InvalidInput { detail: String },
    Internal { detail: String },
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::InvalidInput { detail } => write!(f, "INVALID_INPUT: {detail}"),
            EngineError::Internal { detail } => write!(f, "INTERNAL: {detail}"),
        }
    }
}

impl std::error::Error for EngineError {}

/// Callback progres dari Rust ke Kotlin, dipakai pekerjaan berat (AI, ekspor).
#[uniffi::export(callback_interface)]
pub trait ProgressCallback: Send + Sync {
    fn on_progress(&self, percent: u32);
}

/// Pemeriksaan jembatan paling dasar: mengembalikan string dari Rust.
#[uniffi::export]
pub fn ping(name: String) -> String {
    format!("pong, {name} (eraser-core {})", env!("CARGO_PKG_VERSION"))
}

/// Pekerjaan demo untuk menguji callback progres dari pekerjaan berat.
#[uniffi::export]
pub fn run_demo_job(steps: u32, callback: Box<dyn ProgressCallback>) -> Result<u64, EngineError> {
    if steps == 0 {
        return Err(EngineError::InvalidInput {
            detail: "steps must be at least 1".to_string(),
        });
    }
    let mut acc: u64 = 0;
    for i in 1..=steps {
        acc += u64::from(i);
        callback.on_progress(i * 100 / steps);
    }
    Ok(acc)
}

/// Selalu gagal dengan error terstruktur, untuk membuktikan jalur error FFI.
#[uniffi::export]
pub fn fail_demo() -> Result<(), EngineError> {
    Err(EngineError::Internal {
        detail: "demo failure, generated on request".to_string(),
    })
}

/// Checksum atas buffer RGBA. Benih untuk operasi piksel Fase 1; juga membuktikan
/// pemindahan buffer byte lewat FFI.
#[uniffi::export]
pub fn rgba_checksum(rgba: Vec<u8>) -> Result<u64, EngineError> {
    if rgba.len() % 4 != 0 {
        return Err(EngineError::InvalidInput {
            detail: format!("RGBA buffer length {} is not a multiple of 4", rgba.len()),
        });
    }
    let mut acc: u64 = 0;
    for (i, b) in rgba.iter().enumerate() {
        acc = acc.wrapping_add(u64::from(*b) * (i as u64 + 1));
    }
    Ok(acc)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[derive(Clone, Default)]
    struct MockCallback {
        seen: Arc<Mutex<Vec<u32>>>,
    }

    impl ProgressCallback for MockCallback {
        fn on_progress(&self, percent: u32) {
            self.seen.lock().unwrap().push(percent);
        }
    }

    #[test]
    fn ping_mentions_name_and_version() {
        let out = ping("kotlin".to_string());
        assert!(out.contains("kotlin"));
        assert!(out.contains("0.1.0"));
    }

    #[test]
    fn demo_job_reports_progress_to_100() {
        let cb = MockCallback::default();
        let seen = cb.seen.clone();
        let sum = run_demo_job(4, Box::new(cb)).unwrap();
        assert_eq!(sum, 10);
        assert_eq!(*seen.lock().unwrap(), vec![25, 50, 75, 100]);
    }

    #[test]
    fn demo_job_rejects_zero_steps() {
        let err = run_demo_job(0, Box::new(MockCallback::default())).unwrap_err();
        assert!(matches!(err, EngineError::InvalidInput { .. }));
    }

    #[test]
    fn fail_demo_returns_internal_error() {
        let err = fail_demo().unwrap_err();
        assert!(matches!(err, EngineError::Internal { .. }));
    }

    #[test]
    fn checksum_rejects_partial_pixels() {
        assert!(rgba_checksum(vec![1, 2, 3]).is_err());
        assert_eq!(rgba_checksum(vec![10, 0, 0, 255]).unwrap(), 10 + 255 * 4);
    }
}
