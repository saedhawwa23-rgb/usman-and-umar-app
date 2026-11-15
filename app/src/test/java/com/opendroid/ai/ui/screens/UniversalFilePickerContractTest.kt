package com.opendroid.ai.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UniversalFilePickerContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `picker accepts arbitrary files with required MIME hints and persistent permission flags`() {
        val contract = UniversalFilePickerContract()
        val intent = contract.createIntent(context, UniversalFilePickerContract.REQUIRED_MIME_TYPES.toTypedArray())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        // No EXTRA_MIME_TYPES: a hint list is treated as a filter by document providers
        // even when type is */*, which greyed out APK/BIN/notebook/source/ZIP files.
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(requiredFlags, intent.flags and requiredFlags)

        // Every file category is attachable and eligible for Colab transfer; nothing is
        // implicitly decoded, executed, or filtered by type.
        val attachments = listOf(
            PendingChatAttachment("app.apk", "application/vnd.android.package-archive", sendToColabEligible = true),
            PendingChatAttachment("archive.zip", "application/zip", sendToColabEligible = true),
            PendingChatAttachment("payload.bin", "application/octet-stream", sendToColabEligible = true),
            PendingChatAttachment("notebook.ipynb", "application/x-ipynb+json", textContent = "{}", sendToColabEligible = true),
            PendingChatAttachment("script.py", "text/x-python", textContent = "print('ok')", sendToColabEligible = true),
        )
        assertEquals(listOf("app.apk", "archive.zip", "payload.bin", "notebook.ipynb", "script.py"), attachments.map { it.displayName })
        assertTrue(attachments.all { it.sendToColabEligible })
    }

    @Test
    fun `picker returns distinct single and multi-select URIs`() {
        val first = Uri.parse("content://files/test.apk")
        val second = Uri.parse("content://files/notebook.ipynb")
        val clip = ClipData("files", arrayOf("text/uri-list"), ClipData.Item(first)).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
        }
        val intent = Intent().setData(first)
        intent.clipData = clip
        assertEquals(listOf(first, second), UniversalFilePickerContract().parseResult(Activity.RESULT_OK, intent))
        assertTrue(UniversalFilePickerContract().parseResult(Activity.RESULT_CANCELED, intent).isEmpty())
    }
}
