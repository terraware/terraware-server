package com.terraformation.backend.accelerator.document

import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.file.DropboxWriter
import java.io.InputStream

class DropboxReceiver(
    private val dropboxWriter: DropboxWriter,
    private val folderPath: String,
) : SubmissionDocumentReceiver {
  override val documentStore: DocumentStore
    get() = DocumentStore.Dropbox

  override fun upload(inputStream: InputStream, fileName: String, contentType: String): StoredFile {
    val storedName = dropboxWriter.uploadFile(folderPath, fileName, inputStream)

    return StoredFile(storedName, "$folderPath/$storedName")
  }

  override fun rename(storedFile: StoredFile, newName: String) {
    dropboxWriter.rename(storedFile.location, "$folderPath/$newName")
  }

  override fun delete(storedFile: StoredFile) {
    dropboxWriter.delete(storedFile.location)
  }
}
