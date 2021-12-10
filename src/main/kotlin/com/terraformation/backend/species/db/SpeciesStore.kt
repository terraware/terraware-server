package com.terraformation.backend.species.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.SpeciesNameNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.StoreSupport
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.pojos.SpeciesNamesRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.SPECIES_NAMES
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class SpeciesStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val speciesDao: SpeciesDao,
    private val speciesNamesDao: SpeciesNamesDao,
    private val support: StoreSupport
) {
  private val log = perClassLogger()

  fun getSpeciesId(speciesName: String): SpeciesId {
    return dslContext
        .select(SPECIES.ID)
        .from(SPECIES)
        .where(SPECIES.NAME.eq(speciesName))
        .fetchOne(SPECIES.ID)
        ?: createSpecies(SpeciesRow(name = speciesName))
  }

  fun countSpecies(asOf: TemporalAccessor): Int {
    return support.countEarlierThan(asOf, SPECIES.CREATED_TIME)
  }

  fun findAllSortedByName(): List<SpeciesRow> {
    return dslContext.selectFrom(SPECIES).orderBy(SPECIES.NAME).fetchInto(SpeciesRow::class.java)
  }

  fun createSpecies(row: SpeciesRow): SpeciesId {
    requirePermissions { createSpecies() }

    val newRow = row.copy(createdTime = clock.instant(), modifiedTime = clock.instant())
    speciesDao.insert(newRow)
    val speciesId = newRow.id!!

    speciesNamesDao.insert(
        SpeciesNamesRow(
            createdTime = clock.instant(),
            isScientific = row.isScientific,
            name = row.name,
            modifiedTime = clock.instant(),
            speciesId = speciesId))

    return speciesId
  }

  /**
   * Updates the data for an existing species.
   *
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(row: SpeciesRow) {
    val speciesId = row.id ?: throw IllegalArgumentException("No species ID specified")

    requirePermissions { updateSpecies(speciesId) }

    val existing = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    speciesDao.update(row.copy(createdTime = existing.createdTime, modifiedTime = clock.instant()))

    if (existing.name != row.name) {
      val existingNamesRowForNewName =
          dslContext
              .selectFrom(SPECIES_NAMES)
              .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId).and(SPECIES_NAMES.NAME.eq(row.name)))
              .fetchOneInto(SpeciesNamesRow::class.java)

      if (existingNamesRowForNewName != null) {
        // We are updating the primary name to a name that was previously added as a secondary.
        // Use the existing secondary name so its creation time is preserved.
        dslContext
            .deleteFrom(SPECIES_NAMES)
            .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId).and(SPECIES_NAMES.NAME.eq(existing.name)))
            .execute()

        if (existingNamesRowForNewName.isScientific != row.isScientific) {
          speciesNamesDao.update(
              existingNamesRowForNewName.copy(
                  isScientific = row.isScientific, modifiedTime = clock.instant()))
        }
      } else {
        val namesUpdated =
            dslContext
                .update(SPECIES_NAMES)
                .set(SPECIES_NAMES.IS_SCIENTIFIC, row.isScientific)
                .set(SPECIES_NAMES.NAME, row.name)
                .set(SPECIES_NAMES.MODIFIED_TIME, clock.instant())
                .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
                .and(SPECIES_NAMES.NAME.eq(existing.name))
                .execute()

        if (namesUpdated != 1) {
          log.error(
              "Renaming species ${existing.name} to ${row.name}: expected to update 1 name, but " +
                  "was $namesUpdated")
        }
      }
    }
  }

  /**
   * Deletes a species. This will fail if there are any entities referencing the species.
   *
   * @throws AccessDeniedException The user does not have permission to delete the species.
   * @throws DataIntegrityViolationException The species is still in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun deleteSpecies(speciesId: SpeciesId) {
    requirePermissions { deleteSpecies(speciesId) }

    val rowsDeleted =
        dslContext.transactionResult { _ ->
          dslContext
              .deleteFrom(SPECIES_NAMES)
              .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
              .execute()
          dslContext.deleteFrom(SPECIES).where(SPECIES.ID.eq(speciesId)).execute()
        }

    if (rowsDeleted != 1) {
      throw SpeciesNotFoundException(speciesId)
    }
  }

  /**
   * Creates a new name for an existing species.
   *
   * @throws AccessDeniedException The user does not have permission to create the name.
   * @throws DataIntegrityViolationException The species already has the requested name.
   * @throws SpeciesNotFoundException No species with the requested species ID exists.
   */
  fun createSpeciesName(row: SpeciesNamesRow): SpeciesNameId {
    val speciesId = row.speciesId ?: throw IllegalArgumentException("Species ID may not be null")
    requirePermissions { updateSpecies(speciesId) }

    val newRow = row.copy(id = null, createdTime = clock.instant(), modifiedTime = clock.instant())
    speciesNamesDao.insert(newRow)

    return newRow.id!!
  }

  /**
   * Deletes a species name. This cannot be used to delete the primary name.
   *
   * @throws DataIntegrityViolationException The requested name was the primary name of the species.
   * @throws SpeciesNameNotFoundException No species name with the requested ID exists.
   */
  fun deleteSpeciesName(speciesNameId: SpeciesNameId) {
    val existing =
        speciesNamesDao.fetchOneById(speciesNameId)
            ?: throw SpeciesNameNotFoundException(speciesNameId)
    val speciesId = existing.speciesId!!
    val species = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    requirePermissions { updateSpecies(speciesId) }

    if (existing.name == species.name) {
      throw DataIntegrityViolationException("Cannot delete primary name of a species")
    }

    speciesNamesDao.deleteById(speciesNameId)
  }

  /**
   * Returns a list of all the names of a species.
   *
   * @throws SpeciesNotFoundException The species does not exist.
   */
  fun listAllSpeciesNames(speciesId: SpeciesId): List<SpeciesNamesRow> {
    val rows =
        dslContext
            .selectFrom(SPECIES_NAMES)
            .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
            .orderBy(SPECIES_NAMES.NAME)
            .fetchInto(SpeciesNamesRow::class.java)

    // We don't allow deletion of the primary name of a species, so species should always have
    // at least one name.
    if (rows.isEmpty()) {
      throw SpeciesNotFoundException(speciesId)
    }

    return rows
  }

  /**
   * Updates one of the names of a species. If the name was the primary name, updates it both in the
   * `species_names` table and the `species` table.
   *
   * @throws SpeciesNameNotFoundException The species name does not exist.
   * @throws SpeciesNotFoundException The species does not exist (this may indicate a bug, since a
   * species name should not be able to exist if its species doesn't).
   * @throws DataIntegrityViolationException The species name was already in use.
   */
  fun updateSpeciesName(row: SpeciesNamesRow) {
    val speciesNameId = row.id ?: throw IllegalArgumentException("Species name ID may not be null")
    val existing =
        speciesNamesDao.fetchOneById(speciesNameId)
            ?: throw SpeciesNameNotFoundException(speciesNameId)
    val speciesId = existing.speciesId!!
    val species = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    requirePermissions { updateSpecies(speciesId) }

    dslContext.transaction { _ ->
      speciesNamesDao.update(
          row.copy(createdTime = existing.createdTime, modifiedTime = clock.instant()))

      if (existing.name == species.name) {
        speciesDao.update(
            species.copy(
                isScientific = row.isScientific, modifiedTime = clock.instant(), name = row.name))
      }
    }
  }
}
