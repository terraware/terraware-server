package com.terraformation.backend.db

import java.time.DateTimeException
import java.util.Locale
import org.jooq.impl.AbstractConverter

/**
 * Converts text values from the database to and from Locale objects.
 *
 * This is referenced in generated database classes.
 */
class LocaleConverter : AbstractConverter<String, Locale>(String::class.java, Locale::class.java) {
  /**
   * Converts a time zone name to a ZoneId.
   *
   * @throws DateTimeException The zone name wasn't found in the list of zones recognized by the
   *   java.time package.
   */
  override fun from(databaseObject: String?): Locale? =
      databaseObject?.let { Locale.forLanguageTag(it) }

  override fun to(userObject: Locale?): String? = userObject?.toLanguageTag()
}
