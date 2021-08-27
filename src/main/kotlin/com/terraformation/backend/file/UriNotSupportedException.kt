package com.terraformation.backend.file

import java.io.IOException
import java.net.URI

/** A file store was asked to accept a URL that refers to some other storage location. */
class InvalidStorageLocationException(private val uri: URI, message: String? = null) :
    IOException(message)
