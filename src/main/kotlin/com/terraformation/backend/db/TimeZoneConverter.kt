package com.terraformation.backend.db

import java.time.DateTimeException
import java.time.ZoneId
import org.jooq.impl.AbstractConverter

/**
 * Converts text values from the database to and from ZoneId objects. This is for type safety, so we
 * don't treat arbitrary string values as zone IDs.
 *
 * This is referenced in generated database classes.
 */
class TimeZoneConverter :
    AbstractConverter<String, ZoneId>(String::class.java, ZoneId::class.java) {
  /**
   * Converts a time zone name to a ZoneId.
   *
   * @throws DateTimeException The zone name wasn't found in the list of zones recognized by the
   * java.time package.
   */
  override fun from(databaseObject: String?): ZoneId? = databaseObject?.let { ZoneId.of(it) }

  override fun to(userObject: ZoneId?): String? = userObject?.id
}
