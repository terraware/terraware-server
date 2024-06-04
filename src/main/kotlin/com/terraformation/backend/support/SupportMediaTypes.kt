package com.terraformation.backend.support

private const val IMAGE_MEDIA_TYPE_PREFIX = "image"
private const val VIDEO_MEDIA_TYPE_PREFIX = "video"

const val SUPPORTED_CONTENT_TYPES_STRING = "$IMAGE_MEDIA_TYPE_PREFIX/*, $VIDEO_MEDIA_TYPE_PREFIX/*"

fun isContentTypeSupported(plainContentType: String): Boolean {
  return plainContentType.startsWith(IMAGE_MEDIA_TYPE_PREFIX) ||
      plainContentType.startsWith(VIDEO_MEDIA_TYPE_PREFIX)
}
