package id.eraserremake.app.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.eraserremake.app.data.ExportFormat
import id.eraserremake.app.data.Exporter
import id.eraserremake.app.data.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom sheet ekspor (design doc 5.4): format, latar JPG, ukuran, lalu simpan atau bagikan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    session: EditorSession,
    store: ProjectStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val snackbar = remember { SnackbarHostState() }

    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var bgWhite by remember { mutableStateOf(true) }
    var small by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Simpan & Bagikan", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

            Text("Format", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = format == ExportFormat.PNG,
                    onClick = { format = ExportFormat.PNG },
                    label = { Text("PNG transparan") },
                )
                FilterChip(
                    selected = format == ExportFormat.JPG,
                    onClick = { format = ExportFormat.JPG },
                    label = { Text("JPG") },
                )
            }

            if (format == ExportFormat.JPG) {
                Text("Warna latar", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = bgWhite,
                        onClick = { bgWhite = true },
                        label = { Text("Putih") },
                    )
                    FilterChip(
                        selected = !bgWhite,
                        onClick = { bgWhite = false },
                        label = { Text("Hitam") },
                    )
                }
            }

            Text("Ukuran", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !small,
                    onClick = { small = false },
                    label = { Text("Asli") },
                )
                FilterChip(
                    selected = small,
                    onClick = { small = true },
                    label = { Text("Kecil (1024px)") },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                val full = session.fullBitmap()
                                val rendered = withContext(Dispatchers.Default) {
                                    Exporter.render(
                                        full,
                                        format,
                                        if (bgWhite) AndroidColor.WHITE else AndroidColor.BLACK,
                                        small,
                                    )
                                }
                                Exporter.saveToGallery(context, rendered, format)
                                    ?: error("simpan gagal")
                            }
                            busy = false
                            result.onSuccess {
                                // Autosave juga ikut titik ini.
                                store.saveWork(session.project, session.engine)
                                snackbar.showSnackbar("Tersimpan di ${Exporter.GALLERY_DIR}")
                                onDismiss()
                            }.onFailure {
                                snackbar.showSnackbar("Gagal menyimpan. Coba lagi.")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text("Simpan ke Galeri")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching {
                                val full = session.fullBitmap()
                                val rendered = withContext(Dispatchers.Default) {
                                    Exporter.render(
                                        full,
                                        format,
                                        if (bgWhite) AndroidColor.WHITE else AndroidColor.BLACK,
                                        small,
                                    )
                                }
                                Exporter.shareIntent(context, rendered, format)
                            }
                            busy = false
                            result.onSuccess { intent ->
                                context.startActivity(
                                    Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                onDismiss()
                            }.onFailure {
                                snackbar.showSnackbar("Gagal menyiapkan file untuk dibagikan.")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Bagikan") }
            }
            SnackbarHost(snackbar)
        }
    }
}
