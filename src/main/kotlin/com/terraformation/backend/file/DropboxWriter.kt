package com.terraformation.backend.file

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.common.PathRoot
import com.dropbox.core.v2.files.WriteMode
import com.dropbox.core.v2.sharing.CreateSharedLinkWithSettingsError
import com.dropbox.core.v2.sharing.CreateSharedLinkWithSettingsErrorException
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import java.net.URI

/**
 * Writes files to Dropbox. API credentials must be configured in [TerrawareServerConfig]. The
 * following Dropbox permissions are required:
 * - files.metadata.write
 * - files.content.write
 * - sharing.read
 * - sharing.write
 */
@Named
class DropboxWriter(
    private val config: TerrawareServerConfig,
) {
  private val log = perClassLogger()

  private val dbxClient: DbxClientV2 by lazy { createClient() }

  /**
   * Uploads a file to Dropbox at a specified path. If there is already a file with the same path,
   * allows Dropbox to pick a different filename. If the folder doesn't exist yet, it is created
   * automatically.
   *
   * @return The actual filename that was created on Dropbox.
   */
  fun uploadFile(folderPath: String, name: String, inputStream: InputStream): String {
    val metadata =
        dbxClient
            .files()
            .uploadBuilder("$folderPath/$name")
            .withMode(WriteMode.ADD)
            .withAutorename(true)
            .withStrictConflict(true)
            .uploadAndFinish(inputStream)

    log.info("Uploaded file ${metadata.pathDisplay}")

    return metadata.name
  }

  /** Creates a folder. Any missing parent folders will also be created. */
  fun createFolder(path: String) {
    dbxClient.files().createFolderV2(path)

    log.info("Created folder $path")
  }

  fun rename(oldPath: String, newPath: String) {
    dbxClient.files().moveV2(oldPath, newPath)
  }

  /**
   * Deletes a file or folder. If [path] is a folder, all the files in the folder are deleted too.
   */
  fun delete(path: String) {
    dbxClient.files().deleteV2(path)

    log.info("Deleted file/folder $path")
  }

  fun shareFile(path: String): URI {
    val url =
        try {
          dbxClient.sharing().createSharedLinkWithSettings(path).url
        } catch (e: CreateSharedLinkWithSettingsErrorException) {
          if (
              e.errorValue.tag() == CreateSharedLinkWithSettingsError.Tag.SHARED_LINK_ALREADY_EXISTS
          ) {
            e.errorValue.sharedLinkAlreadyExistsValue.metadataValue.url
          } else {
            throw e
          }
        }

    return URI.create(url)
  }

  private fun createClient(): DbxClientV2 {
    val dbxConfig = DbxRequestConfig.newBuilder(config.dropbox.clientId).build()
    val credential =
        DbxCredential(
            // Access token; must be non-null but won't be used.
            "",
            // Access token expiration time; we set this to 0 to force a new access token to be
            // generated from the refresh token.
            0,
            config.dropbox.refreshToken,
            config.dropbox.appKey,
            config.dropbox.appSecret,
        )

    val client = DbxClientV2(dbxConfig, credential)

    // We want paths to be relative to the account's root folder, not its home folder, so that
    // team folders are addressable. Without this setting, the path "/foo" will actually point to
    // "/(username)/foo" and the files won't be accessible by other users on the team.
    val rootNamespaceId = client.users().currentAccount.rootInfo.rootNamespaceId

    return client.withPathRoot(PathRoot.root(rootNamespaceId))
  }
}
