package com.terraformation.backend.file

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
    val serviceAccountCredentials =
        GoogleCredentials.getApplicationDefault()
            .createScoped(listOf("https://www.googleapis.com/auth/drive"))

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
            HttpCredentialsAdapter(credentials))
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
      driveId: String,
      parentFolderId: String,
      filename: String,
      contentType: String,
      inputStream: InputStream,
      description: String? = null,
      fileId: FileId? = null,
      inputStreamContentType: String = contentType,
  ): String {
    val existingFile = findFile(driveId, parentFolderId, filename, fileId)

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
          driveId = driveId,
          parentFolderId = parentFolderId,
          filename = name,
          contentType = metadata.contentType,
          inputStream = inputStream,
          description = description,
          fileId = metadata.id)
    }
  }

  /** Returns the ID of an existing file, or null if no file matches the search criteria. */
  private fun findFile(
      driveId: String,
      parentFolderId: String,
      name: String,
      fileId: FileId? = null,
      mimeType: String? = null
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
