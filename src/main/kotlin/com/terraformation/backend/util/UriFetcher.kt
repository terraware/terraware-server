package com.terraformation.backend.util

import jakarta.inject.Named
import java.io.InputStream
import java.net.URI

@Named
class UriFetcher {
  fun openStream(uri: URI): InputStream = uri.toURL().openStream()
}
