package com.opendroid.ai.core.drive

interface NotebookDriveUploader {
    suspend fun uploadNotebook(filename: String, contentBytes: ByteArray): GoogleDriveNotebookUploadResult
}
