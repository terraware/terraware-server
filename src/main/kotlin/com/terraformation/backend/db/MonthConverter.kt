package com.terraformation.backend.db

import java.time.DateTimeException
import java.time.Month
import org.jooq.impl.AbstractConverter

/**
 * Converts integer values from the database to and from Month objects.
 *
 * This is referenced in generated database classes.
 */
class MonthConverter : AbstractConverter<Int, Month>(Int::class.java, Month::class.java) {
  /**
   * Converts a month number (1-12) to a Month.
   *
   * @throws DateTimeException The value was not between 1 and 12.
   */
  override fun from(databaseObject: Int?): Month? = databaseObject?.let { Month.of(it) }

  override fun to(month: Month?): Int? = month?.value
}
