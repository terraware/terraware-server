package com.terraformation.backend.api

/**
 * Description of maxWidth and maxHeight parameters on photo endpoints. This is suitable for use in
 * the description value of an `@Operation` annotation on a photo endpoint.
 */
const val PHOTO_OPERATION_DESCRIPTION =
    "Optional maxWidth and maxHeight parameters may be included to control the dimensions of the " +
        "image; the server will scale the original down as needed. If neither parameter is " +
        "specified, the original full-size image will be returned. The aspect ratio of the " +
        "original image is maintained, so the returned image may be smaller than the requested " +
        "width and height. If only maxWidth or only maxHeight is supplied, the other dimension " +
        "will be computed based on the original image's aspect ratio."

/** Description of the maxWidth parameter on a photo GET endpoint. */
const val PHOTO_MAXWIDTH_DESCRIPTION =
    "Maximum desired width in pixels. If neither this nor maxHeight is specified, the full-sized " +
        "original image will be returned. If this is specified, an image no wider than this will " +
        "be returned. The image may be narrower than this value if needed to preserve the aspect " +
        "ratio of the original."

/** Description of the maxHeight parameter on a photo GET endpoint. */
const val PHOTO_MAXHEIGHT_DESCRIPTION =
    "Maximum desired height in pixels. If neither this nor maxWidth is specified, the full-sized " +
        "original image will be returned. If this is specified, an image no taller than this " +
        "will be returned. The image may be shorter than this value if needed to preserve the " +
        "aspect ratio of the original."
