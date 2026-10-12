//! Helper ubah ukuran gambar (bilinear) untuk jalur inference dan mask.

/// Resize RGBA bilinear dari (w,h) ke (dw,dh). Koordinat memakai konvensi
/// align-corners=false seperti kebanyakan pipeline pra-proses model.
pub fn resize_bilinear_rgba(src: &[u8], w: u32, h: u32, dw: u32, dh: u32) -> Vec<u8> {
    let mut out = vec![0u8; (dw * dh * 4) as usize];
    resize_bilinear_channels(src, w, h, 4, dw, dh, &mut out);
    out
}

/// Resize RGB (3 kanal) bilinear.
pub fn resize_bilinear_rgb(src: &[u8], w: u32, h: u32, dw: u32, dh: u32) -> Vec<u8> {
    let mut out = vec![0u8; (dw * dh * 3) as usize];
    resize_bilinear_channels(src, w, h, 3, dw, dh, &mut out);
    out
}

/// Resize bidang f32 satu kanal bilinear.
pub fn resize_bilinear_f32(src: &[f32], w: u32, h: u32, dw: u32, dh: u32) -> Vec<f32> {
    if w == dw && h == dh {
        return src.to_vec();
    }
    let mut out = vec![0f32; (dw * dh) as usize];
    for dy in 0..dh {
        let sy = sample_pos(dy, h, dh);
        let y0 = sy.floor() as u32;
        let y1 = (y0 + 1).min(h - 1);
        let fy = sy - y0 as f32;
        for dx in 0..dw {
            let sx = sample_pos(dx, w, dw);
            let x0 = sx.floor() as u32;
            let x1 = (x0 + 1).min(w - 1);
            let fx = sx - x0 as f32;
            let i00 = src[(y0 * w + x0) as usize];
            let i10 = src[(y0 * w + x1) as usize];
            let i01 = src[(y1 * w + x0) as usize];
            let i11 = src[(y1 * w + x1) as usize];
            let top = i00 + (i10 - i00) * fx;
            let bottom = i01 + (i11 - i01) * fx;
            out[(dy * dw + dx) as usize] = top + (bottom - top) * fy;
        }
    }
    out
}

/// Box blur f32 satu kanal memakai integral image (O(1) per piksel).
pub fn box_blur_f32(src: &[f32], w: u32, h: u32, radius: u32) -> Vec<f32> {
    let (w, h) = (w as usize, h as usize);
    if radius == 0 || w == 0 || h == 0 {
        return src.to_vec();
    }
    let r = radius as usize;
    // Integral image dengan padding 1.
    let iw = w + 1;
    let mut integral = vec![0f64; iw * (h + 1)];
    for y in 0..h {
        let mut row_sum = 0f64;
        for x in 0..w {
            row_sum += f64::from(src[y * w + x]);
            integral[(y + 1) * iw + (x + 1)] = integral[y * iw + (x + 1)] + row_sum;
        }
    }
    let mut out = vec![0f32; w * h];
    for y in 0..h {
        let y0 = y.saturating_sub(r);
        let y1 = (y + r).min(h - 1);
        for x in 0..w {
            let x0 = x.saturating_sub(r);
            let x1 = (x + r).min(w - 1);
            let sum = integral[(y1 + 1) * iw + (x1 + 1)]
                - integral[y0 * iw + (x1 + 1)]
                - integral[(y1 + 1) * iw + x0]
                + integral[y0 * iw + x0];
            let count = ((x1 - x0 + 1) * (y1 - y0 + 1)) as f64;
            out[y * w + x] = (sum / count) as f32;
        }
    }
    out
}

/// Guided filter (He et al.): haluskan `mask` sambil menjaga tepi yang ada di
/// gambar panduan (luminansi RGB). Dipakai merapikan mask AI/SAM/objek agar
/// tepinya mengikuti tepi foto, bukan tepi mask yang kasar.
pub fn guided_filter(
    guide_rgb: &[u8],
    mask: &[f32],
    w: u32,
    h: u32,
    radius: u32,
    eps: f32,
) -> Vec<f32> {
    let n = (w * h) as usize;
    if n == 0 || guide_rgb.len() < n * 3 || mask.len() < n {
        return mask.to_vec();
    }
    let guide: Vec<f32> = (0..n)
        .map(|i| {
            (0.299 * f32::from(guide_rgb[i * 3])
                + 0.587 * f32::from(guide_rgb[i * 3 + 1])
                + 0.114 * f32::from(guide_rgb[i * 3 + 2]))
                / 255.0
        })
        .collect();
    let mean_i = box_blur_f32(&guide, w, h, radius);
    let mean_p = box_blur_f32(mask, w, h, radius);
    let corr_i: Vec<f32> = guide.iter().zip(&guide).map(|(a, b)| a * b).collect();
    let corr_ip: Vec<f32> = guide.iter().zip(mask).map(|(a, b)| a * b).collect();
    let mean_ii = box_blur_f32(&corr_i, w, h, radius);
    let mean_ip = box_blur_f32(&corr_ip, w, h, radius);
    let mut a = vec![0f32; n];
    let mut b = vec![0f32; n];
    for i in 0..n {
        let var_i = mean_ii[i] - mean_i[i] * mean_i[i];
        let cov_ip = mean_ip[i] - mean_i[i] * mean_p[i];
        a[i] = cov_ip / (var_i + eps);
        b[i] = mean_p[i] - a[i] * mean_i[i];
    }
    let mean_a = box_blur_f32(&a, w, h, radius);
    let mean_b = box_blur_f32(&b, w, h, radius);
    (0..n)
        .map(|i| (mean_a[i] * guide[i] + mean_b[i]).clamp(0.0, 1.0))
        .collect()
}

