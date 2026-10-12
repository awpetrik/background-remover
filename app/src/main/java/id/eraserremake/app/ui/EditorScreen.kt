package id.eraserremake.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import id.eraserremake.app.data.AppDb
import id.eraserremake.app.data.Project
import id.eraserremake.app.data.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uniffi.eraser_core.CropRect
import uniffi.eraser_core.DetectedObject
import uniffi.eraser_core.ImagePoint
import kotlin.math.min
import kotlin.math.roundToInt

private const val CANVAS_BG = 0xFF2B2B2B

@Composable
fun EditorScreen(projectId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProjectStore(context) }
    var project by remember { mutableStateOf<Project?>(null) }
    var session by remember { mutableStateOf<EditorSession?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val latestSession by rememberUpdatedState(session)
    val latestProject by rememberUpdatedState(project)

    LaunchedEffect(projectId) {
        runCatching {
            val dao = AppDb.get(context).projectDao()
            val loaded = dao.getById(projectId) ?: error("Proyek tidak ditemukan")
            project = loaded
            val bitmap = store.loadEditBitmap(loaded)
            session = EditorSession.open(loaded, bitmap)
        }.onFailure { loadError = it.message ?: "Gagal membuka proyek" }
    }

    // Autosave: keadaan terakhir sesi disimpan lalu engine ditutup saat layar ditinggalkan.
    DisposableEffect(Unit) {
        onDispose {
            val s = latestSession
            val p = latestProject
            if (s != null && p != null) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    store.saveWork(p, s.engine)
                    s.close()
                }
            }
        }
    }

    BackHandler { onBack() }

    when {
        loadError != null -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Proyek tidak bisa dibuka", style = MaterialTheme.typography.titleLarge)
            Text(loadError!!, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack) { Text("Kembali") }
        }

        session == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> EditorContent(
            session = session!!,
            store = store,
            onBack = onBack,
            onSessionReplaced = { newProject, newSession ->
                session?.close()
                project = newProject
                session = newSession
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    session: EditorSession,
    store: ProjectStore,
    onBack: () -> Unit,
    onSessionReplaced: (Project, EditorSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var tool by remember { mutableStateOf(EditorTool.Kuas) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var imgW by remember { mutableIntStateOf(session.imageWidth()) }
    var imgH by remember { mutableIntStateOf(session.imageHeight()) }
    var canUndo by remember { mutableStateOf(session.canUndo()) }
    var canRedo by remember { mutableStateOf(session.canRedo()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf<Offset?>(null) }

    var brushRadius by remember { mutableFloatStateOf(40f) }
    var brushOffsetDp by remember { mutableFloatStateOf(36f) }
    var brushParam by remember { mutableStateOf("Ukuran") } // "Ukuran" | "Offset"

    val context = LocalContext.current
    val modelStore = remember { id.eraserremake.app.data.ModelStore(context) }
    var salientModels by remember {
        mutableStateOf<List<id.eraserremake.app.data.InstalledModel>>(emptyList())
    }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiProgress by remember { mutableStateOf<Int?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiParam by remember { mutableStateOf("Model") } // "Model" | "Rapikan"
    var refineLevel by remember { mutableFloatStateOf(2f) }
    var warnaTolerance by remember { mutableFloatStateOf(32f) }
    var magicParam by remember { mutableStateOf("Toleransi") } // "Toleransi" | "Jangkauan"
    var magicTolerance by remember { mutableFloatStateOf(32f) }
    var magicRadius by remember { mutableFloatStateOf(600f) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    var instanceModels by remember {
        mutableStateOf<List<id.eraserremake.app.data.InstalledModel>>(emptyList())
    }
    var samModels by remember {
        mutableStateOf<List<id.eraserremake.app.data.InstalledModel>>(emptyList())
    }
    var objekMode by remember { mutableStateOf("Deteksi") } // "Deteksi" | "Ketuk"
    var selectedInstanceId by remember { mutableStateOf<String?>(null) }
    var selectedSamId by remember { mutableStateOf<String?>(null) }
    var detections by remember { mutableStateOf<List<DetectedObject>>(emptyList()) }
    var selectedObjects by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var objekBusy by remember { mutableStateOf(false) }
    var objekProgress by remember { mutableStateOf<Int?>(null) }
    var objekError by remember { mutableStateOf<String?>(null) }
    var samActiveUi by remember { mutableStateOf(false) }
    var samPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var samPoints by remember { mutableStateOf<List<Triple<Float, Float, Boolean>>>(emptyList()) }
    var samPositive by remember { mutableStateOf(true) }
    var instanceMenuOpen by remember { mutableStateOf(false) }
    var samMenuOpen by remember { mutableStateOf(false) }

    var cropRect by remember { mutableStateOf<Rect?>(null) }
    var cropRatio by remember { mutableStateOf<Float?>(null) } // width/height, null = Bebas
    var exportOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val strokeChannel = remember(session) { Channel<StrokeEvent>(Channel.UNLIMITED) }

    fun fitScale(): Float {
        if (canvasSize.width == 0 || imgW == 0) return 1f
        return min(
            canvasSize.width.toFloat() / imgW,
            canvasSize.height.toFloat() / imgH,
        )
    }

    fun dispScale(): Float = fitScale() * zoom

    fun imageTopLeft(): Offset {
        val ds = dispScale()
        return Offset(
            (canvasSize.width - imgW * ds) / 2f + pan.x,
            (canvasSize.height - imgH * ds) / 2f + pan.y,
        )
    }

    fun screenToImage(p: Offset): ImagePoint {
        val ds = dispScale()
        val tl = imageTopLeft()
        return ImagePoint((p.x - tl.x) / ds, (p.y - tl.y) / ds)
    }

    suspend fun refreshPreview() {
        preview = session.previewBitmap(1600)
        imgW = session.imageWidth()
        imgH = session.imageHeight()
        canUndo = session.canUndo()
        canRedo = session.canRedo()
    }

    LaunchedEffect(session) {
        refreshPreview()
        var lastRefresh = 0L
        for (event in strokeChannel) {
            when (event) {
                StrokeEvent.Begin -> session.beginStroke()
                is StrokeEvent.Segment -> {
                    session.stroke(event.points, event.radius, event.mode)
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh > 60) {
                        refreshPreview()
                        lastRefresh = now
                    }
                }
                StrokeEvent.End -> {
                    session.endStroke()
                    refreshPreview()
                    lastRefresh = System.currentTimeMillis()
                }
            }
        }
    }

    LaunchedEffect(Unit) { modelStore.ensureBundled() }
    LaunchedEffect(Unit) {
        modelStore.observeSalient().collect { models ->
            salientModels = models
            if (selectedModelId == null || models.none { it.id == selectedModelId }) {
                selectedModelId = models.firstOrNull { it.isDefault }?.id
                    ?: models.firstOrNull()?.id
            }
        }
    }

    LaunchedEffect(Unit) {
        modelStore.observeInstance().collect { models ->
            instanceModels = models
            if (selectedInstanceId == null || models.none { it.id == selectedInstanceId }) {
                selectedInstanceId = models.firstOrNull { it.isDefault }?.id
                    ?: models.firstOrNull()?.id
            }
        }
    }
    LaunchedEffect(Unit) {
        modelStore.observeSam().collect { models ->
            samModels = models
            if (selectedSamId == null || models.none { it.id == selectedSamId }) {
                selectedSamId = models.firstOrNull { it.isDefault }?.id
                    ?: models.firstOrNull()?.id
            }
        }
    }

    val tapChannel = remember(session) { Channel<ImagePoint>(Channel.UNLIMITED) }
    val toolRef = rememberUpdatedState(tool)
    val warnaTolRef = rememberUpdatedState(warnaTolerance)
    val magicTolRef = rememberUpdatedState(magicTolerance)
    val magicRadRef = rememberUpdatedState(magicRadius)
    val samActiveRef = rememberUpdatedState(samActiveUi)
    val samPositiveRef = rememberUpdatedState(samPositive)
    LaunchedEffect(session) {
        for (point in tapChannel) {
            val x = point.x.roundToInt().coerceIn(0, session.imageWidth() - 1)
            val y = point.y.roundToInt().coerceIn(0, session.imageHeight() - 1)
            runCatching {
                when (toolRef.value) {
                    EditorTool.Warna -> session.autoColor(x, y, warnaTolRef.value.roundToInt())
                    EditorTool.Magic -> session.magic(
                        x,
                        y,
                        magicTolRef.value.roundToInt(),
                        magicRadRef.value.roundToInt(),
                    )
                    EditorTool.Objek -> if (samActiveRef.value) {
                        val rt = id.eraserremake.app.data.ModelRuntimeHolder.get(context)
                        samPreviewBmp = session.samAddPoint(
                            rt, point.x, point.y, samPositiveRef.value,
                        )
                        samPoints = samPoints +
                            Triple(point.x, point.y, samPositiveRef.value)
                    }
                    else -> Unit
                }
            }.onSuccess { refreshPreview() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Editor") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        scope.launch { if (session.undo()) refreshPreview() }
                    },
                    enabled = canUndo,
                ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Urungkan") }
                IconButton(
                    onClick = {
                        scope.launch { if (session.redo()) refreshPreview() }
                    },
                    enabled = canRedo,
                ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Ulangi") }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu editor")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Ganti Foto") },
                            onClick = {
                                menuOpen = false
                                onBack()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Atur Ulang Edit") },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching {
                                        val cleared = store.clearWork(session.project)
                                        val bmp = store.loadOriginalBitmap(cleared)
                                        val newSession = EditorSession.open(cleared, bmp)
                                        zoom = 1f
                                        pan = Offset.Zero
                                        cropRect = null
                                        tool = EditorTool.Kuas
                                        onSessionReplaced(cleared, newSession)
                                    }
                                }
                            },
                        )
                    }
                }
                TextButton(onClick = { exportOpen = true }) { Text("Simpan") }
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(CANVAS_BG))
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .editorGestures(
                    tool = { tool },
                    isCropTool = { tool == EditorTool.Potong },
                    screenToImage = { screenToImage(it) },
                    brushRadius = { brushRadius },
                    brushOffsetPx = {
                        if (tool == EditorTool.Kuas || tool == EditorTool.Pulihkan) {
                            with(density) { brushOffsetDp.dp.toPx() }
                        } else {
                            0f
                        }
                    },
                    onStrokeEvent = { strokeChannel.trySend(it) },
                    onTapPoint = { tapChannel.trySend(it) },
                    onTransform = { deltaPan, zoomFactor ->
                        pan += deltaPan
                        zoom = (zoom * zoomFactor).coerceIn(0.2f, 16f)
                    },
                    onCursor = { cursor = it },
                    cropRect = { cropRect },
                    onCropRect = { cropRect = it },
                    cropRatio = { cropRatio },
                    imageSize = { Size(imgW.toFloat(), imgH.toFloat()) },
                    dispScale = { dispScale() },
                    imageTopLeft = { imageTopLeft() },
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ds = dispScale()
                val tl = imageTopLeft()
                val imgRect = Rect(tl, Size(imgW * ds, imgH * ds))
                val cell = 12.dp.toPx()
                val light = Color(0xFFFFFFFF)
                val dark = Color(0xFFBDBDBD)
                var row = 0
                var y = imgRect.top
                while (y < imgRect.bottom) {
                    var col = row % 2
                    var x = imgRect.left
                    while (x < imgRect.right) {
                        drawRect(
                            color = if (col % 2 == 0) light else dark,
                            topLeft = Offset(x, y),
                            size = Size(
                                min(cell, imgRect.right - x),
                                min(cell, imgRect.bottom - y),
                            ),
                        )
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
                preview?.let { bmp ->
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = androidx.compose.ui.unit.IntOffset(
                            imgRect.left.roundToInt(),
                            imgRect.top.roundToInt(),
                        ),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            imgRect.width.roundToInt().coerceAtLeast(1),
                            imgRect.height.roundToInt().coerceAtLeast(1),
                        ),
                    )
                }
                // Overlay Objek: kotak deteksi, pratinjau mask SAM, titik prompt.
                if (tool == EditorTool.Objek) {
                    for (det in detections) {
                        val selected = det.index.toInt() in selectedObjects
                        drawRect(
                            color = if (selected) Color(0xFF10B981) else Color.White,
                            topLeft = Offset(
                                imgRect.left + det.x.toFloat() * ds,
                                imgRect.top + det.y.toFloat() * ds,
                            ),
                            size = Size(
                                det.width.toFloat() * ds,
                                det.height.toFloat() * ds,
                            ),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    samPreviewBmp?.let { mask ->
                        drawImage(
                            image = mask.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                imgRect.left.roundToInt(),
                                imgRect.top.roundToInt(),
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                imgRect.width.roundToInt().coerceAtLeast(1),
                                imgRect.height.roundToInt().coerceAtLeast(1),
                            ),
                        )
                    }
                    for ((px, py, positive) in samPoints) {
                        val center = Offset(
                            imgRect.left + px * ds,
                            imgRect.top + py * ds,
                        )
                        val fill = if (positive) Color(0xFF10B981) else Color(0xFFEF4444)
                        drawCircle(color = Color.White, radius = 9.dp.toPx(), center = center)
                        drawCircle(color = fill, radius = 7.dp.toPx(), center = center)
                        val arm = 4.dp.toPx()
                        drawLine(
                            color = Color.White,
                            start = Offset(center.x - arm, center.y),
                            end = Offset(center.x + arm, center.y),
                            strokeWidth = 2.dp.toPx(),
                        )
                        if (positive) {
                            drawLine(
                                color = Color.White,
                                start = Offset(center.x, center.y - arm),
                                end = Offset(center.x, center.y + arm),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    }
                }
                if ((tool == EditorTool.Kuas || tool == EditorTool.Pulihkan) && cursor != null) {
                    val r = brushRadius * ds
                    drawCircle(
                        color = Color.White,
                        radius = r,
                        center = cursor!!,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = r,
                        center = cursor!!,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = cursor!!)
                }
                // Overlay crop.
                if (tool == EditorTool.Potong) {
                    val rect = cropRect ?: Rect(0f, 0f, imgW.toFloat(), imgH.toFloat())
                    val tlImg = Offset(
                        imgRect.left + rect.left * ds,
                        imgRect.top + rect.top * ds,
                    )
                    val cropScreen = Rect(
                        tlImg,
                        Size(rect.width * ds, rect.height * ds),
                    )
                    val dim = Color(0x99000000)
                    drawRect(dim, Offset(0f, 0f), Size(size.width, cropScreen.top))
                    drawRect(
                        dim,
                        Offset(0f, cropScreen.bottom),
                        Size(size.width, size.height - cropScreen.bottom),
                    )
                    drawRect(
                        dim,
                        Offset(0f, cropScreen.top),
                        Size(cropScreen.left, cropScreen.height),
                    )
                    drawRect(
                        dim,
                        Offset(cropScreen.right, cropScreen.top),
                        Size(size.width - cropScreen.right, cropScreen.height),
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = cropScreen.topLeft,
                        size = cropScreen.size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    listOf(
                        cropScreen.topLeft, cropScreen.topRight,
                        cropScreen.bottomLeft, cropScreen.bottomRight,
                    ).forEach { corner ->
                        drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
                        drawCircle(
                            Color.Black,
                            radius = 7.dp.toPx(),
                            center = corner,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }
        }

        // Panel konteks, tinggi tetap agar kanvas tidak bergeser.
        Surface(tonalElevation = 2.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                when (tool) {
                    EditorTool.Kuas, EditorTool.Pulihkan -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = brushParam == "Ukuran",
                            onClick = { brushParam = "Ukuran" },
                            label = { Text("Ukuran") },
                        )
                        FilterChip(
                            selected = brushParam == "Offset",
                            onClick = { brushParam = "Offset" },
                            label = { Text("Offset") },
                        )
                        if (brushParam == "Ukuran") {
                            Slider(
                                value = brushRadius,
                                onValueChange = { brushRadius = it },
                                valueRange = 2f..120f,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${brushRadius.roundToInt()} px", modifier = Modifier.width(64.dp))
                        } else {
                            Slider(
                                value = brushOffsetDp,
                                onValueChange = { brushOffsetDp = it },
                                valueRange = 0f..80f,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${brushOffsetDp.roundToInt()} dp", modifier = Modifier.width(64.dp))
                        }
                    }

                    EditorTool.Ai -> when {
                        aiBusy -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (aiProgress ?: 0) / 100f },
                                modifier = Modifier.weight(1f),
                            )
                            Text("AI bekerja ${aiProgress ?: 0}%")
                        }
                        aiError != null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                aiError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { aiError = null }) { Text("Tutup") }
                        }
                        else -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = aiParam == "Model",
                                onClick = { aiParam = "Model" },
                                label = { Text("Model") },
                            )
                            FilterChip(
                                selected = aiParam == "Rapikan",
                                onClick = { aiParam = "Rapikan" },
                                label = { Text("Rapikan Tepi") },
                            )
                            if (aiParam == "Model") {
                                Box {
                                    OutlinedButton(onClick = { modelMenuOpen = true }) {
                                        Text(
                                            salientModels.firstOrNull { it.id == selectedModelId }?.name
                                                ?: "Pilih model",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = modelMenuOpen,
                                        onDismissRequest = { modelMenuOpen = false },
                                    ) {
                                        salientModels.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model.name) },
                                                onClick = {
                                                    selectedModelId = model.id
                                                    modelMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                                Button(
                                    enabled = selectedModelId != null,
                                    onClick = {
                                        val modelId = selectedModelId ?: return@Button
                                        aiBusy = true
                                        aiProgress = 0
                                        scope.launch {
                                            runCatching {
                                                id.eraserremake.app.data.ModelRuntimeHolder
                                                    .registerInstalled(context, modelStore)
                                                val runtime =
                                                    id.eraserremake.app.data.ModelRuntimeHolder
                                                        .get(context)
                                                session.applyAi(runtime, modelId) { p ->
                                                    aiProgress = p
                                                }
                                            }.onSuccess { refreshPreview() }
                                                .onFailure {
                                                    aiError = "AI gagal: ${it.message ?: "kesalahan mesin"}"
                                                }
                                            aiBusy = false
                                            aiProgress = null
                                        }
                                    },
                                ) { Text("Hapus Background") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { session.invertMask() }
                                            .onSuccess { refreshPreview() }
                                    }
                                }) { Text("Balik Hasil") }
                            } else {
                                Slider(
                                    value = refineLevel,
                                    onValueChange = { refineLevel = it },
                                    valueRange = 0f..5f,
                                    steps = 4,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${refineLevel.roundToInt()}", modifier = Modifier.width(32.dp))
                                Button(onClick = {
                                    scope.launch {
                                        runCatching {
                                            session.refineEdges(refineLevel.roundToInt())
                                        }.onSuccess { refreshPreview() }
                                    }
                                }) { Text("Terapkan") }
                            }
                        }
                    }

                    EditorTool.Warna -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Ketuk warna di foto. Toleransi",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Slider(
                            value = warnaTolerance,
                            onValueChange = { warnaTolerance = it },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${warnaTolerance.roundToInt()}", modifier = Modifier.width(40.dp))
                    }

                    EditorTool.Magic -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Ketuk area di foto",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilterChip(
                            selected = magicParam == "Toleransi",
                            onClick = { magicParam = "Toleransi" },
                            label = { Text("Toleransi") },
                        )
                        FilterChip(
                            selected = magicParam == "Jangkauan",
                            onClick = { magicParam = "Jangkauan" },
                            label = { Text("Jangkauan") },
                        )
                        if (magicParam == "Toleransi") {
                            Slider(
                                value = magicTolerance,
                                onValueChange = { magicTolerance = it },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${magicTolerance.roundToInt()}", modifier = Modifier.width(48.dp))
                        } else {
                            Slider(
                                value = magicRadius,
                                onValueChange = { magicRadius = it },
                                valueRange = 50f..2000f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${magicRadius.roundToInt()} px",
                                modifier = Modifier.width(64.dp),
                            )
                        }
                    }

                    EditorTool.Objek -> when {
                        objekBusy -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Objek bekerja ${(objekProgress ?: 0)}%",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (objekProgress ?: 0) / 100f },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        objekError != null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                objekError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { objekError = null }) { Text("Tutup") }
                        }
                        samActiveUi -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = samPositive,
                                onClick = { samPositive = true },
                                label = { Text("Titik +") },
                            )
                            FilterChip(
                                selected = !samPositive,
                                onClick = { samPositive = false },
                                label = { Text("Titik -") },
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val rt = id.eraserremake.app.data
                                                .ModelRuntimeHolder.get(context)
                                            session.samApply(rt, true)
                                        }.onSuccess {
                                            samActiveUi = false
                                            samPreviewBmp = null
                                            samPoints = emptyList()
                                            refreshPreview()
                                        }.onFailure { objekError = it.message ?: "SAM gagal menerapkan" }
                                    }
                                },
                                enabled = samPoints.isNotEmpty(),
                            ) { Text("Simpan Wilayah") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val rt = id.eraserremake.app.data
                                                .ModelRuntimeHolder.get(context)
                                            session.samApply(rt, false)
                                        }.onSuccess {
                                            samActiveUi = false
                                            samPreviewBmp = null
                                            samPoints = emptyList()
                                            refreshPreview()
                                        }.onFailure { objekError = it.message ?: "SAM gagal menerapkan" }
                                    }
                                },
                                enabled = samPoints.isNotEmpty(),
                            ) { Text("Hapus Wilayah") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val rt = id.eraserremake.app.data
                                                .ModelRuntimeHolder.get(context)
                                            samPreviewBmp = session.samUndoPoint(rt)
                                            samPoints = samPoints.dropLast(1)
                                        }.onFailure { objekError = it.message ?: "gagal urungkan titik" }
                                    }
                                },
                                enabled = samPoints.isNotEmpty(),
                            ) { Text("Urungkan Titik") }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        val rt = id.eraserremake.app.data
                                            .ModelRuntimeHolder.get(context)
                                        session.samCancel(rt)
                                    }
                                    samActiveUi = false
                                    samPreviewBmp = null
                                    samPoints = emptyList()
                                }
                            }) { Text("Batal") }
                        }
                        detections.isNotEmpty() -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            for (det in detections) {
                                val idx = det.index.toInt()
                                FilterChip(
                                    selected = idx in selectedObjects,
                                    onClick = {
                                        selectedObjects = if (idx in selectedObjects) {
                                            selectedObjects - idx
                                        } else {
                                            selectedObjects + idx
                                        }
                                    },
                                    label = {
                                        Text(
                                            det.label + " " +
                                                (det.score * 100).roundToInt().toString() + "%",
                                        )
                                    },
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            session.applyObjectSelection(
                                                selectedObjects.toList(), true,
                                            )
                                        }.onSuccess {
                                            detections = emptyList()
                                            selectedObjects = emptySet()
                                            refreshPreview()
                                        }.onFailure { objekError = it.message ?: "gagal menerapkan" }
                                    }
                                },
                                enabled = selectedObjects.isNotEmpty(),
                            ) { Text("Simpan Terpilih") }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            session.applyObjectSelection(
                                                selectedObjects.toList(), false,
                                            )
                                        }.onSuccess {
                                            detections = emptyList()
                                            selectedObjects = emptySet()
                                            refreshPreview()
                                        }.onFailure { objekError = it.message ?: "gagal menerapkan" }
                                    }
                                },
                                enabled = selectedObjects.isNotEmpty(),
                            ) { Text("Hapus Terpilih") }
                            TextButton(onClick = {
                                scope.launch { session.clearDetections() }
                                detections = emptyList()
                                selectedObjects = emptySet()
                            }) { Text("Bersihkan") }
                        }
                        else -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = objekMode == "Deteksi",
                                onClick = { objekMode = "Deteksi" },
                                label = { Text("Deteksi") },
                            )
                            FilterChip(
                                selected = objekMode == "Ketuk",
                                onClick = { objekMode = "Ketuk" },
                                label = { Text("Ketuk") },
                            )
                            if (objekMode == "Deteksi") {
                                if (instanceModels.isEmpty()) {
                                    Text(
                                        "Install model Deteksi di Manajer Model",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Box {
                                        OutlinedButton(onClick = { instanceMenuOpen = true }) {
                                            Text(
                                                instanceModels
                                                    .firstOrNull { it.id == selectedInstanceId }
                                                    ?.name ?: "Pilih model",
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = instanceMenuOpen,
                                            onDismissRequest = { instanceMenuOpen = false },
                                        ) {
                                            for (m in instanceModels) {
                                                DropdownMenuItem(
                                                    text = { Text(m.name) },
                                                    onClick = {
                                                        selectedInstanceId = m.id
                                                        instanceMenuOpen = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Button(onClick = {
                                        val mid = selectedInstanceId ?: return@Button
                                        objekBusy = true
                                        objekError = null
                                        scope.launch {
                                            runCatching {
                                                id.eraserremake.app.data.ModelRuntimeHolder
                                                    .registerInstalled(context, modelStore)
                                                val rt = id.eraserremake.app.data
                                                    .ModelRuntimeHolder.get(context)
                                                session.detectObjects(rt, mid) { p ->
                                                    objekProgress = p
                                                }
                                            }.onSuccess { found ->
                                                detections = found
                                                selectedObjects = emptySet()
                                            }.onFailure {
                                                objekError = it.message ?: "deteksi gagal"
                                            }
                                            objekBusy = false
                                        }
                                    }) { Text("Deteksi Objek") }
                                }
                            } else {
                                if (samModels.isEmpty()) {
                                    Text(
                                        "Install model SAM di Manajer Model",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Box {
                                        OutlinedButton(onClick = { samMenuOpen = true }) {
                                            Text(
                                                samModels
                                                    .firstOrNull { it.id == selectedSamId }
                                                    ?.name ?: "Pilih model",
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = samMenuOpen,
                                            onDismissRequest = { samMenuOpen = false },
                                        ) {
                                            for (m in samModels) {
                                                DropdownMenuItem(
                                                    text = { Text(m.name) },
                                                    onClick = {
                                                        selectedSamId = m.id
                                                        samMenuOpen = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    Button(onClick = {
                                        val mid = selectedSamId ?: return@Button
                                        objekBusy = true
                                        objekError = null
                                        scope.launch {
                                            runCatching {
                                                id.eraserremake.app.data.ModelRuntimeHolder
                                                    .registerInstalled(context, modelStore)
                                                val rt = id.eraserremake.app.data
                                                    .ModelRuntimeHolder.get(context)
                                                session.samStart(rt, mid) { p ->
                                                    objekProgress = p
                                                }
                                            }.onSuccess {
                                                samActiveUi = true
                                                samPoints = emptyList()
                                                samPreviewBmp = null
                                            }.onFailure {
                                                objekError = it.message ?: "gagal memulai SAM"
                                            }
                                            objekBusy = false
                                        }
                                    }) { Text("Mulai Prompt") }
                                }
                            }
                        }
                    }
                    EditorTool.Potong -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        val ratios = listOf(
                            "Bebas" to null,
                            "1:1" to 1f,
                            "4:5" to 0.8f,
                            "3:4" to 0.75f,
                            "16:9" to (16f / 9f),
                        )
                        ratios.forEach { (label, ratio) ->
                            FilterChip(
                                selected = cropRatio == ratio,
                                onClick = {
                                    cropRatio = ratio
                                    if (ratio != null) {
                                        cropRect = centeredRatioRect(imgW, imgH, ratio)
                                    }
                                },
                                label = { Text(label) },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val rect = cropRect ?: return@Button
                            scope.launch {
                                runCatching {
                                    session.crop(
                                        CropRect(
                                            rect.left.roundToInt().coerceAtLeast(0).toUInt(),
                                            rect.top.roundToInt().coerceAtLeast(0).toUInt(),
                                            rect.width.roundToInt().coerceAtLeast(1).toUInt(),
                                            rect.height.roundToInt().coerceAtLeast(1).toUInt(),
                                        ),
                                    )
                                }.onSuccess {
                                    zoom = 1f
                                    pan = Offset.Zero
                                    cropRect = Rect(
                                        0f,
                                        0f,
                                        session.imageWidth().toFloat(),
                                        session.imageHeight().toFloat(),
                                    )
                                    // Potong membatalkan deteksi dan sesi SAM di engine.
                                    detections = emptyList()
                                    selectedObjects = emptySet()
                                    samActiveUi = false
                                    samPreviewBmp = null
                                    samPoints = emptyList()
                                    refreshPreview()
                                }
                            }
                        }) { Text("Terapkan") }
                        TextButton(onClick = {
                            cropRect = Rect(0f, 0f, imgW.toFloat(), imgH.toFloat())
                            cropRatio = null
                        }) { Text("Batal") }
                    }

                    EditorTool.Zoom -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            zoom = 1f
                            pan = Offset.Zero
                        }) { Text("Pas Layar") }
                        TextButton(onClick = {
                            val fit = fitScale()
                            if (fit > 0f) {
                                zoom = 1f / fit
                                pan = Offset.Zero
                            }
                        }) { Text("100%") }
                        Text("${(zoom * 100).roundToInt()}%")
                    }

                    else -> Text(
                        notice ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EditorTool.entries.forEach { t ->
                    Surface(
                        onClick = {
                            if (t.phaseNote != null) {
                                notice = t.phaseNote
                                tool = t
                            } else {
                                tool = t
                                notice = null
                                if (t == EditorTool.Potong && cropRect == null) {
                                    cropRect = Rect(0f, 0f, imgW.toFloat(), imgH.toFloat())
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.large,
                        color = if (tool == t) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        modifier = Modifier.size(width = 72.dp, height = 56.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                t.icon,
                                contentDescription = t.label,
                                modifier = Modifier.size(22.dp),
                                tint = if (t.phaseNote != null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                t.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (t.phaseNote != null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (exportOpen) {
        ExportSheet(
            session = session,
            store = store,
            onDismiss = { exportOpen = false },
        )
    }
}

private fun centeredRatioRect(imgW: Int, imgH: Int, ratio: Float): Rect {
    val imgRatio = imgW.toFloat() / imgH
    val w: Float
    val h: Float
    if (imgRatio > ratio) {
        h = imgH.toFloat()
        w = h * ratio
    } else {
        w = imgW.toFloat()
        h = w / ratio
    }
    val left = (imgW - w) / 2f
    val top = (imgH - h) / 2f
    return Rect(left, top, left + w, top + h)
}
