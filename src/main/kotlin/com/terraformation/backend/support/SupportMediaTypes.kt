package com.terraformation.backend.support

import org.springframework.http.MediaType

private const val IMAGE_MEDIA_TYPE = "image"
private const val VIDEO_MEDIA_TYPE = "video"

val SUPPORTED_CONTENT_TYPES = setOf(IMAGE_MEDIA_TYPE, VIDEO_MEDIA_TYPE)
const val SUPPORTED_CONTENT_TYPES_STRING = "$IMAGE_MEDIA_TYPE/*, $VIDEO_MEDIA_TYPE/*"

fun isContentTypeSupported(contentType: MediaType): Boolean {
  return SUPPORTED_CONTENT_TYPES.contains(contentType.type)
}
