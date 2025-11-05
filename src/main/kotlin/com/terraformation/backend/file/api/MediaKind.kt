package com.terraformation.backend.file.api

import com.terraformation.backend.db.LocalizableEnum
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Indicates what kind of media a media file is. This is a broad categorization for cases where it's
 * not appropriate to expose a more precise MIME type to clients.
 */
enum class MediaKind : LocalizableEnum<MediaKind> {
  Photo,
  Video;

  override val jsonValue: String
    get() = name

  override fun getDisplayName(locale: Locale?): String {
    val effectiveLocale = locale ?: Locale.ENGLISH
    val namesForLocale =
        displayNames.getOrPut(effectiveLocale) {
          LocalizableEnum.loadLocalizedDisplayNames(effectiveLocale, MediaKind.entries)
        }
    return namesForLocale[this]
        ?: throw IllegalStateException("No display name for MediaKind.$this in $effectiveLocale")
  }

  companion object {
    private val displayNames = ConcurrentHashMap<Locale, Map<MediaKind, String>>()

    fun forMimeType(mimeType: String): MediaKind =
        when {
          mimeType.startsWith("image/") -> Photo
          mimeType.startsWith("video/") -> Video
          else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
  }
}
