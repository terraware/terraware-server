package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.db.tables.references.COLLECTION_EVENT
import com.terraformation.seedbank.services.toSetOrNull
import java.math.BigDecimal
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class CollectionEventFetcher(private val dslContext: DSLContext, private val clock: Clock) {
  fun fetchGeolocations(accessionId: Long): Set<Geolocation>? {
    return dslContext
        .selectFrom(COLLECTION_EVENT)
        .where(COLLECTION_EVENT.ACCESSION_ID.eq(accessionId))
        .orderBy(COLLECTION_EVENT.LATITUDE, COLLECTION_EVENT.LONGITUDE)
        .fetch { record ->
          Geolocation(
              record[COLLECTION_EVENT.LATITUDE]!!,
              record[COLLECTION_EVENT.LONGITUDE]!!,
              record[COLLECTION_EVENT.GPS_ACCURACY]?.let { BigDecimal(it) },
          )
        }
        .toSetOrNull()
  }

  fun updateGeolocations(
      accessionId: Long,
      existingGeolocations: Set<Geolocation>?,
      desiredGeolocations: Set<Geolocation>?
  ) {
    if (existingGeolocations != desiredGeolocations) {
      val existing = existingGeolocations ?: emptySet()
      val desired = desiredGeolocations ?: emptySet()
      val deleted = existing.minus(desired)
      val added = desired.minus(existing)

      with(COLLECTION_EVENT) {
        if (deleted.isNotEmpty()) {
          dslContext
              .deleteFrom(COLLECTION_EVENT)
              .where(ACCESSION_ID.eq(accessionId))
              .and(
                  DSL.row(LATITUDE, LONGITUDE)
                      .`in`(deleted.map { DSL.row(it.latitude, it.longitude) }))
              .execute()
        }

        added.forEach { geolocation ->
          dslContext
              .insertInto(
                  COLLECTION_EVENT, ACCESSION_ID, CREATED_TIME, LATITUDE, LONGITUDE, GPS_ACCURACY)
              .values(
                  accessionId,
                  clock.instant(),
                  geolocation.latitude,
                  geolocation.longitude,
                  geolocation.accuracy?.toDouble())
              .execute()
        }
      }
    }
  }
}
