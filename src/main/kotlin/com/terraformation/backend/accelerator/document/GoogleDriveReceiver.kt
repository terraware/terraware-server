package com.terraformation.backend.accelerator.document

import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.file.GoogleDriveWriter
import java.io.InputStream
import java.net.URI

class GoogleDriveReceiver(
    private val googleDriveWriter: GoogleDriveWriter,
    private val googleFolderUrl: URI,
) : SubmissionDocumentReceiver {
  override val documentStore: DocumentStore
    get() = DocumentStore.Google

  override fun upload(inputStream: InputStream, fileName: String, contentType: String): StoredFile {
    val folderId = googleDriveWriter.getFileIdForFolderUrl(googleFolderUrl)
    val fileId =
        googleDriveWriter.uploadFile(
            folderId,
            fileName,
            contentType,
            inputStream,
            overwriteExistingFile = false,
        )

    return StoredFile(fileName, fileId)
  }

  override fun rename(storedFile: StoredFile, newName: String) {
    googleDriveWriter.renameFile(storedFile.location, newName)
  }

  override fun delete(storedFile: StoredFile) {
    googleDriveWriter.deleteFile(storedFile.location)
  }
}
