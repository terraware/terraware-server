package com.terraformation.backend.file

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI
import java.nio.file.NoSuchFileException

@Named
class GoogleDriveWriter(
    private val config: TerrawareServerConfig,
    private val fileStore: FileStore,
) {
  private val log = perClassLogger()

  /** "Folders" on Google Drive are files with a specific MIME type. */
  private val folderMimeType = "application/vnd.google-apps.folder"

  /**
   * Name of custom property that holds our internal IDs for uploaded files. This is used by the
   * "overwrite the previously-exported copy of the same file" logic and is necessary because
   * filenames aren't required to be unique.
   */
  private val terrawareFileIdProperty = "terrawareFileId"

  /**
   * A client that can make Google Drive API requests. This is initialized lazily because it
   * authenticates with Google, so it blocks until the authentication requests finish and throws an
   * exception if authentication fails.
   */
  private val driveClient by lazy {
    val baseCredentials =
        config.report.googleCredentialsJson?.let { json ->
          GoogleCredentials.fromStream(json.byteInputStream())
        } ?: GoogleCredentials.getApplicationDefault()

    val serviceAccountCredentials =
        baseCredentials.createScoped(listOf("https://www.googleapis.com/auth/drive"))

    // Service accounts can't access private shared drives; we have to impersonate a user who has
    // access. Documentation:
    // https://developers.google.com/identity/protocols/oauth2/service-account#delegatingauthority
    val credentials =
        if (config.report.googleEmail != null) {
          serviceAccountCredentials.createDelegated(config.report.googleEmail)
        } else {
          serviceAccountCredentials
        }

    Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials),
        )
        .setApplicationName("terraware-server")
        .build()
  }

  /**
   * Returns the Google file ID of the innermost folder in a hierarchy. Creates new folders if there
   * aren't already folders with the requested names.
   *
   * @param names List of path elements, starting from the parent folder.
   * @return Google file ID of the innermost folder.
   */
  fun findOrCreateFolders(driveId: String, parentFolderId: String, names: List<String>): String {
    return names.fold(parentFolderId) { parent, name -> findOrCreateFolder(driveId, parent, name) }
  }

  /**
   * Uploads a file to Google Drive. If there is an existing file with the same [filename] and
   * [fileId], replaces it. Otherwise creates a new one. Note that [filename] is not necessarily
   * unique! It is valid to have multiple files with the same name.
   *
   * Google Drive doesn't provide any atomic create-or-replace APIs and doesn't guarantee ACID
   * semantics, so it's not possible to make this fully concurrency-safe. It is possible to end up
   * with two files with the same [filename] and [fileId] if this function is called from two
   * threads or on two hosts at the same time.
   *
   * @param contentType The content type the file should have in Google Drive.
   * @param fileId If non-null, it is used in combination with [filename] to try to detect
   *   previously uploaded copies of the same file and overwrite them in place.
   * @param inputStreamContentType The content type of the uploaded file. If this is different from
   *   [contentType], Google Drive will attempt to convert the file. We use this to create Google
   *   Docs documents from uploaded HTML files.
   * @return Google file ID of the file.
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
  ): String {
    val existingFile =
        if (overwriteExistingFile) findFile(driveId, parentFolderId, filename, fileId) else null

    val file = File()
    file.driveId = driveId
    file.name = filename
    file.mimeType = contentType
    file.description = description

    if (existingFile == null) {
      file.parents = listOf(parentFolderId)
    }
    if (fileId != null) {
      file.properties = mapOf(terrawareFileIdProperty to "$fileId")
    }

    val content = InputStreamContent(inputStreamContentType, inputStream)

    val result =
        if (existingFile != null) {
          driveClient
              .files()
              .update(existingFile, file, content)
              .setSupportsAllDrives(true)
              .setFields("id")
              .execute()
        } else {
          driveClient
              .files()
              .create(file, content)
              .setSupportsAllDrives(true)
              .setFields("id")
              .execute()
        }

    log.info("Uploaded $filename as ${result.id}")

    return result.id
  }

  fun getFileContentType(googleFileId: String): String? {
    return driveClient.files().get(googleFileId).setSupportsAllDrives(true).execute().mimeType
  }

  fun downloadFile(googleFileId: String): InputStream {
    return driveClient
        .files()
        .get(googleFileId)
        .setSupportsAllDrives(true)
        .executeMediaAsInputStream()
  }

  /** Copies a file from a [FileStore] to Google Drive. */
  fun copyFile(
      driveId: String,
      parentFolderId: String,
      metadata: ExistingFileMetadata,
      description: String? = null,
      name: String = metadata.filenameWithoutPath,
  ): String {
    return fileStore.read(metadata.storageUrl).use { inputStream ->
      uploadFile(
          parentFolderId = parentFolderId,
          filename = name,
          contentType = metadata.contentType,
          inputStream = inputStream,
          driveId = driveId,
          description = description,
          fileId = metadata.id,
      )
    }
  }

  /**
   * Looks up the drive ID for a given file ID. The file may be a folder or a regular file.
   *
   * @throws NoSuchFileException The requested file wasn't found. This may mean the file truly
   *   doesn't exist, or it may mean that the user doesn't have permission to see the file.
   */
  fun getDriveIdForFile(googleFileId: String): String {
    return getFileMetadata(googleFileId).driveId
  }

  /**
   * Extracts the file ID from a Google Drive folder URL. The file ID is assumed to be the last path
   * element in the URL.
   */
  fun getFileIdForFolderUrl(folderUrl: URI): String {
    if (folderUrl.host == "drive.google.com" && folderUrl.path.startsWith("/drive/folders/")) {
      return folderUrl.path.substringAfterLast('/')
    } else {
      throw IllegalArgumentException("$folderUrl does not appear to be a Google Drive folder URL")
    }
  }

  fun renameFile(googleFileId: String, newName: String) {
    val newMetadata = File()
    newMetadata.name = newName

    val updateRequest = driveClient.files().update(googleFileId, newMetadata)
    updateRequest.supportsAllDrives = true
    updateRequest.execute()
  }

  fun deleteFile(googleFileId: String) {
    val deleteRequest = driveClient.files().delete(googleFileId)
    deleteRequest.supportsAllDrives = true
    deleteRequest.execute()
  }

  /**
   * Returns a shareable link to the file. Access is still subject to the permission settings on the
   * file.
   */
  fun shareFile(googleFileId: String): URI {
    return URI.create(getFileMetadata(googleFileId, "webViewLink").webViewLink)
  }

  /** Moves a file or folder to a different parent folder. */
  fun moveFile(googleFileId: String, parentFileId: String) {
    // Retrieve the existing parents to remove
    val file = getFileMetadata(googleFileId, "parents")
    val previousParents = file.parents.joinToString(",")

    if (previousParents != parentFileId) {
      val updateRequest =
          driveClient
              .files()
              .update(googleFileId, null)
              .setAddParents(parentFileId)
              .setRemoveParents(previousParents)

      updateRequest.supportsAllDrives = true
      updateRequest.execute()
    }
  }

  /** Returns the metadata for an existing file. */
  private fun getFileMetadata(googleFileId: String, fields: String? = null): File {
    val getRequest = driveClient.files().get(googleFileId)
    getRequest.supportsAllDrives = true
    if (fields != null) {
      getRequest.fields = fields
    }

    try {
      return getRequest.execute()
    } catch (e: GoogleJsonResponseException) {
      if (e.details.code == 404) {
        throw NoSuchFileException(googleFileId, null, "File not found on Google Drive")
      } else {
        throw e
      }
    }
  }

  /** Returns the ID of an existing file, or null if no file matches the search criteria. */
  private fun findFile(
      driveId: String,
      parentFolderId: String,
      name: String,
      fileId: FileId? = null,
      mimeType: String? = null,
  ): String? {
    val escapedName = name.replace("\\", "\\\\").replace("'", "\\'")

    val query =
        listOfNotNull(
            "trashed = false",
            "name = '$escapedName'",
            "'$parentFolderId' in parents",
            mimeType?.let { "mimeType = '$mimeType'" },
            fileId?.let { "properties has { key = '$terrawareFileIdProperty' and value='$it' }" },
        )

    val listRequest = driveClient.files().list()
    listRequest.corpora = "drive"
    listRequest.driveId = driveId
    listRequest.includeItemsFromAllDrives = true
    listRequest.supportsAllDrives = true
    listRequest.q = query.joinToString(" and ")

    val result = listRequest.execute()

    return result.files.firstOrNull()?.id
  }

  private fun findFolder(driveId: String, parentFolderId: String, name: String): String? {
    return findFile(driveId, parentFolderId, name, mimeType = folderMimeType)
  }

  private fun createFolder(driveId: String, parentFolderId: String, name: String): String {
    val file = File()
    file.driveId = driveId
    file.name = name
    file.mimeType = folderMimeType
    file.parents = listOf(parentFolderId)

    return driveClient.files().create(file).setSupportsAllDrives(true).setFields("id").execute().id
  }

  private fun findOrCreateFolder(driveId: String, parentFolderId: String, name: String): String {
    return findFolder(driveId, parentFolderId, name) ?: createFolder(driveId, parentFolderId, name)
  }
}
