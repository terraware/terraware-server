package com.terraformation.backend.file

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.model.ExistingFileMetadata
import java.io.InputStream
import java.net.URI

/**
 * Writes deliverable and report documents to a Google-Drive-like store. The production
 * implementation talks to the real Google Drive API; an alternate implementation stores files in
 * the local [FileStore].
 *
 * Files and folders are identified by opaque string IDs. Folders are addressable by URLs of the
 * form `https://drive.google.com/drive/folders/<id>`.
 */
interface GoogleDriveWriter {
  /**
   * Returns the file ID of the innermost folder in a hierarchy, creating folders as needed.
   *
   * @param names List of path elements, starting from the parent folder.
   */
  fun findOrCreateFolders(driveId: String, parentFolderId: String, names: List<String>): String

  /**
   * Uploads a file. If [overwriteExistingFile] is true and an existing file with the same
   * [filename] (and [fileId], if supplied) is found, it is replaced; otherwise a new file is
   * created. Filenames are not required to be unique.
   *
   * @return The file ID of the uploaded file.
   */
  fun uploadFile(
      parentFolderId: String,
      filename: String,
      contentType: String,
      inputStream: InputStream,
      driveId: String = getDriveIdForFile(parentFolderId),
      description: String? = null,
      fileId: FileId? = null,
      inputStreamContentType: String = contentType,
      overwriteExistingFile: Boolean = true,
  ): String

  fun getFileContentType(googleFileId: String): String?

  fun downloadFile(googleFileId: String): InputStream

  /** Copies a file from a [FileStore] to the document store. */
  fun copyFile(
      driveId: String,
      parentFolderId: String,
      metadata: ExistingFileMetadata,
      description: String? = null,
      name: String = metadata.filenameWithoutPath,
  ): String

  /** Looks up the drive ID for a given file ID. The file may be a folder or a regular file. */
  fun getDriveIdForFile(googleFileId: String): String

  /** Extracts the file ID from a folder URL (the last path element). */
  fun getFileIdForFolderUrl(folderUrl: URI): String

  fun renameFile(googleFileId: String, newName: String)

  fun deleteFile(googleFileId: String)

  /** Returns a shareable link to the file. */
  fun shareFile(googleFileId: String): URI

  /** Moves a file or folder to a different parent folder. */
  fun moveFile(googleFileId: String, parentFileId: String)
}
