package com.opendroid.ai.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

data class PendingChatAttachment(
    val displayName: String,
    val imageBase64: String? = null,
    val textContent: String? = null,
)

fun readChatAttachment(context: Context, uri: Uri, isDocument: Boolean): PendingChatAttachment {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: if (mimeType.startsWith("image/")) "attached-image.jpg" else "attached-document.txt"

    if (mimeType.startsWith("image/")) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image could not be decoded." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("The selected image could not be opened.")
        return PendingChatAttachment(displayName, imageBase64 = bitmapToBase64(bitmap))
    }

    val textExtensions = listOf(".txt", ".md", ".csv", ".tsv", ".json", ".xml", ".log", ".yaml", ".yml")
    val isText = mimeType.startsWith("text/") ||
        mimeType in setOf("application/json", "application/xml", "application/csv") ||
        textExtensions.any { displayName.lowercase(Locale.ROOT).endsWith(it) }
    require(isDocument && isText) {
        "Chat supports images and text documents (TXT, MD, CSV, JSON, XML, and logs). This file type is not supported yet."
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 512 * 1024) { "Text documents must be 512 KB or smaller." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("The selected document could not be opened.")
    return PendingChatAttachment(displayName, textContent = String(bytes, Charsets.UTF_8))
}

fun bitmapToBase64(bitmap: Bitmap): String {
    val maxDimension = 1600
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
    val resized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
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