fn sample_pos(d: u32, src_len: u32, dst_len: u32) -> f32 {
    if dst_len <= 1 || src_len <= 1 {
        return 0.0;
    }
    let scale = src_len as f32 / dst_len as f32;
    ((d as f32 + 0.5) * scale - 0.5).clamp(0.0, (src_len - 1) as f32)
}

fn resize_bilinear_channels(
    src: &[u8],
    w: u32,
    h: u32,
    channels: u32,
    dw: u32,
    dh: u32,
    out: &mut [u8],
) {
    if w == dw && h == dh {
        out.copy_from_slice(src);
        return;
    }
    let c = channels as usize;
    for dy in 0..dh {
        let sy = sample_pos(dy, h, dh);
        let y0 = sy.floor() as u32;
        let y1 = (y0 + 1).min(h - 1);
        let fy = sy - y0 as f32;
        for dx in 0..dw {
            let sx = sample_pos(dx, w, dw);
            let x0 = sx.floor() as u32;
            let x1 = (x0 + 1).min(w - 1);
            let fx = sx - x0 as f32;
            let base_out = ((dy * dw + dx) as usize) * c;
            for ch in 0..c {
                let i00 = src[((y0 * w + x0) as usize) * c + ch] as f32;
                let i10 = src[((y0 * w + x1) as usize) * c + ch] as f32;
                let i01 = src[((y1 * w + x0) as usize) * c + ch] as f32;
                let i11 = src[((y1 * w + x1) as usize) * c + ch] as f32;
                let top = i00 + (i10 - i00) * fx;
                let bottom = i01 + (i11 - i01) * fx;
                out[base_out + ch] = (top + (bottom - top) * fy).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resize_same_size_is_identity() {
        let src: Vec<u8> = (0..16).collect();
        let out = resize_bilinear_rgba(&src, 2, 2, 2, 2);
        assert_eq!(out, src);
    }

    #[test]
    fn resize_f32_upscale_interpolates() {
        // Bidang 2x1: [0, 100] -> lebar 4 harus menanjak halus.
        let out = resize_bilinear_f32(&[0.0, 100.0], 2, 1, 4, 1);
        assert_eq!(out.len(), 4);
        assert!(out[0] < out[1] && out[1] < out[2] && out[2] < out[3]);
        assert!(out[3] > 80.0);
    }

    #[test]
    fn box_blur_f32_smooths() {
        let src = vec![0.0f32, 0.0, 100.0, 0.0, 0.0];
        let out = box_blur_f32(&src, 5, 1, 1);
        assert!(out[2] < 100.0 && out[2] > 30.0);
        assert!(out[1] > 0.0, "tetangga impuls ikut terangkat");
        assert_eq!(out[0], 0.0, "dua piksel dari impuls tidak tersentuh radius 1");
    }

    #[test]
    fn guided_filter_keeps_constant_mask() {
        let guide = vec![128u8; 8 * 8 * 3];
        let mask = vec![0.7f32; 64];
        let out = guided_filter(&guide, &mask, 8, 8, 2, 1e-4);
        assert!(out.iter().all(|&v| (v - 0.7).abs() < 0.01));
    }

    #[test]
    fn guided_filter_preserves_guide_edge_better_than_blur() {
        // Panduan: separuh kiri hitam, kanan putih. Mask: langkah sejajar.
        let (w, h) = (32u32, 8u32);
        let mut guide = vec![0u8; (w * h * 3) as usize];
        let mut mask = vec![0f32; (w * h) as usize];
        for y in 0..h {
            for x in 0..w {
                let i = (y * w + x) as usize;
                if x >= 16 {
                    guide[i * 3] = 255;
                    guide[i * 3 + 1] = 255;
                    guide[i * 3 + 2] = 255;
                    mask[i] = 1.0;
                }
            }
        }
        let guided = guided_filter(&guide, &mask, w, h, 4, 1e-4);
        let blurred = box_blur_f32(&mask, w, h, 4);
        let mid = (4 * w + 15) as usize; // piksel terakhir sisi kiri
        assert!(
            guided[mid] < blurred[mid],
            "tepi kiri harus lebih tajam: guided {} vs blur {}",
            guided[mid],
            blurred[mid]
        );
        let mid_r = (4 * w + 16) as usize;
        assert!(guided[mid_r] > blurred[mid_r]);
    }

    #[test]
    fn resize_rgba_downscale_averages() {
        // 2x2 semua nilai berbeda; turun ke 1x1 mengambil wilayah tengah.
        let mut src = vec![0u8; 16];
        for i in 0..4 {
            src[i * 4] = (i * 40) as u8;
            src[i * 4 + 3] = 255;
        }
        let out = resize_bilinear_rgba(&src, 2, 2, 1, 1);
        assert_eq!(out.len(), 4);
        assert_eq!(out[3], 255);
    }
}
