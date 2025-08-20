package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.GEOLOCATIONS
import com.terraformation.backend.seedbank.model.Geolocation
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class GeolocationStore(private val dslContext: DSLContext, private val clock: Clock) {
  fun geolocationsMultiset(idField: Field<AccessionId?> = ACCESSIONS.ID): Field<Set<Geolocation>> {
    return DSL.multiset(
            DSL.selectFrom(GEOLOCATIONS)
                .where(GEOLOCATIONS.ACCESSION_ID.eq(idField))
                .orderBy(GEOLOCATIONS.LATITUDE, GEOLOCATIONS.LONGITUDE)
        )
        .convertFrom { result ->
          result
              .map { record ->
                Geolocation(
                    record[GEOLOCATIONS.LATITUDE]!!,
                    record[GEOLOCATIONS.LONGITUDE]!!,
                    record[GEOLOCATIONS.GPS_ACCURACY]?.let { BigDecimal(it) },
                )
              }
              .toSet()
        }
  }

  fun updateGeolocations(
      accessionId: AccessionId,
      existingGeolocations: Set<Geolocation>?,
      desiredGeolocations: Set<Geolocation>?,
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
                      .`in`(deleted.map { DSL.row(it.latitude, it.longitude) })
              )
              .execute()
        }

        added.forEach { geolocation ->
          dslContext
              .insertInto(
                  GEOLOCATIONS,
                  ACCESSION_ID,
                  CREATED_TIME,
                  LATITUDE,
                  LONGITUDE,
                  GPS_ACCURACY,
              )
              .values(
                  accessionId,
                  clock.instant(),
                  geolocation.latitude,
                  geolocation.longitude,
                  geolocation.accuracy?.toDouble(),
              )
              .execute()
        }
      }
    }
  }
}
