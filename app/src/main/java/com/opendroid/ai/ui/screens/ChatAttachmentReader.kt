package com.opendroid.ai.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** A selected file is metadata-first. No attachment is executed or uploaded implicitly. */
data class PendingChatAttachment(
    val displayName: String,
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long? = null,
    val uri: Uri? = null,
    val imageBase64: String? = null,
    val textContent: String? = null,
    val sendToColabEligible: Boolean = false,
    val sendToColabRejectionReason: String? = null,
) {
    val displaySize: String
        get() = sizeBytes?.let(::formatFileSize) ?: "size unknown"
}

@Suppress("UNUSED_PARAMETER")
fun readChatAttachment(context: Context, uri: Uri, isDocument: Boolean): PendingChatAttachment {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)?.lowercase(Locale.ROOT).orEmpty().ifBlank { "application/octet-stream" }
    val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0 } else null
            name to size
        } else null
    }
    val displayName = metadata?.first?.takeIf { it.isNotBlank() } ?: "attached-file"
    val reportedSize = metadata?.second
    val lowerName = displayName.lowercase(Locale.ROOT)

    if (mimeType.startsWith("image/")) {
        val image = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("Unreadable image")
            bitmapToBase64(bitmap).also { bitmap.recycle() }
        }.getOrNull()
        return PendingChatAttachment(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = reportedSize,
            uri = uri,
            imageBase64 = image,
            sendToColabRejectionReason = "Only UTF-8 plain-text files up to 1 MB can be sent to Colab.",
        )
    }

    val extractedDocumentText = when {
        mimeType == "application/pdf" || lowerName.endsWith(".pdf") -> runCatching {
            val bytes = readBounded(resolver.openInputStream(uri), MAX_DOCUMENT_BYTES) ?: return@runCatching null
            if (bytes.size > MAX_DOCUMENT_BYTES) return@runCatching null
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(bytes).use { PDFTextStripper().getText(it).trim().take(MAX_CHAT_TEXT_CHARS) }
        }.getOrNull()
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || lowerName.endsWith(".docx") -> runCatching {
            val bytes = readBounded(resolver.openInputStream(uri), MAX_DOCUMENT_BYTES) ?: return@runCatching null
            if (bytes.size > MAX_DOCUMENT_BYTES) return@runCatching null
            val xml = ZipInputStream(bytes.inputStream()).use { zip ->
                var body: ByteArray? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") { body = readBounded(zip, MAX_DOCUMENT_BYTES); break }
                }
                body
            } ?: return@runCatching null
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setExpandEntityReferences(false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(String(xml, Charsets.UTF_8))))
            document.documentElement.textContent.replace(Regex("\\s+"), " ").trim().take(MAX_CHAT_TEXT_CHARS)
        }.getOrNull()
        else -> null
    }
    if (extractedDocumentText != null) {
        return PendingChatAttachment(displayName, mimeType, reportedSize, uri, textContent = extractedDocumentText, sendToColabRejectionReason = "Only UTF-8 plain-text files up to 1 MB can be sent to Colab.")
    }

    if (isLikelyUtf8Text(displayName, mimeType)) {
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_COLAB_TEXT_BYTES) return@use null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }.getOrNull()
        if (bytes != null) {
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (decoded != null) {
                return PendingChatAttachment(
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = reportedSize ?: bytes.size.toLong(),
                    uri = uri,
                    textContent = decoded.take(MAX_CHAT_TEXT_CHARS),
                    sendToColabEligible = bytes.size <= MAX_COLAB_TEXT_BYTES,
                )
            }
        }
    }

    // Binary, unknown, invalid-UTF-8, and oversized files remain attachable as metadata.
    val rejection = when {
        isLikelyUtf8Text(displayName, mimeType) && reportedSize != null && reportedSize > MAX_COLAB_TEXT_BYTES -> "Text file exceeds the 1 MB Colab transfer limit."
        isLikelyUtf8Text(displayName, mimeType) -> "File is not valid UTF-8 text or could not be read."
        else -> "Binary and unsupported files can be attached, but only UTF-8 text up to 1 MB can be sent to Colab."
    }
    return PendingChatAttachment(displayName, mimeType, reportedSize, uri, sendToColabRejectionReason = rejection)
}

private fun readBounded(input: java.io.InputStream?, maximumBytes: Int): ByteArray? {
    if (input == null) return null
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > maximumBytes) return null
            output.write(buffer, 0, count)
        }
    }
}

private fun isLikelyUtf8Text(name: String, mimeType: String): Boolean {
    val textExtensions = setOf(".txt", ".md", ".csv", ".tsv", ".json", ".xml", ".log", ".yaml", ".yml", ".py", ".ipynb", ".sh", ".html", ".css", ".js", ".kt", ".java")
    return mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml", "application/csv", "application/x-ipynb+json") ||
        textExtensions.any(name.lowercase(Locale.ROOT)::endsWith)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private const val MAX_COLAB_TEXT_BYTES = 1024 * 1024
private const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024
private const val MAX_CHAT_TEXT_CHARS = 2_000_000

fun bitmapToBase64(bitmap: Bitmap): String {
    val maxDimension = 1600
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
    val resized = if (scale < 1f) {
        bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
        )
    } else bitmap
    try {
        var quality = 86
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 8
        } while (bytes.size > 6 * 1024 * 1024 && quality >= 46)
        require(bytes.size <= 6 * 1024 * 1024) { "The image is too large to attach." }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally {
        if (resized !== bitmap) resized.recycle()
    }
}
