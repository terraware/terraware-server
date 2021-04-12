package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.pojos.Species
import com.terraformation.seedbank.db.tables.references.SPECIES
import com.terraformation.seedbank.db.tables.references.SPECIES_FAMILY
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class SpeciesStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val support: StoreSupport
) {

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

  fun findAllSortedByName(): List<Species> {
    return dslContext.selectFrom(SPECIES).orderBy(SPECIES.NAME).fetchInto(Species::class.java)
  }

  fun createSpecies(speciesName: String): Species {
    return dslContext
            .insertInto(SPECIES)
            .set(SPECIES.NAME, speciesName)
            .set(SPECIES.CREATED_TIME, clock.instant())
            .set(SPECIES.MODIFIED_TIME, clock.instant())
            .returning()
            .fetchOne()!!
        .into(Species::class.java)
  }

  /**
   * Updates the data for an existing species.
   *
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(speciesId: Long, name: String) {
    val rowsUpdated =
        dslContext
            .update(SPECIES)
            .set(SPECIES.NAME, name)
            .set(SPECIES.MODIFIED_TIME, clock.instant())
            .where(SPECIES.ID.eq(speciesId))
            .execute()
    if (rowsUpdated != 1) {
      throw SpeciesNotFoundException(speciesId)
    }
  }

  /**
   * Deletes a species. This will fail if there are any accessions referencing the species.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException The species is still in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun deleteSpecies(speciesId: Long) {
    val rowsDeleted = dslContext.deleteFrom(SPECIES).where(SPECIES.ID.eq(speciesId)).execute()
    if (rowsDeleted != 1) {
      throw SpeciesNotFoundException(speciesId)
    }
  }
}
