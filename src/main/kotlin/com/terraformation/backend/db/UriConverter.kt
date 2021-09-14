package com.terraformation.backend.db

import java.net.URI
import org.jooq.impl.AbstractConverter

/**
 * Converts text values from the database to and from URI objects. This is for type safety, so we
 * don't treat arbitrary string values as URIs.
 *
 * This is referenced in generated database classes.
 */
class UriConverter : AbstractConverter<String, URI>(String::class.java, URI::class.java) {
  override fun from(databaseObject: String?): URI? = databaseObject?.let { URI(it) }
  override fun to(userObject: URI?): String? = userObject?.let { "$it" }
}
