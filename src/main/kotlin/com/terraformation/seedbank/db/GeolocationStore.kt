package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.GEOLOCATION
import com.terraformation.seedbank.model.Geolocation
import java.math.BigDecimal
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class GeolocationStore(private val dslContext: DSLContext, private val clock: Clock) {
  fun fetchGeolocations(accessionId: Long): Set<Geolocation> {
    return dslContext
        .selectFrom(GEOLOCATION)
        .where(GEOLOCATION.ACCESSION_ID.eq(accessionId))
        .orderBy(GEOLOCATION.LATITUDE, GEOLOCATION.LONGITUDE)
        .fetch { record ->
          Geolocation(
              record[GEOLOCATION.LATITUDE]!!,
              record[GEOLOCATION.LONGITUDE]!!,
              record[GEOLOCATION.GPS_ACCURACY]?.let { BigDecimal(it) },
          )
        }
        .toSet()
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

      with(GEOLOCATION) {
        if (deleted.isNotEmpty()) {
          dslContext
              .deleteFrom(GEOLOCATION)
              .where(ACCESSION_ID.eq(accessionId))
              .and(
                  DSL.row(LATITUDE, LONGITUDE)
                      .`in`(deleted.map { DSL.row(it.latitude, it.longitude) }))
              .execute()
        }

        added.forEach { geolocation ->
          dslContext
              .insertInto(
                  GEOLOCATION, ACCESSION_ID, CREATED_TIME, LATITUDE, LONGITUDE, GPS_ACCURACY)
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
