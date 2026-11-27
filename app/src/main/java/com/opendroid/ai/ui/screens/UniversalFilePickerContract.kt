package com.opendroid.ai.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Multi-select picker for arbitrary files.
 *
 * The picker must never restrict what the user can attach. Android treats
 * `EXTRA_MIME_TYPES` as a filter even when the intent type is the wildcard MIME
 * type: a hint list makes document providers grey out anything not listed, which is
 * what hid APK, BIN, notebook, source and ZIP files. The filter is therefore omitted
 * so the picker opens unfiltered.
 */
class UniversalFilePickerContract : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        /**
         * Retained for callers that still pass hints. It is deliberately not applied to
         * the intent - see the class comment - so it can never filter the picker.
         */
        val REQUIRED_MIME_TYPES = listOf("*/*")
    }
}
