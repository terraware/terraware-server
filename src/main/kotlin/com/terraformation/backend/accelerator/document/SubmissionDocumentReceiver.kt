package com.terraformation.backend.accelerator.document

import com.terraformation.backend.db.accelerator.DocumentStore
import java.io.InputStream

/** Common interface for interactions with document stores to save uploaded submission documents. */
interface SubmissionDocumentReceiver {
  /** Which document store this receiver talks to. */
  val documentStore: DocumentStore

  fun upload(inputStream: InputStream, fileName: String, contentType: String): StoredFile

  fun rename(storedFile: StoredFile, newName: String)

  fun delete(storedFile: StoredFile)
}
