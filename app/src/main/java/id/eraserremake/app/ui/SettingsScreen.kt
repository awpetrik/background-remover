package id.eraserremake.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.eraserremake.app.BuildConfig
import id.eraserremake.app.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    useNnapi: Boolean,
    onUseNnapiChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pengaturan", style = MaterialTheme.typography.headlineSmall)

        Text("Tema", style = MaterialTheme.typography.titleMedium)
        listOf(
            ThemeMode.System to "Ikuti sistem",
            ThemeMode.Light to "Terang",
            ThemeMode.Dark to "Gelap",
        ).forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = themeMode == mode) { onThemeModeChange(mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
                Text(label)
            }
        }

        Text("Performa AI", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("NNAPI (eksperimental)")
                Text(
                    "Memakai akselerator perangkat untuk inference. Hasil bisa berbeda " +
                        "antar perangkat dan model besar bisa gagal dimuat; matikan bila " +
                        "hasil AI terasa janggal. Berlaku untuk model yang dimuat setelah " +
                        "diubah, mulai ulang aplikasi untuk efek penuh.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = useNnapi, onCheckedChange = onUseNnapiChange)
        }

        Text("Tentang", style = MaterialTheme.typography.titleMedium)
        Text(
            "Versi ${BuildConfig.VERSION_NAME}. Semua pemrosesan foto terjadi di " +
                "perangkat; tidak ada akun, iklan, atau pengiriman data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Model bawaan U2NetP dari proyek U-2-Net (Apache License 2.0). " +
                "Mesin inference ONNX Runtime (MIT License). Model tambahan yang " +
                "diinstall dari Manajer Model tunduk pada lisensi masing-masing.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
