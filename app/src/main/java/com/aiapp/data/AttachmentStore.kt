package com.aiapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.aiapp.llm.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies picked files into the app's storage and extracts text from text-like files. */
object AttachmentStore {
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val MAX_TEXT = 2_000_000
    private const val MAX_IMAGE_SIDE = 1600

    private val textExtensions = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "yaml", "yml", "toml", "ini", "log",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs",
        "swift", "rb", "php", "sh", "sql", "gradle", "properties", "css", "scss", "dart", "lua", "r",
    )

    suspend fun import(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw IllegalArgumentException("$name is larger than 30 MB.")
        val ext = name.substringAfterLast('.', "").lowercase()
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        if (mime == "application/octet-stream" && ext in textExtensions) mime = "text/plain"
        if (ext == "pdf") mime = "application/pdf"

        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val out = File(dir, "${UUID.randomUUID().toString().take(8)}_$safe")
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Couldn't open $name.")

        when {
            mime.startsWith("image/") -> {
                // Shrink big photos: models don't need more detail, and it keeps requests fast and cheap.
                val shrunk = shrink(out)
                Attachment(name, if (shrunk) "image/jpeg" else mime, out.absolutePath)
            }
            mime == "application/pdf" -> Attachment(name, mime, out.absolutePath)
            mime.startsWith("text/") || ext in textExtensions || mime.contains("json") || mime.contains("xml") -> {
                val text = out.readText().take(MAX_TEXT)
                Attachment(name, "text/plain", out.absolutePath, text)
            }
            else -> Attachment(name, mime, out.absolutePath)
        }
    }

    private fun shrink(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return false
        if (longest <= MAX_IMAGE_SIDE && file.length() < 1_500_000) return false
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val final = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        file.outputStream().use { final.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return true
    }
}
