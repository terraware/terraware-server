package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean

@ManagedBean
class SpeciesFetcher(private val clock: Clock, private val support: FetcherSupport) {

  fun getSpeciesId(speciesName: String?): Long? {
    return support.getOrInsertId(speciesName, SPECIES.ID, SPECIES.NAME) {
      it.set(SPECIES.CREATED_TIME, clock.instant())
      it.set(SPECIES.MODIFIED_TIME, clock.instant())
    }
  }

  fun getSpeciesFamilyId(familyName: String?): Long? {
    return support.getOrInsertId(familyName, SPECIES_FAMILY.ID, SPECIES_FAMILY.NAME) {
      it.set(SPECIES_FAMILY.CREATED_TIME, clock.instant())
    }
  }

  fun countSpecies(asOf: TemporalAccessor): Int {
    return support.countEarlierThan(asOf, SPECIES.CREATED_TIME)
  }

  fun countFamilies(asOf: TemporalAccessor): Int {
    return support.countEarlierThan(asOf, SPECIES_FAMILY.CREATED_TIME)
  }
}
