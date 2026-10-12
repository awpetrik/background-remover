package id.eraserremake.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import uniffi.eraser_core.BrushMode
import uniffi.eraser_core.ImagePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class DragMode { None, Stroke, Transform, CropMove, CropCorner, CropNew }

/**
 * Penanganan gestur kanvas editor dalam satu loop pointer:
 * satu jari menggambar (Kuas/Pulihkan) atau menggeser crop (Potong) atau pan
 * (Zoom); dua jari selalu berarti zoom/pan, dari alat apa pun.
 */
fun Modifier.editorGestures(
    tool: () -> EditorTool,
    isCropTool: () -> Boolean,
    screenToImage: (Offset) -> ImagePoint,
    brushRadius: () -> Float,
    brushOffsetPx: () -> Float,
    onStrokeEvent: (StrokeEvent) -> Unit,
    onTransform: (deltaPan: Offset, zoomFactor: Float) -> Unit,
    onCursor: (Offset?) -> Unit,
    onTapPoint: (ImagePoint) -> Unit,
    cropRect: () -> Rect?,
    onCropRect: (Rect) -> Unit,
    cropRatio: () -> Float?,
    imageSize: () -> Size,
    dispScale: () -> Float,
    imageTopLeft: () -> Offset,
): Modifier = pointerInput(Unit) {
    var mode = DragMode.None
    var lastImg: ImagePoint? = null
    var prevCentroid: Offset? = null
    var prevSpan: Float = 0f
    var tapStart: Offset? = null
    var tapMoved = false
    var cropStartRect: Rect? = null
    var cropAnchor: Offset = Offset.Zero // dalam koordinat gambar
    var cropCorner: Int = -1 // 0 TL, 1 TR, 2 BL, 3 BR
    var cropStartPoint: ImagePoint = ImagePoint(0f, 0f)

    fun imagePointOf(change: PointerInputChange, withOffset: Boolean): ImagePoint {
        val pos = if (withOffset) {
            change.position - Offset(0f, brushOffsetPx())
        } else {
            change.position
        }
        return screenToImage(pos)
    }

    fun clampToImage(p: ImagePoint): ImagePoint {
        val size = imageSize()
        return ImagePoint(
            p.x.coerceIn(0f, size.width),
            p.y.coerceIn(0f, size.height),
        )
    }

    fun cropScreenRect(): Rect? {
        val rect = cropRect() ?: return null
        val tl = imageTopLeft()
        val ds = dispScale()
        return Rect(
            Offset(tl.x + rect.left * ds, tl.y + rect.top * ds),
            Size(rect.width * ds, rect.height * ds),
        )
    }

    fun applyCropDrag(current: ImagePoint) {
        val start = cropStartRect ?: return
        val size = imageSize()
        val ratio = cropRatio()
        when (mode) {
            DragMode.CropMove -> {
                val dx = current.x - cropStartPoint.x
                val dy = current.y - cropStartPoint.y
                var left = start.left + dx
                var top = start.top + dy
                left = left.coerceIn(0f, size.width - start.width)
                top = top.coerceIn(0f, size.height - start.height)
                onCropRect(Rect(left, top, left + start.width, top + start.height))
            }
            DragMode.CropCorner, DragMode.CropNew -> {
                var left = min(cropAnchor.x, current.x)
                var top = min(cropAnchor.y, current.y)
                var right = max(cropAnchor.x, current.x)
                var bottom = max(cropAnchor.y, current.y)
                if (ratio != null && (right - left) > 0f) {
                    // Jaga rasio dari jangkar: sesuaikan tinggi mengikuti lebar.
                    val h = (right - left) / ratio
                    if (current.y >= cropAnchor.y) {
                        top = cropAnchor.y
                        bottom = cropAnchor.y + h
                    } else {
                        bottom = cropAnchor.y
                        top = cropAnchor.y - h
                    }
                    if (top < 0f || bottom > size.height) {
                        val clampedH = if (current.y >= cropAnchor.y) {
                            size.height - cropAnchor.y
                        } else {
                            cropAnchor.y
                        }
                        val w = clampedH * ratio
                        if (current.x >= cropAnchor.x) {
                            left = cropAnchor.x
                            right = cropAnchor.x + w
                        } else {
                            right = cropAnchor.x
                            left = cropAnchor.x - w
                        }
                        top = min(cropAnchor.y, if (current.y >= cropAnchor.y) cropAnchor.y else cropAnchor.y - clampedH)
                        bottom = top + clampedH
                    }
                }
                left = left.coerceIn(0f, size.width)
                right = right.coerceIn(0f, size.width)
                top = top.coerceIn(0f, size.height)
                bottom = bottom.coerceIn(0f, size.height)
                if (right - left >= 8f && bottom - top >= 8f) {
                    onCropRect(Rect(left, top, right, bottom))
                }
            }
            else -> Unit
        }
    }

    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            val currentTool = tool()

            if (pressed.size >= 2) {
                if (mode == DragMode.Stroke) {
                    onStrokeEvent(StrokeEvent.End)
                    onCursor(null)
                }
                mode = DragMode.Transform
                val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                    pressed.size.toFloat()
                val span = if (pressed.size >= 2) {
                    val d = pressed[0].position - pressed[1].position
                    sqrt(d.x * d.x + d.y * d.y)
                } else {
                    0f
                }
                val pc = prevCentroid
                if (pc != null) {
                    val factor = if (prevSpan > 1f && span > 1f) span / prevSpan else 1f
                    onTransform(centroid - pc, factor)
                }
                prevCentroid = centroid
                prevSpan = span
                event.changes.forEach { it.consume() }
                continue
            }

            if (pressed.size == 1) {
                val change = pressed[0]
                prevCentroid = null
                prevSpan = 0f

                if (currentTool == EditorTool.Warna || currentTool == EditorTool.Magic ||
                    currentTool == EditorTool.Objek
                ) {
                    // Ketuk = pilih titik. Seret = geser kanvas.
                    if (change.changedToDown()) {
                        tapStart = change.position
                        tapMoved = false
                    } else if (tapStart != null) {
                        val start = tapStart!!
                        val dist = change.position - start
                        if (sqrt(dist.x * dist.x + dist.y * dist.y) > 14.dp.toPx()) {
                            tapMoved = true
                        }
                        if (tapMoved) {
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) onTransform(delta, 1f)
                        }
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }

                if (isCropTool()) {
                    if (change.changedToDown()) {
                        val img = clampToImage(imagePointOf(change, withOffset = false))
                        val screen = cropScreenRect()
                        val handlePx = 36.dp.toPx()
                        val corners = screen?.let {
                            listOf(it.topLeft, it.topRight, it.bottomLeft, it.bottomRight)
                        } ?: emptyList()
                        val cornerIdx = corners.indexOfFirst {
                            val d = it - change.position
                            abs(d.x) <= handlePx && abs(d.y) <= handlePx
                        }
                        when {
                            cornerIdx >= 0 && cropRect() != null -> {
                                mode = DragMode.CropCorner
                                cropCorner = cornerIdx
                                val r = cropRect()!!
                                cropAnchor = when (cornerIdx) {
                                    0 -> Offset(r.right, r.bottom)
                                    1 -> Offset(r.left, r.bottom)
                                    2 -> Offset(r.right, r.top)
                                    else -> Offset(r.left, r.top)
                                }
                                cropStartRect = r
                            }
                            screen != null && screen.contains(change.position) -> {
                                mode = DragMode.CropMove
                                cropStartRect = cropRect()
                                cropStartPoint = img
                            }
                            else -> {
                                mode = DragMode.CropNew
                                cropAnchor = Offset(img.x, img.y)
                                cropStartRect = Rect(img.x, img.y, img.x, img.y)
                                onCropRect(Rect(img.x, img.y, img.x + 1f, img.y + 1f))
                            }
                        }
                    } else if (mode == DragMode.CropMove ||
                        mode == DragMode.CropCorner ||
                        mode == DragMode.CropNew
                    ) {
                        applyCropDrag(clampToImage(imagePointOf(change, withOffset = false)))
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }

                when (currentTool) {
                    EditorTool.Kuas, EditorTool.Pulihkan -> {
                        val cursorPos = change.position - Offset(0f, brushOffsetPx())
                        onCursor(cursorPos)
                        val img = screenToImage(cursorPos)
                        if (change.changedToDown() || mode != DragMode.Stroke) {
                            mode = DragMode.Stroke
                            onStrokeEvent(StrokeEvent.Begin)
                            onStrokeEvent(
                                StrokeEvent.Segment(
                                    listOf(img),
                                    brushRadius(),
                                    if (currentTool == EditorTool.Kuas) {
                                        BrushMode.ERASE
                                    } else {
                                        BrushMode.RESTORE
                                    },
                                ),
                            )
                            lastImg = img
                        } else {
                            val last = lastImg
                            val points = if (last != null) listOf(last, img) else listOf(img)
                            onStrokeEvent(
                                StrokeEvent.Segment(
                                    points,
                                    brushRadius(),
                                    if (currentTool == EditorTool.Kuas) {
                                        BrushMode.ERASE
                                    } else {
                                        BrushMode.RESTORE
                                    },
                                ),
                            )
                            lastImg = img
                        }
                    }
                    EditorTool.Zoom -> {
                        if (change.pressed) {
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) onTransform(delta, 1f)
                        }
                    }
                    else -> Unit
                }
                event.changes.forEach { it.consume() }
                continue
            }

            if (mode == DragMode.Stroke) {
                onStrokeEvent(StrokeEvent.End)
            }
            val startTap = tapStart
            if (startTap != null && !tapMoved &&
                (tool() == EditorTool.Warna || tool() == EditorTool.Magic || tool() == EditorTool.Objek)
            ) {
                onTapPoint(screenToImage(startTap))
            }
            tapStart = null
            tapMoved = false
            mode = DragMode.None
            lastImg = null
            prevCentroid = null
            prevSpan = 0f
            onCursor(null)
        }
    }
}
