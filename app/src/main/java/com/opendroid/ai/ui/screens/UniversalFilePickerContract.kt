package com.opendroid.ai.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** Multi-select picker for arbitrary files; MIME hints never prevent attaching unknown types. */
class UniversalFilePickerContract : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        // The requested slash sentinel is passed both as the intent type and in EXTRA_MIME_TYPES.
        type = "/"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        putExtra(Intent.EXTRA_MIME_TYPES, (REQUIRED_MIME_TYPES + input).distinct().toTypedArray())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != android.app.Activity.RESULT_OK || intent == null) return emptyList()
        val uris = LinkedHashSet<Uri>()
        intent.data?.let(uris::add)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
        }
        return uris.toList()
    }

    companion object {
        val REQUIRED_MIME_TYPES = listOf(
            "/",
            "application/vnd.android.package-archive",
            "application/zip",
            "application/octet-stream",
            "text/*",
            "application/x-ipynb+json",
            "application/json",
        )
    }
}
