package com.terraformation.backend.documentproducer.db

import com.terraformation.backend.documentproducer.model.StableId
import java.time.DateTimeException
import org.jooq.impl.AbstractConverter

/**
 * Converts text values from the database to and from StableId objects.
 *
 * This is referenced in generated database classes.
 */
class StableIdConverter :
    AbstractConverter<String, StableId>(String::class.java, StableId::class.java) {
  /**
   * Converts a t.
   *
   * @throws DateTimeException The zone name wasn't found in the list of zones recognized by the
   *   java.time package.
   */
  override fun from(databaseObject: String?): StableId? = databaseObject?.let { StableId(it) }

  override fun to(stableId: StableId?): String? = stableId?.value
}
