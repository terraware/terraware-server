package com.terraformation.backend.file

/** Thrown when the system is unable to read or process an uploaded file. */
class UploadFailedException(message: String, cause: Exception) : RuntimeException(message, cause) {
  constructor(cause: Exception) : this("Upload failed", cause)
}
