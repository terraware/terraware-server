package com.terraformation.backend.file

import java.io.IOException
import java.net.URI

/** A file store was asked to accept a URL that refers to some other storage location. */
class InvalidStorageLocationException(val uri: URI, message: String? = null) : IOException(message)

/** Thrown when the system is unable to read or process an uploaded file. */
class UploadFailedException(message: String, cause: Exception) : RuntimeException(message, cause) {
  constructor(cause: Exception) : this("Upload failed", cause)
}
