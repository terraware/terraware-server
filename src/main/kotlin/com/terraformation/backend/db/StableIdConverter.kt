package com.terraformation.backend.db

import org.jooq.impl.AbstractConverter

/**
 * Converts text values from the database to and from StableId objects.
 *
 * This is referenced in generated database classes.
 */
class StableIdConverter :
    AbstractConverter<String, StableId>(String::class.java, StableId::class.java) {
  override fun from(databaseObject: String?): StableId? = databaseObject?.let { StableId(it) }

  override fun to(stableId: StableId?): String? = stableId?.value
}
