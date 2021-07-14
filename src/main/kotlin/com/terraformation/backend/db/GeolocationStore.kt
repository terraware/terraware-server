package com.terraformation.backend.db

import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.model.Geolocation
import java.math.BigDecimal
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class GeolocationStore(private val dslContext: DSLContext, private val clock: Clock) {
  fun fetchGeolocations(accessionId: Long): Set<Geolocation> {
    return dslContext
        .selectFrom(GEOLOCATIONS)
        .where(GEOLOCATIONS.ACCESSION_ID.eq(accessionId))
        .orderBy(GEOLOCATIONS.LATITUDE, GEOLOCATIONS.LONGITUDE)
        .fetch { record ->
          Geolocation(
              record[GEOLOCATIONS.LATITUDE]!!,
              record[GEOLOCATIONS.LONGITUDE]!!,
              record[GEOLOCATIONS.GPS_ACCURACY]?.let { BigDecimal(it) },
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

      with(GEOLOCATIONS) {
        if (deleted.isNotEmpty()) {
          dslContext
              .deleteFrom(GEOLOCATIONS)
              .where(ACCESSION_ID.eq(accessionId))
              .and(
                  DSL.row(LATITUDE, LONGITUDE)
                      .`in`(deleted.map { DSL.row(it.latitude, it.longitude) }))
              .execute()
        }

        added.forEach { geolocation ->
          dslContext
              .insertInto(
                  GEOLOCATIONS, ACCESSION_ID, CREATED_TIME, LATITUDE, LONGITUDE, GPS_ACCURACY)
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
