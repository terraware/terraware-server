package com.terraformation.backend.species.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.toInstant
import java.time.Clock
import java.time.temporal.TemporalAccessor
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class SpeciesStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val speciesDao: SpeciesDao,
) {
  private val log = perClassLogger()

  fun getOrCreateSpecies(organizationId: OrganizationId, scientificName: String): SpeciesId {
    requirePermissions { readOrganization(organizationId) }

    val existing =
        dslContext
            .select(SPECIES.ID, SPECIES.DELETED_TIME)
            .from(SPECIES)
            .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
            .and(SPECIES.SCIENTIFIC_NAME.eq(scientificName))
            .fetchOne()
    val existingId = existing?.value1()
    val availableInOrganization = existing?.value2() == null

    return if (existingId != null && availableInOrganization) {
      existingId
    } else {
      createSpecies(SpeciesRow(organizationId = organizationId, scientificName = scientificName))
    }
  }

  fun fetchSpeciesById(speciesId: SpeciesId): SpeciesRow? {
    requirePermissions { readSpecies(speciesId) }

    return dslContext
        .selectFrom(SPECIES)
        .where(SPECIES.ID.eq(speciesId))
        .and(SPECIES.DELETED_TIME.isNull)
        .fetchOneInto(SpeciesRow::class.java)
  }

  fun countSpecies(organizationId: OrganizationId, asOf: TemporalAccessor): Int {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(DSL.count())
        .from(SPECIES)
        .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.CREATED_TIME.le(asOf.toInstant()))
        .and(SPECIES.DELETED_TIME.isNull.or(SPECIES.DELETED_TIME.gt(asOf.toInstant())))
        .fetchOne()
        ?.value1()
        ?: 0
  }

  fun findAllSpecies(organizationId: OrganizationId): List<SpeciesRow> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .selectFrom(SPECIES)
        .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.DELETED_TIME.isNull)
        .orderBy(SPECIES.ID)
        .fetchInto(SpeciesRow::class.java)
  }

  fun createSpecies(row: SpeciesRow): SpeciesId {
    val organizationId = row.organizationId!!
    requirePermissions { createSpecies(organizationId) }

    val existingRow =
        dslContext
            .selectFrom(SPECIES)
            .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
            .and(SPECIES.SCIENTIFIC_NAME.eq(row.scientificName))
            .and(SPECIES.DELETED_TIME.isNotNull)
            .fetchOneInto(SpeciesRow::class.java)

    return if (existingRow?.deletedTime != null) {
      log.info("Reusing existing species ${existingRow.id}")

      val rowWithExistingId =
          row.copy(
              createdBy = existingRow.createdBy,
              createdTime = existingRow.createdTime,
              deletedBy = null,
              deletedTime = null,
              id = existingRow.id,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
          )

      speciesDao.update(rowWithExistingId)
      existingRow.id!!
    } else {
      val rowWithMetadata =
          row.copy(
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              deletedBy = null,
              deletedTime = null,
              id = null,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
          )

      speciesDao.insert(rowWithMetadata)
      rowWithMetadata.id!!
    }
  }

  /**
   * Updates the data for an existing species.
   *
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(row: SpeciesRow) {
    val speciesId = row.id ?: throw IllegalArgumentException("No species ID specified")

    val existing = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    val organizationId = existing.organizationId!!

    requirePermissions { updateSpecies(speciesId) }

    speciesDao.update(
        row.copy(
            createdBy = existing.createdBy,
            createdTime = existing.createdTime,
            deletedBy = existing.deletedBy,
            deletedTime = existing.deletedTime,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            organizationId = organizationId,
        ))
  }

  /**
   * Deletes a species from an organization. This doesn't remove any existing references to the
   * species, just prevents it from being used in the future.
   *
   * @throws AccessDeniedException The user does not have permission to delete the species.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun deleteSpecies(speciesId: SpeciesId) {
    requirePermissions { deleteSpecies(speciesId) }

    val rowsUpdated =
        dslContext
            .update(SPECIES)
            .set(SPECIES.DELETED_BY, currentUser().userId)
            .set(SPECIES.DELETED_TIME, clock.instant())
            .where(SPECIES.ID.eq(speciesId))
            .execute()

    if (rowsUpdated != 1) {
      throw SpeciesNotFoundException(speciesId)
    }
  }
}
