package com.terraformation.backend.file

import org.springframework.http.MediaType

const val MEDIA_TYPE_HEIC = "image/heic"

const val MEDIA_TYPE_SVG = "image/svg+xml"

/**
 * List of supported photo content types. We only support content types that are compatible with our
 * thumbnail generator.
 */
val SUPPORTED_PHOTO_TYPES =
    setOf(MediaType.valueOf(MEDIA_TYPE_HEIC), MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG)

/**
 * List of supported audiovisual media content types. We delegate video handling to Mux, which
 * supports all the widely-used video formats.
 */
val SUPPORTED_MEDIA_TYPES = SUPPORTED_PHOTO_TYPES + setOf(MediaType.valueOf("video/*"))

/**
 * List of supported content types for additional media associated with an organization or an
 * observation.
 */
val SUPPORTED_ADDITIONAL_MEDIA_TYPES = SUPPORTED_MEDIA_TYPES + setOf(MediaType.APPLICATION_JSON)
