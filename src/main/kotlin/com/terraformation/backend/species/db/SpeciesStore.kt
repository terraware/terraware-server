package com.terraformation.backend.species.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.SpeciesNameNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.daos.SpeciesOptionsDao
import com.terraformation.backend.db.tables.pojos.SpeciesNamesRow
import com.terraformation.backend.db.tables.pojos.SpeciesOptionsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.SPECIES_NAMES
import com.terraformation.backend.db.tables.references.SPECIES_OPTIONS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.toInstant
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class SpeciesStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val speciesDao: SpeciesDao,
    private val speciesNamesDao: SpeciesNamesDao,
    private val speciesOptionsDao: SpeciesOptionsDao,
) {
  private val log = perClassLogger()

  fun getOrCreateSpecies(organizationId: OrganizationId, speciesName: String): SpeciesId {
    requirePermissions { readOrganization(organizationId) }

    val existing =
        dslContext
            .select(SPECIES_NAMES.SPECIES_ID, SPECIES_OPTIONS.ORGANIZATION_ID)
            .from(SPECIES_NAMES)
            .leftJoin(SPECIES_OPTIONS)
            .on(SPECIES_NAMES.SPECIES_ID.eq(SPECIES_OPTIONS.SPECIES_ID))
            .and(SPECIES_NAMES.ORGANIZATION_ID.eq(SPECIES_OPTIONS.ORGANIZATION_ID))
            .where(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
            .and(SPECIES_NAMES.NAME.eq(speciesName))
            .fetchOne()
    val existingId = existing?.value1()
    val availableInOrganization = existing?.value2() != null

    if (existingId != null && !availableInOrganization) {
      // The species was created at one point, then deleted, and now is being requested again. Reuse
      // the existing species ID.
      speciesOptionsDao.insert(
          SpeciesOptionsRow(
              createdTime = clock.instant(),
              modifiedTime = clock.instant(),
              organizationId = organizationId,
              speciesId = existingId))
    }

    return existingId ?: createSpecies(organizationId, SpeciesRow(name = speciesName))
  }

  fun fetchSpeciesId(layerId: LayerId, speciesName: String): SpeciesId? {
    requirePermissions { readLayer(layerId) }

    return dslContext
        .select(SPECIES_NAMES.SPECIES_ID)
        .from(SPECIES_NAMES)
        .join(PROJECTS)
        .on(PROJECTS.ORGANIZATION_ID.eq(SPECIES_NAMES.ORGANIZATION_ID))
        .join(SITES)
        .on(SITES.PROJECT_ID.eq(PROJECTS.ID))
        .join(LAYERS)
        .on(LAYERS.SITE_ID.eq(SITES.ID))
        .where(LAYERS.ID.eq(layerId))
        .and(SPECIES_NAMES.NAME.eq(speciesName))
        .fetchOne(SPECIES_NAMES.SPECIES_ID)
  }

  fun fetchSpeciesById(organizationId: OrganizationId, speciesId: SpeciesId): SpeciesRow? {
    requirePermissions { readSpecies(organizationId, speciesId) }

    return dslContext
        .selectFrom(SPECIES)
        .where(SPECIES.ID.eq(speciesId))
        .andExists(
            DSL.selectOne()
                .from(SPECIES_OPTIONS)
                .where(SPECIES_OPTIONS.SPECIES_ID.eq(SPECIES.ID))
                .and(SPECIES_OPTIONS.ORGANIZATION_ID.eq(organizationId)))
        .fetchOneInto(SpeciesRow::class.java)
  }

  fun fetchSpeciesNameById(speciesNameId: SpeciesNameId): SpeciesNamesRow? {
    requirePermissions { readSpeciesName(speciesNameId) }

    return dslContext
        .selectFrom(SPECIES_NAMES)
        .where(SPECIES_NAMES.ID.eq(speciesNameId))
        .andExists(
            DSL.selectOne()
                .from(SPECIES_OPTIONS)
                .where(SPECIES_OPTIONS.SPECIES_ID.eq(SPECIES_NAMES.SPECIES_ID))
                .and(SPECIES_OPTIONS.ORGANIZATION_ID.eq(SPECIES_NAMES.ORGANIZATION_ID)))
        .fetchOneInto(SpeciesNamesRow::class.java)
  }

  fun countSpecies(organizationId: OrganizationId, asOf: TemporalAccessor): Int {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(DSL.count())
        .from(SPECIES)
        .where(SPECIES.CREATED_TIME.le(asOf.toInstant()))
        .andExists(
            DSL.selectOne()
                .from(SPECIES_OPTIONS)
                .where(SPECIES_OPTIONS.SPECIES_ID.eq(SPECIES.ID))
                .and(SPECIES_OPTIONS.ORGANIZATION_ID.eq(organizationId)))
        .fetchOne()
        ?.value1()
        ?: 0
  }

  fun findAllSpecies(organizationId: OrganizationId): List<SpeciesRow> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .selectFrom(SPECIES)
        .where(
            SPECIES.ID.`in`(
                DSL.select(SPECIES_OPTIONS.SPECIES_ID)
                    .from(SPECIES_OPTIONS)
                    .where(SPECIES_OPTIONS.ORGANIZATION_ID.eq(organizationId))))
        .orderBy(SPECIES.ID)
        .fetchInto(SpeciesRow::class.java)
  }

  fun findAllSpeciesNames(organizationId: OrganizationId): List<SpeciesNamesRow> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .selectFrom(SPECIES_NAMES)
        .where(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
        .andExists(
            DSL.selectOne()
                .from(SPECIES_OPTIONS)
                .where(SPECIES_OPTIONS.SPECIES_ID.eq(SPECIES_NAMES.SPECIES_ID))
                .and(SPECIES_OPTIONS.ORGANIZATION_ID.eq(organizationId)))
        .orderBy(SPECIES_NAMES.SPECIES_ID, SPECIES_NAMES.NAME)
        .fetchInto(SpeciesNamesRow::class.java)
  }

  fun createSpecies(organizationId: OrganizationId, row: SpeciesRow): SpeciesId {
    requirePermissions { createSpecies(organizationId) }

    val existingEntry =
        dslContext
            .select(SPECIES_NAMES.SPECIES_ID, SPECIES_OPTIONS.ORGANIZATION_ID)
            .from(SPECIES_NAMES)
            .leftJoin(SPECIES_OPTIONS)
            .on(SPECIES_NAMES.SPECIES_ID.eq(SPECIES_OPTIONS.SPECIES_ID))
            .and(SPECIES_NAMES.ORGANIZATION_ID.eq(SPECIES_OPTIONS.ORGANIZATION_ID))
            .where(SPECIES_NAMES.NAME.eq(row.name))
            .and(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
            .fetchOne()
    val existingSpeciesId = existingEntry?.value1()
    val optionExists = existingEntry?.value2() != null

    if (existingSpeciesId != null && optionExists) {
      throw DuplicateKeyException("Species name already exists")
    }

    return dslContext.transactionResult { _ ->
      val speciesId =
          if (existingSpeciesId != null) {
            existingSpeciesId
          } else {
            val newRow = row.copy(createdTime = clock.instant(), modifiedTime = clock.instant())
            speciesDao.insert(newRow)
            newRow.id ?: throw RuntimeException("BUG! Species ID not returned after insertion")
          }

      if (existingSpeciesId == null) {
        speciesNamesDao.insert(
            SpeciesNamesRow(
                createdTime = clock.instant(),
                isScientific = row.isScientific,
                name = row.name,
                modifiedTime = clock.instant(),
                organizationId = organizationId,
                speciesId = speciesId))
      }

      speciesOptionsDao.insert(
          SpeciesOptionsRow(
              createdTime = clock.instant(),
              modifiedTime = clock.instant(),
              organizationId = organizationId,
              speciesId = speciesId))

      speciesId
    }
  }

  /**
   * Updates the data for an existing species.
   *
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(organizationId: OrganizationId, row: SpeciesRow) {
    val speciesId = row.id ?: throw IllegalArgumentException("No species ID specified")

    requirePermissions { updateSpecies(organizationId, speciesId) }

    val existing = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    speciesDao.update(row.copy(createdTime = existing.createdTime, modifiedTime = clock.instant()))

    if (existing.name != row.name) {
      val existingNamesRowForNewName =
          dslContext
              .selectFrom(SPECIES_NAMES)
              .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
              .and(SPECIES_NAMES.NAME.eq(row.name))
              .and(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
              .fetchOneInto(SpeciesNamesRow::class.java)

      if (existingNamesRowForNewName != null) {
        // We are updating the primary name to a name that was previously added as a secondary.
        // Use the existing secondary name so its creation time is preserved.
        dslContext
            .deleteFrom(SPECIES_NAMES)
            .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
            .and(SPECIES_NAMES.NAME.eq(existing.name))
            .and(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
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
                .and(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
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
   * Deletes a species from an organization. This doesn't remove any existing references to the
   * species, just prevents it from being used in the future.
   *
   * @throws AccessDeniedException The user does not have permission to delete the species.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun deleteSpecies(organizationId: OrganizationId, speciesId: SpeciesId) {
    requirePermissions { deleteSpecies(organizationId, speciesId) }

    val rowsDeleted =
        dslContext.transactionResult { _ ->
          dslContext
              .deleteFrom(SPECIES_OPTIONS)
              .where(SPECIES_OPTIONS.SPECIES_ID.eq(speciesId))
              .and(SPECIES_OPTIONS.ORGANIZATION_ID.eq(organizationId))
              .execute()
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
    val organizationId =
        row.organizationId ?: throw IllegalArgumentException("Organization ID may not be null")
    if (row.speciesId == null) {
      throw IllegalArgumentException("Species ID may not be null")
    }

    requirePermissions { createSpeciesName(organizationId) }

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
    requirePermissions { deleteSpeciesName(speciesNameId) }

    val existing =
        speciesNamesDao.fetchOneById(speciesNameId)
            ?: throw SpeciesNameNotFoundException(speciesNameId)
    val speciesId = existing.speciesId!!
    val species = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    if (existing.name == species.name) {
      throw DataIntegrityViolationException("Cannot delete primary name of a species")
    }

    speciesNamesDao.deleteById(speciesNameId)
  }

  /**
   * Returns a list of all the names of a species at an organization.
   *
   * @throws SpeciesNotFoundException The species does not exist.
   */
  fun listAllSpeciesNames(
      organizationId: OrganizationId,
      speciesId: SpeciesId
  ): List<SpeciesNamesRow> {
    requirePermissions { readSpecies(organizationId, speciesId) }

    val rows =
        dslContext
            .selectFrom(SPECIES_NAMES)
            .where(SPECIES_NAMES.SPECIES_ID.eq(speciesId))
            .and(SPECIES_NAMES.ORGANIZATION_ID.eq(organizationId))
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

    requirePermissions { updateSpeciesName(speciesNameId) }

    val existingNamesRow =
        speciesNamesDao.fetchOneById(speciesNameId)
            ?: throw SpeciesNameNotFoundException(speciesNameId)
    val speciesId = existingNamesRow.speciesId!!
    val existingSpeciesRow =
        speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    dslContext.transaction { _ ->
      speciesNamesDao.update(
          existingNamesRow.copy(
              isScientific = row.isScientific,
              locale = row.locale,
              modifiedTime = clock.instant(),
              name = row.name))

      if (existingNamesRow.name == existingSpeciesRow.name) {
        speciesDao.update(
            existingSpeciesRow.copy(
                isScientific = row.isScientific, modifiedTime = clock.instant(), name = row.name))
      }
    }
  }
}
