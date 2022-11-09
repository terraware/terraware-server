package com.terraformation.backend.api

import java.io.InputStreamReader
import org.apache.commons.fileupload.FileItemStream

/** Reads an item of a streaming file-upload form submission as a string. */
fun FileItemStream.readString(): String {
  return openStream().use { InputStreamReader(it).readText() }
}
