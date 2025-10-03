package com.terraformation.backend.file

import com.terraformation.backend.db.default_schema.FileId

interface JpegConverter {
  /**
   * Returns true if this converter can produce a JPEG version of a file with the given MIME type.
   */
  fun canConvertToJpeg(mimeType: String): Boolean

  /** Converts the specified file to JPEG and returns the JPEG contents. */
  fun convertToJpeg(fileId: FileId): ByteArray
}
