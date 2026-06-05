package com.terraformation.backend.file

import java.io.InputStream
import java.net.URI

/**
 * Writes deliverable documents to a Dropbox-like store. The production implementation talks to the
 * real Dropbox API; an alternate implementation stores files in the local [FileStore].
 */
interface DropboxWriter {
  /**
   * Uploads a file to a specified path. If there is already a file with the same path, a different
   * filename is chosen automatically. Missing parent folders are created automatically.
   *
   * @return The actual filename that was created.
   */
  fun uploadFile(folderPath: String, name: String, inputStream: InputStream): String

  /** Creates a folder. Any missing parent folders will also be created. */
  fun createFolder(path: String)

  fun rename(oldPath: String, newPath: String)

  /** Deletes a file or folder. If [path] is a folder, the files in it are deleted too. */
  fun delete(path: String)

  fun shareFile(path: String): URI
}
