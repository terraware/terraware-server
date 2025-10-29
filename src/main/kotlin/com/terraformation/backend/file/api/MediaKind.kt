package com.terraformation.backend.file.api

/**
 * Indicates what kind of media a media file is. This is a broad categorization for cases where it's
 * not appropriate to expose a more precise MIME type to clients.
 */
enum class MediaKind {
  Photo,
  Video;

  companion object {
    fun forMimeType(mimeType: String): MediaKind =
        when {
          mimeType.startsWith("image/") -> Photo
          mimeType.startsWith("video/") -> Video
          else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
  }
}
