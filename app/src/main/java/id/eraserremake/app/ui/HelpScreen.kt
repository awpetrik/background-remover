package id.eraserremake.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cara Pakai", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("Mulai")
        Body(
            "Ketuk Pilih Foto di Beranda. Setiap foto menjadi proyek yang tersimpan " +
                "otomatis, jadi pekerjaan bisa dilanjutkan kapan saja dari Beranda.",
        )

        SectionTitle("Gestur di kanvas")
        Body(
            "Satu jari menggambar atau mengetuk sesuai alat yang aktif. Dua jari " +
                "menggeser dan memperbesar kanvas dari alat apa pun.",
        )

        SectionTitle("Alat AI")
        Body(
            "Hapus Background menjalankan model saliency pada foto. Balik Hasil " +
                "menukar wilayah yang dipertahankan. Rapikan Tepi (0 sampai 5) " +
                "menghaluskan potongan; naikkan bila tepi terlihat bergerigi.",
        )

        SectionTitle("Alat Objek")
        Body(
            "Mode Deteksi mencari objek pada foto; pilih satu atau beberapa objek, " +
                "lalu Simpan Terpilih untuk mempertahankan mereka saja atau Hapus " +
                "Terpilih untuk menghilangkan mereka. Mode Ketuk memakai titik: " +
                "ketuk objek dengan Titik +, tambahkan Titik - pada bagian yang " +
                "tidak diinginkan, lalu terapkan. Kedua mode butuh model yang " +
                "diinstall dari halaman Model AI.",
        )

        SectionTitle("Alat Warna dan Magic")
        Body(
            "Ketuk warna pada foto. Warna menghapus semua piksel dengan warna serupa " +
                "di seluruh foto; Magic hanya menghapus wilayah serupa yang menyambung " +
                "dari titik ketukan. Atur Toleransi sebelum mengetuk, dan Jangkauan " +
                "pada Magic untuk membatasi radius.",
        )

        SectionTitle("Kuas dan Pulihkan")
        Body(
            "Kuas menghapus dengan goresan; Pulihkan mengembalikan piksel foto asli. " +
                "Atur Ukuran kuas dan Offset kursor agar ujung jari tidak menutupi " +
                "area yang sedang dikerjakan.",
        )

        SectionTitle("Potong, Zoom, dan Simpan")
        Body(
            "Potong mengubah bingkai foto dengan rasio siap pakai. Zoom menyediakan " +
                "Pas Layar dan 100%. Tombol Simpan mengekspor PNG transparan atau JPG " +
                "dengan latar putih/hitam ke galeri (folder EraserRemake), atau " +
                "membagikan hasilnya langsung.",
        )

        SectionTitle("Model AI tambahan")
        Body(
            "Halaman Model AI memasang model yang lebih besar dari katalog. Setiap " +
                "unduhan diverifikasi dengan SHA-256 sebelum dipakai. Model bawaan " +
                "U2NetP cukup untuk kebanyakan foto; model Objek dan SAM hanya perlu " +
                "diinstall bila alat Objek dipakai.",
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
