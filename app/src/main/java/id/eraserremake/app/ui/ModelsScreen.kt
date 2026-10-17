package id.eraserremake.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import id.eraserremake.app.data.CatalogModel
import id.eraserremake.app.data.InstalledModel
import id.eraserremake.app.data.ModelDownloadWorker
import id.eraserremake.app.data.ModelStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun kindLabel(kind: String): String = when (kind) {
    "SalientMask" -> "Hapus background"
    "InstanceSeg" -> "Deteksi objek"
    "PointSam" -> "Pilih objek dengan ketukan"
    "Selfie" -> "Selfie"
    else -> kind
}

private fun sizeLabel(bytes: Long): String =
    if (bytes > 0) "%.1f MB".format(bytes / 1_000_000.0) else "-"

/** Manajer Model (design doc 5.3): model terpasang dan katalog unduhan. */
@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val store = remember { ModelStore(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { store.ensureBundled() }

    val installed by store.observeInstalled().collectAsState(initial = emptyList())
    var catalog by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var catalogNote by remember {
        mutableStateOf(
            if (store.catalogIsCached()) {
                "Katalog dari unduhan terakhir"
            } else {
                "Katalog bawaan aplikasi"
            },
        )
    }
    LaunchedEffect(Unit) { catalog = store.loadCatalog() }

    val prefs = remember {
        context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
    }
    var catalogUrl by remember { mutableStateOf(prefs.getString("catalog_url", "") ?: "") }
    var refreshing by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<InstalledModel?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Model AI", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Text("Terpasang", style = MaterialTheme.typography.titleMedium)
        }
        if (installed.isEmpty()) {
            item {
                Text(
                    "Menyiapkan model bawaan...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(installed, key = { "terpasang-" + it.id }) { model ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(model.name, style = MaterialTheme.typography.titleSmall)
                        if (model.isDefault) {
                            Text("Default", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        "${kindLabel(model.kind)} · ${sizeLabel(model.sizeBytes)} · versi ${model.version}" +
                            if (model.bundled) " · bawaan" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!model.isDefault) {
                            OutlinedButton(onClick = {
                                scope.launch { store.setDefault(model) }
                            }) { Text("Jadikan Default") }
                        }
                        if (!model.bundled) {
                            TextButton(onClick = { deleteTarget = model }) { Text("Hapus") }
                        }
                    }
                }
            }
        }

        item {
            Text("Katalog", style = MaterialTheme.typography.titleMedium)
            Text(catalogNote, style = MaterialTheme.typography.bodySmall)
        }
        items(catalog, key = { "katalog-" + it.id }) { entry ->
            val isInstalled = installed.any { it.id == entry.id }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${kindLabel(entry.kind)} · ${sizeLabel(entry.sizeBytes)} · versi ${entry.version}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (entry.notes.isNotEmpty()) {
                        Text(entry.notes, style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        entry.isBundledAsset -> Text(
                            if (isInstalled) {
                                "Sudah terpasang sebagai model bawaan."
                            } else {
                                "Model bawaan aplikasi."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        isInstalled -> Text(
                            "Sudah terpasang.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> DownloadControls(entry, store)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sumber katalog", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Katalog adalah file JSON di hosting statis. Isi URL-nya untuk " +
                        "memuat daftar model yang bisa di-install.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = catalogUrl,
                    onValueChange = { catalogUrl = it },
                    label = { Text("URL katalog (JSON)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = catalogUrl.isNotBlank() && !refreshing,
                    onClick = {
                        refreshing = true
                        prefs.edit().putString("catalog_url", catalogUrl).apply()
                        scope.launch {
                            store.refreshCatalog(catalogUrl)
                                .onSuccess {
                                    catalog = it
                                    catalogNote = "Katalog dari unduhan terakhir"
                                }
                                .onFailure {
                                    catalogNote = "Gagal memuat katalog. Periksa URL dan koneksi."
                                }
                            refreshing = false
                        }
                    },
                ) { Text(if (refreshing) "Memuat..." else "Muat Katalog") }
            }
        }
    }

    deleteTarget?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus model?") },
            text = { Text("Model \"${model.name}\" akan dihapus dari perangkat.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { store.deleteInstalled(model) }
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Batal") }
            },
        )
    }
}

/** Tombol install + progres unduhan untuk satu entri katalog. */
@Composable
private fun DownloadControls(entry: CatalogModel, store: ModelStore) {
    val context = LocalContext.current
    val workInfo by produceState<WorkInfo?>(initialValue = null, entry.id) {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelStore.workName(entry.id))
            .collect { list -> value = list.firstOrNull() }
    }
    val scope = rememberCoroutineScope()
    when (workInfo?.state) {
        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
            val percent = workInfo?.progress?.getInt(ModelDownloadWorker.KEY_PROGRESS, 0) ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Mengunduh $percent%", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch { store.cancelDownload(entry.id) }
                    }) { Text("Batalkan") }
                }
            }
        }
        WorkInfo.State.FAILED -> {
            val error = workInfo?.outputData?.getString(ModelDownloadWorker.KEY_ERROR)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    error ?: "Unduhan gagal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { store.enqueueDownload(entry) }) { Text("Coba Lagi") }
            }
        }
        else -> {
            Button(onClick = { store.enqueueDownload(entry) }) { Text("Install") }
        }
    }
}
