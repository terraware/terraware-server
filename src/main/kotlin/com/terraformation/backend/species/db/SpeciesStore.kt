package com.terraformation.backend.species.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.SpeciesProblemField
import com.terraformation.backend.db.SpeciesProblemHasNoSuggestionException
import com.terraformation.backend.db.SpeciesProblemId
import com.terraformation.backend.db.SpeciesProblemNotFoundException
import com.terraformation.backend.db.SpeciesProblemType
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.SpeciesService
import java.time.Clock
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
    private val speciesProblemsDao: SpeciesProblemsDao,
) {
  private val log = perClassLogger()

  fun fetchSpeciesIdByName(organizationId: OrganizationId, scientificName: String): SpeciesId? {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(SPECIES.ID)
        .from(SPECIES)
        .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.SCIENTIFIC_NAME.eq(scientificName))
        .and(SPECIES.DELETED_TIME.isNull)
        .fetchOne(SPECIES.ID)
  }

  fun fetchSpeciesById(speciesId: SpeciesId): SpeciesRow {
    requirePermissions { readSpecies(speciesId) }

    return dslContext
        .selectFrom(SPECIES)
        .where(SPECIES.ID.eq(speciesId))
        .and(SPECIES.DELETED_TIME.isNull)
        .fetchOneInto(SpeciesRow::class.java)
        ?: throw SpeciesNotFoundException(speciesId)
  }

  fun countSpecies(organizationId: OrganizationId): Int {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(DSL.count())
        .from(SPECIES)
        .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.DELETED_TIME.isNull)
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

  /**
   * Returns a list of IDs of species that haven't yet been checked for possible suggested edits.
   */
  fun fetchUncheckedSpeciesIds(organizationId: OrganizationId): List<SpeciesId> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(SPECIES.ID)
        .from(SPECIES)
        .where(SPECIES.CHECKED_TIME.isNull)
        .and(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.DELETED_TIME.isNull)
        .fetch(SPECIES.ID)
        .filterNotNull()
  }

  /** Returns a list of problems for a particular species, if any. */
  fun fetchProblemsBySpeciesId(speciesId: SpeciesId): List<SpeciesProblemsRow> {
    requirePermissions { readSpecies(speciesId) }

    return speciesProblemsDao.fetchBySpeciesId(speciesId)
  }

  /** Returns details of a single species problem. */
  fun fetchProblemById(problemId: SpeciesProblemId): SpeciesProblemsRow {
    val row = speciesProblemsDao.fetchOneById(problemId)
    val speciesId = row?.speciesId ?: throw SpeciesProblemNotFoundException(problemId)

    requirePermissions { readSpecies(speciesId) }

    return row
  }

  /**
   * Returns a map of all the problems with an organization's species. Species without problems are
   * not included in the map.
   */
  fun findAllProblems(organizationId: OrganizationId): Map<SpeciesId, List<SpeciesProblemsRow>> {
    requirePermissions { readOrganization(organizationId) }

    return dslContext
        .select(SPECIES_PROBLEMS.asterisk())
        .from(SPECIES_PROBLEMS)
        .join(SPECIES)
        .on(SPECIES_PROBLEMS.SPECIES_ID.eq(SPECIES.ID))
        .where(SPECIES.ORGANIZATION_ID.eq(organizationId))
        .and(SPECIES.DELETED_TIME.isNull)
        .fetchInto(SpeciesProblemsRow::class.java)
        .groupBy { row ->
          row.speciesId ?: throw IllegalStateException("Species problem has no species ID")
        }
  }

  /**
   * Creates a new species. You probably want to call [SpeciesService.createSpecies] instead of
   * this.
   */
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
              checkedTime = existingRow.checkedTime,
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
              checkedTime = null,
              createdBy = currentUser().userId,
              createdTime = clock.instant(),
              deletedBy = null,
              deletedTime = null,
              id = null,
              initialScientificName = row.scientificName,
              modifiedBy = currentUser().userId,
              modifiedTime = clock.instant(),
          )

      speciesDao.insert(rowWithMetadata)
      rowWithMetadata.id!!
    }
  }

  /**
   * Inserts or updates a single species, typically based on a row from an external data file, and
   * pay attention to existing species that have been renamed.
   *
   * Species are matched based on their scientific names, but this is a little tricky because we
   * track renamed species and want to match on the original name. The use case is someone uploading
   * a CSV, accepting a suggested name change, then uploading the CSV again; we want to remember
   * that species X in the CSV is really species Y in our database because the user renamed it.
   *
   * In addition, this needs to behave differently if there is an existing species but it is marked
   * as deleted; sometimes we want to undelete the existing row and sometimes we want to ignore it.
   * And it also needs to act differently depending on whether the user wants to overwrite existing
   * data or only import new entries.
   *
   * Exhaustive list of the possible cases:
   *
   * * Current = the scientific name from the CSV is the same as the scientific name of an existing
   * species (regardless of whether the existing species is deleted or not)
   * * Initial = the scientific name from the CSV is the same as the initial scientific name of an
   * existing species (regardless of whether the existing species is deleted or not)
   * * Deleted = the existing species, if any, is marked as deleted
   * * Overwrite = the [overwriteExisting] parameter is true, meaning the user wants to update
   * existing species rather than ignore them
   *
   * ```
   * | Current | Initial | Deleted | Overwrite | Action |
   * | ------- | ------- | ------- | --------- | ------ |
   * | No      | No      | No      | No        | Insert |
   * | No      | No      | No      | Yes       | Insert |
   * | No      | No      | Yes     | No        | (impossible) |
   * | No      | No      | Yes     | Yes       | (impossible) |
   * | No      | Yes     | No      | No        | No-op |
   * | No      | Yes     | No      | Yes       | Update but use current name instead of CSV's |
   * | No      | Yes     | Yes     | No        | Insert |
   * | No      | Yes     | Yes     | Yes       | Insert |
   * | Yes     | No      | No      | No        | No-op |
   * | Yes     | No      | No      | Yes       | Update |
   * | Yes     | No      | Yes     | No        | Update and undelete |
   * | Yes     | No      | Yes     | Yes       | Update and undelete |
   * | Yes     | Yes     | No      | No        | No-op |
   * | Yes     | Yes     | No      | Yes       | Update species with same current name |
   * | Yes     | Yes     | Yes     | No        | Update species with same current name; undelete |
   * | Yes     | Yes     | Yes     | Yes       | Update species with same current name; undelete |
   * ```
   *
   * @return The ID of the existing species that matched the requested name or the new species that
   * was inserted.
   */
  fun importRow(row: SpeciesRow, overwriteExisting: Boolean): SpeciesId {
    return with(SPECIES) {
      /**
       * Updates the editable values of an existing species and marks it as not deleted. Leaves the
       * initial scientific name as is.
       */
      fun updateExisting(speciesId: SpeciesId) {
        val rowsUpdated =
            dslContext
                .update(SPECIES)
                .set(COMMON_NAME, row.commonName)
                .set(FAMILY_NAME, row.familyName)
                .set(ENDANGERED, row.endangered)
                .set(RARE, row.rare)
                .set(GROWTH_FORM_ID, row.growthFormId)
                .set(SEED_STORAGE_BEHAVIOR_ID, row.seedStorageBehaviorId)
                .setNull(DELETED_TIME)
                .setNull(DELETED_BY)
                .set(MODIFIED_BY, currentUser().userId)
                .set(MODIFIED_TIME, clock.instant())
                .where(ID.eq(speciesId))
                .execute()
        if (rowsUpdated != 1) {
          log.error("Expected to update 1 row for species $speciesId but got $rowsUpdated")
        }
      }

      val existingByCurrentName =
          dslContext
              .select(SPECIES.ID, SPECIES.DELETED_TIME)
              .from(SPECIES)
              .where(ORGANIZATION_ID.eq(row.organizationId))
              .and(SCIENTIFIC_NAME.eq(row.scientificName))
              .fetchOne()
      val existingIdByCurrentName = existingByCurrentName?.get(ID)

      if (existingIdByCurrentName != null) {
        if (overwriteExisting || existingByCurrentName[DELETED_TIME] != null) {
          updateExisting(existingIdByCurrentName)
        }
        existingIdByCurrentName
      } else {
        val existingIdByInitialName =
            dslContext
                .select(SPECIES.ID)
                .from(SPECIES)
                .where(ORGANIZATION_ID.eq(row.organizationId))
                .and(INITIAL_SCIENTIFIC_NAME.eq(row.scientificName))
                .and(DELETED_TIME.isNull)
                .fetchOne(SPECIES.ID)

        if (existingIdByInitialName == null) {
          dslContext
              .insertInto(SPECIES)
              .set(SCIENTIFIC_NAME, row.scientificName)
              .set(INITIAL_SCIENTIFIC_NAME, row.scientificName)
              .set(COMMON_NAME, row.commonName)
              .set(FAMILY_NAME, row.familyName)
              .set(ENDANGERED, row.endangered)
              .set(RARE, row.rare)
              .set(GROWTH_FORM_ID, row.growthFormId)
              .set(SEED_STORAGE_BEHAVIOR_ID, row.seedStorageBehaviorId)
              .set(CREATED_BY, currentUser().userId)
              .set(CREATED_TIME, clock.instant())
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .set(ORGANIZATION_ID, row.organizationId)
              .returning(ID)
              .fetchOne(ID)!!
        } else {
          if (overwriteExisting) {
            updateExisting(existingIdByInitialName)
          }
          existingIdByInitialName
        }
      }
    }
  }

  /**
   * Updates the data for an existing species. You probably want to call
   * [SpeciesService.updateSpecies] instead of this.
   *
   * @return The updated row. This is a new object; the input row is not modified.
   * @throws DuplicateKeyException The requested name was already in use.
   * @throws SpeciesNotFoundException No species with the requested ID exists.
   */
  fun updateSpecies(row: SpeciesRow): SpeciesRow {
    val speciesId = row.id ?: throw IllegalArgumentException("No species ID specified")

    requirePermissions { updateSpecies(speciesId) }

    val existing = speciesDao.fetchOneById(speciesId) ?: throw SpeciesNotFoundException(speciesId)

    val updatedRow =
        row.copy(
            createdBy = existing.createdBy,
            createdTime = existing.createdTime,
            deletedBy = existing.deletedBy,
            deletedTime = existing.deletedTime,
            initialScientificName = existing.initialScientificName,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            organizationId = existing.organizationId,
        )

    speciesDao.update(updatedRow)

    return updatedRow
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

  fun deleteProblem(problemId: SpeciesProblemId) {
    val problem = speciesProblemsDao.fetchOneById(problemId)
    val speciesId = problem?.speciesId ?: throw SpeciesProblemNotFoundException(problemId)

    requirePermissions { updateSpecies(speciesId) }

    speciesProblemsDao.deleteById(problemId)
  }

  /**
   * Records the result of checking a species for problems. Inserts the problems, if any, into
   * `species_problems`, and sets the species' checked time so it won't be scanned again. Any
   * existing problems are discarded.
   */
  fun updateProblems(speciesId: SpeciesId, problems: Collection<SpeciesProblemsRow>) {
    requirePermissions { updateSpecies(speciesId) }

    val problemsWithMetadata =
        problems.map { it.copy(speciesId = speciesId, createdTime = clock.instant()) }

    dslContext.transaction { _ ->
      dslContext
          .deleteFrom(SPECIES_PROBLEMS)
          .where(SPECIES_PROBLEMS.SPECIES_ID.eq(speciesId))
          .execute()

      speciesProblemsDao.insert(problemsWithMetadata)

      dslContext
          .update(SPECIES)
          .set(SPECIES.CHECKED_TIME, clock.instant())
          .where(SPECIES.ID.eq(speciesId))
          .execute()
    }
  }

  fun acceptProblemSuggestion(problemId: SpeciesProblemId): SpeciesRow {
    val problem = fetchProblemById(problemId)
    val speciesId = problem.speciesId ?: throw SpeciesProblemNotFoundException(problemId)
    val existingSpecies = fetchSpeciesById(speciesId)

    val fieldId = problem.fieldId ?: throw IllegalStateException("Species problem had no field")
    val typeId = problem.typeId ?: throw IllegalStateException("Species problem had no type")

    val correctedSpecies =
        when (typeId) {
          SpeciesProblemType.NameNotFound -> throw SpeciesProblemHasNoSuggestionException(problemId)
          SpeciesProblemType.NameIsSynonym,
          SpeciesProblemType.NameMisspelled -> {
            // Only one field defined right now but use a "when" so this will break the build if
            // we add a second field and forget to handle it here.
            when (fieldId) {
              SpeciesProblemField.ScientificName ->
                  existingSpecies.copy(scientificName = problem.suggestedValue)
            }
          }
        }

    return dslContext.transactionResult { _ ->
      deleteProblem(problemId)
      updateSpecies(correctedSpecies)
    }
  }
}
