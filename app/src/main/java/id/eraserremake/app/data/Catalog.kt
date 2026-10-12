package id.eraserremake.app.data

import org.json.JSONObject

/** Satu entri katalog model (design doc 8). fileUrl "asset://..." berarti terbundel. */
data class CatalogModel(
    val id: String,
    val name: String,
    val kind: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String,
    val minAppVersion: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val fileUrl: String,
    val notes: String,
) {
    val isBundledAsset: Boolean get() = fileUrl.startsWith("asset://")
}

/** Parse katalog JSON. Format tidak valid menghasilkan daftar kosong. */
fun parseCatalog(json: String): List<CatalogModel> {
    return runCatching {
        val root = JSONObject(json)
        val models = root.getJSONArray("models")
        (0 until models.length()).map { i ->
            val m = models.getJSONObject(i)
            val input = m.optJSONObject("input")
            CatalogModel(
                id = m.getString("id"),
                name = m.getString("name"),
                kind = m.getString("kind"),
                version = m.optString("version", "1"),
                sizeBytes = m.optLong("size_bytes", 0),
                sha256 = m.optString("sha256", ""),
                minAppVersion = m.optInt("min_app_version", 1),
                inputWidth = input?.optInt("width", 320) ?: 320,
                inputHeight = input?.optInt("height", 320) ?: 320,
                fileUrl = m.getString("file_url"),
                notes = m.optString("notes", ""),
            )
        }
    }.getOrDefault(emptyList())
}
