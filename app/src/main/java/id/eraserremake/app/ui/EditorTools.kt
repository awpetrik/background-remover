package id.eraserremake.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector
import uniffi.eraser_core.BrushMode
import uniffi.eraser_core.ImagePoint

/** Delapan alat editor, urutan mengikuti frekuensi pakai (design doc 5.2). */
enum class EditorTool(
    val label: String,
    val icon: ImageVector,
    val phaseNote: String?,
) {
    Ai("AI", Icons.Filled.ContentCut, null),
    Objek("Objek", Icons.Filled.Category, null),
    Warna("Warna", Icons.Filled.FormatColorFill, null),
    Magic("Magic", Icons.Filled.Colorize, null),
    Kuas("Kuas", Icons.Filled.Brush, null),
    Pulihkan("Pulihkan", Icons.Filled.Healing, null),
    Potong("Potong", Icons.Filled.Crop, null),
    Zoom("Zoom", Icons.Filled.ZoomIn, null),
}

sealed interface StrokeEvent {
    data object Begin : StrokeEvent
    data class Segment(val points: List<ImagePoint>, val radius: Float, val mode: BrushMode) :
        StrokeEvent
    data object End : StrokeEvent
}
