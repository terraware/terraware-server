package com.terraformation.backend.file

import org.springframework.http.MediaType

/**
 * List of supported photo content types. We only support content types that are compatible with our
 * thumbnail generator.
 */
val SUPPORTED_PHOTO_TYPES =
    setOf(MediaType.valueOf("image/heic"), MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)

val SUPPORTED_VIDEO_TYPES = setOf(MediaType.valueOf("video/*"))
