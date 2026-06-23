package com.terraformation.backend.util

import jakarta.inject.Named
import java.io.InputStream
import java.net.URI

/**
 * Fetches data from remote services. This is a simple wrapper around the built-in URL.openStream
 * method; it's exposed as a Spring bean so that tests can stub out network access without having to
 * resort to reflection trickery.
 */
@Named
class UriFetcher {
  fun openStream(uri: URI): InputStream = uri.toURL().openStream()
}
