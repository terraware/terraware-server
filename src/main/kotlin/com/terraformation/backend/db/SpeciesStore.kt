package com.terraformation.backend.db

import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.db.tables.references.SPECIES
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

  fun getSpeciesId(speciesName: String?): SpeciesId? {
    return support.getOrInsertId(speciesName, SPECIES.ID, SPECIES.SCIENTIFIC_NAME) {
      it.set(SPECIES.CREATED_TIME, clock.instant())
      it.set(SPECIES.MODIFIED_TIME, clock.instant())
    }
  }

  fun getFamilyId(familyName: String?): FamilyId? {
    return support.getOrInsertId(familyName, FAMILIES.ID, FAMILIES.SCIENTIFIC_NAME) {
      it.set(FAMILIES.CREATED_TIME, clock.instant())
      it.set(FAMILIES.MODIFIED_TIME, clock.instant())
    }
  }

  fun countSpecies(asOf: TemporalAccessor): Int {
    return support.countEarlierThan(asOf, SPECIES.CREATED_TIME)
  }

  fun countFamilies(asOf: TemporalAccessor): Int {
    return support.countEarlierThan(asOf, FAMILIES.CREATED_TIME)
  }

  fun findAllSortedByName(): List<SpeciesRow> {
    return dslContext
        .selectFrom(SPECIES)
        .orderBy(SPECIES.SCIENTIFIC_NAME)
        .fetchInto(SpeciesRow::class.java)
  }

  fun createSpecies(speciesName: String): SpeciesRow {
    return dslContext
            .insertInto(SPECIES)
            .set(SPECIES.SCIENTIFIC_NAME, speciesName)
            .set(SPECIES.CREATED_TIME, clock.instant())
            .set(SPECIES.MODIFIED_TIME, clock.instant())
            .returning()
            .fetchOne()!!
        .into(SpeciesRow::class.java)
  }

  /**
   * Updates the data for an existing species.
   *
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(speciesId: SpeciesId, name: String) {
    val rowsUpdated =
        dslContext
            .update(SPECIES)
            .set(SPECIES.SCIENTIFIC_NAME, name)
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
  fun deleteSpecies(speciesId: SpeciesId) {
    val rowsDeleted = dslContext.deleteFrom(SPECIES).where(SPECIES.ID.eq(speciesId)).execute()
    if (rowsDeleted != 1) {
      throw SpeciesNotFoundException(speciesId)
    }
  }
}
