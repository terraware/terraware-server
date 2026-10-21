package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.DRAFT_STRATA
import com.terraformation.backend.db.tracking.tables.references.DRAFT_SUBSTRATA
import com.terraformation.backend.tracking.model.DraftPlantingSiteMap
import com.terraformation.backend.tracking.model.DraftPlantingSiteModel
import com.terraformation.backend.tracking.model.DraftStratumModel
import com.terraformation.backend.tracking.model.DraftSubstratumModel
import com.terraformation.backend.util.toMultiPolygon
import jakarta.inject.Named
import java.time.InstantSource
import java.time.ZoneId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.security.access.AccessDeniedException

@Named
class DraftPlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
) {
  private val strataField: Field<List<DraftStratumModel>> = strataMultiset()

  fun fetchOneById(draftPlantingSiteId: DraftPlantingSiteId): DraftPlantingSitesRecord {
    requirePermissions { readDraftPlantingSite(draftPlantingSiteId) }

    return dslContext
        .selectFrom(DRAFT_PLANTING_SITES)
        .where(DRAFT_PLANTING_SITES.ID.eq(draftPlantingSiteId))
        .fetchOne() ?: throw DraftPlantingSiteNotFoundException(draftPlantingSiteId)
  }

  fun fetchModelById(id: DraftPlantingSiteId): DraftPlantingSiteModel {
    requirePermissions { readDraftPlantingSite(id) }

    return with(DRAFT_PLANTING_SITES) {
      dslContext
          .select(
              BOUNDARY,
              BOUNDARY_SOURCE,
              CREATED_BY,
              CREATED_TIME,
              DESCRIPTION,
              EXCLUSION,
              ID,
              MODIFIED_BY,
              MODIFIED_TIME,
              NAME,
              ORGANIZATION_ID,
              PROJECT_ID,
              SITE_EDIT_STEP,
              SITE_TYPE_ID,
              strataField,
              TIME_ZONE,
          )
          .from(DRAFT_PLANTING_SITES)
          .where(ID.eq(id))
          .fetchOne { record ->
            DraftPlantingSiteModel(
                createdBy = record[CREATED_BY],
                createdTime = record[CREATED_TIME],
                description = record[DESCRIPTION],
                id = record[ID],
                map =
                    DraftPlantingSiteMap(
                        boundary = record[BOUNDARY]?.toMultiPolygon(),
                        boundarySource = record[BOUNDARY_SOURCE],
                        exclusion = record[EXCLUSION]?.toMultiPolygon(),
                        siteEditStep = record[SITE_EDIT_STEP],
                        siteType = record[SITE_TYPE_ID]!!,
                        strata = record[strataField],
                    ),
                modifiedBy = record[MODIFIED_BY],
                modifiedTime = record[MODIFIED_TIME],
                name = record[NAME]!!,
                organizationId = record[ORGANIZATION_ID]!!,
                projectId = record[PROJECT_ID],
                timeZone = record[TIME_ZONE],
            )
          }
    } ?: throw DraftPlantingSiteNotFoundException(id)
  }

  fun create(
      data: JSONB,
      name: String,
      organizationId: OrganizationId,
      description: String? = null,
      numSubstrata: Int? = null,
      numStrata: Int? = null,
      projectId: ProjectId? = null,
      timeZone: ZoneId? = null,
  ): DraftPlantingSitesRecord {
    requirePermissions { createDraftPlantingSite(organizationId) }

    if (projectId != null) {
      requirePermissions { readProject(projectId) }
      if (parentStore.getOrganizationId(projectId) != organizationId) {
        throw ProjectInDifferentOrganizationException()
      }
    }

    val now = clock.instant()
    val userId = currentUser().userId

    val record =
        DraftPlantingSitesRecord(
            createdBy = userId,
            createdTime = now,
            data = data,
            description = description,
            modifiedBy = userId,
            modifiedTime = now,
            name = name,
            numSubstrata = numSubstrata,
            numStrata = numStrata,
            organizationId = organizationId,
            projectId = projectId,
            timeZone = timeZone,
        )

    dslContext.attach(record)
    record.insert()

    return record
  }

  /**
   * Creates a new draft planting site. The model's ID and audit fields are ignored.
   *
   * @return The draft as stored, including the IDs of its strata and substrata.
   */
  fun create(model: DraftPlantingSiteModel): DraftPlantingSiteModel {
    requirePermissions { createDraftPlantingSite(model.organizationId) }

    requireProjectInOrganization(model.projectId, model.organizationId)
    model.map.requireValid(null)

    val now = clock.instant()
    val userId = currentUser().userId

    return dslContext.transactionResult { _ ->
      val id =
          with(DRAFT_PLANTING_SITES) {
            dslContext
                .insertInto(DRAFT_PLANTING_SITES)
                .set(BOUNDARY, model.map.boundary)
                .set(BOUNDARY_SOURCE, model.map.boundarySource)
                .set(CREATED_BY, userId)
                .set(CREATED_TIME, now)
                .set(DESCRIPTION, model.description)
                .set(EXCLUSION, model.map.exclusion)
                .set(MODIFIED_BY, userId)
                .set(MODIFIED_TIME, now)
                .set(NAME, model.name)
                .set(ORGANIZATION_ID, model.organizationId)
                .set(PROJECT_ID, model.projectId)
                .set(SITE_EDIT_STEP, model.map.siteEditStep)
                .set(SITE_TYPE_ID, model.map.siteType)
                .set(TIME_ZONE, model.timeZone)
                .returning(ID)
                .fetchOne(ID)!!
          }

      updateStrata(id, emptyList(), model.map.strata)

      fetchModelById(id)
    }
  }

  fun delete(draftPlantingSiteId: DraftPlantingSiteId) {
    requirePermissions { deleteDraftPlantingSite(draftPlantingSiteId) }

    dslContext
        .deleteFrom(DRAFT_PLANTING_SITES)
        .where(DRAFT_PLANTING_SITES.ID.eq(draftPlantingSiteId))
        .execute()
  }

  fun update(
      draftPlantingSiteId: DraftPlantingSiteId,
      updateFunc: (DraftPlantingSitesRecord) -> Unit,
  ) {
    requirePermissions { updateDraftPlantingSite(draftPlantingSiteId) }

    val record = fetchOneById(draftPlantingSiteId)
    updateFunc(record)

    val readOnlyFields =
        with(DRAFT_PLANTING_SITES) { listOf(CREATED_BY, CREATED_TIME, ID, ORGANIZATION_ID) }

    readOnlyFields.forEach { field ->
      if (record.modified(field)) {
        throw AccessDeniedException("No permission to change $field")
      }
    }

    record.projectId?.let { projectId ->
      requirePermissions { readProject(projectId) }
      if (parentStore.getOrganizationId(projectId) != record.organizationId) {
        throw ProjectInDifferentOrganizationException()
      }
    }

    record.modifiedBy = currentUser().userId
    record.modifiedTime = clock.instant()

    record.store()
  }

  /**
   * Updates a draft planting site. Strata and substrata with IDs are updated in place, those
   * without IDs are inserted, and existing ones that are absent from the updated model are deleted.
   */
  fun updateModel(
      id: DraftPlantingSiteId,
      updateFunc: (DraftPlantingSiteModel) -> DraftPlantingSiteModel,
  ) {
    requirePermissions { updateDraftPlantingSite(id) }

    dslContext.transaction { _ ->
      // Serialize concurrent updates so the stratum and substratum syncs don't interleave.
      dslContext
          .select(DSL.one())
          .from(DRAFT_PLANTING_SITES)
          .where(DRAFT_PLANTING_SITES.ID.eq(id))
          .forUpdate()
          .execute()

      val existing = fetchModelById(id)
      val updated = updateFunc(existing)

      if (updated.organizationId != existing.organizationId) {
        throw AccessDeniedException("No permission to change organization")
      }

      requireProjectInOrganization(updated.projectId, existing.organizationId)
      updated.map.requireValid(existing.map)

      with(DRAFT_PLANTING_SITES) {
        dslContext
            .update(DRAFT_PLANTING_SITES)
            .set(BOUNDARY, updated.map.boundary)
            .set(BOUNDARY_SOURCE, updated.map.boundarySource)
            .set(DESCRIPTION, updated.description)
            .set(EXCLUSION, updated.map.exclusion)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, updated.name)
            .set(PROJECT_ID, updated.projectId)
            .set(SITE_EDIT_STEP, updated.map.siteEditStep)
            .set(TIME_ZONE, updated.timeZone)
            .where(ID.eq(id))
            .execute()
      }

      updateStrata(id, existing.map.strata, updated.map.strata)
    }
  }

  private fun requireProjectInOrganization(projectId: ProjectId?, organizationId: OrganizationId) {
    if (projectId != null) {
      requirePermissions { readProject(projectId) }
      if (parentStore.getOrganizationId(projectId) != organizationId) {
        throw ProjectInDifferentOrganizationException()
      }
    }
  }

  /**
   * Makes a draft's strata and substrata match a desired list whose IDs have already been checked
   * by [DraftPlantingSiteMap.requireValid].
   *
   * Strata and substrata whose names are changing are first given temporary names so that renames
   * that swap or shift names don't violate the uniqueness constraints regardless of update order.
   */
  private fun updateStrata(
      draftPlantingSiteId: DraftPlantingSiteId,
      existingStrata: List<DraftStratumModel>,
      desiredStrata: List<DraftStratumModel>,
  ) {
    val existingStrataById = existingStrata.associateBy { it.id }

    val deletedStratumIds = existingStrataById.keys - desiredStrata.map { it.id }.toSet()
    if (deletedStratumIds.isNotEmpty()) {
      dslContext.deleteFrom(DRAFT_STRATA).where(DRAFT_STRATA.ID.`in`(deletedStratumIds)).execute()
    }

    val renamedStrata = desiredStrata.filter {
      it.id != null && existingStrataById.getValue(it.id).name != it.name
    }
    if (renamedStrata.isNotEmpty()) {
      dslContext
          .update(DRAFT_STRATA)
          .set(DRAFT_STRATA.NAME, DSL.uuid().cast(SQLDataType.VARCHAR))
          .where(DRAFT_STRATA.ID.`in`(renamedStrata.map { it.id }))
          .execute()
    }

    desiredStrata.forEach { stratum ->
      if (stratum.id != null) {
        with(DRAFT_STRATA) {
          dslContext
              .update(DRAFT_STRATA)
              .set(BOUNDARY, stratum.boundary)
              .set(INITIAL_PLANTING_DENSITY, stratum.initialPlantingDensity)
              .set(NAME, stratum.name)
              .where(ID.eq(stratum.id))
              .execute()
        }

        updateSubstrata(
            stratum.id,
            existingStrataById.getValue(stratum.id).substrata,
            stratum.substrata,
        )
      } else {
        val stratumId =
            with(DRAFT_STRATA) {
              dslContext
                  .insertInto(DRAFT_STRATA)
                  .set(BOUNDARY, stratum.boundary)
                  .set(DRAFT_PLANTING_SITE_ID, draftPlantingSiteId)
                  .set(INITIAL_PLANTING_DENSITY, stratum.initialPlantingDensity)
                  .set(NAME, stratum.name)
                  .returning(ID)
                  .fetchOne(ID)!!
            }

        updateSubstrata(stratumId, emptyList(), stratum.substrata)
      }
    }
  }

  /** Makes a stratum's substrata match a desired list whose IDs have already been checked. */
  private fun updateSubstrata(
      draftStratumId: DraftStratumId,
      existingSubstrata: List<DraftSubstratumModel>,
      desiredSubstrata: List<DraftSubstratumModel>,
  ) {
    val existingSubstrataById = existingSubstrata.associateBy { it.id }

    val deletedSubstratumIds = existingSubstrataById.keys - desiredSubstrata.map { it.id }.toSet()
    if (deletedSubstratumIds.isNotEmpty()) {
      dslContext
          .deleteFrom(DRAFT_SUBSTRATA)
          .where(DRAFT_SUBSTRATA.ID.`in`(deletedSubstratumIds))
          .execute()
    }

    val renamedSubstrata = desiredSubstrata.filter {
      it.id != null && existingSubstrataById.getValue(it.id).name != it.name
    }
    if (renamedSubstrata.isNotEmpty()) {
      dslContext
          .update(DRAFT_SUBSTRATA)
          .set(DRAFT_SUBSTRATA.NAME, DSL.uuid().cast(SQLDataType.VARCHAR))
          .where(DRAFT_SUBSTRATA.ID.`in`(renamedSubstrata.map { it.id }))
          .execute()
    }

    with(DRAFT_SUBSTRATA) {
      desiredSubstrata.forEach { substratum ->
        if (substratum.id != null) {
          dslContext
              .update(DRAFT_SUBSTRATA)
              .set(BOUNDARY, substratum.boundary)
              .set(NAME, substratum.name)
              .where(ID.eq(substratum.id))
              .execute()
        } else {
          dslContext
              .insertInto(DRAFT_SUBSTRATA)
              .set(BOUNDARY, substratum.boundary)
              .set(DRAFT_STRATUM_ID, draftStratumId)
              .set(NAME, substratum.name)
              .execute()
        }
      }
    }
  }

  private fun substrataMultiset(): Field<List<DraftSubstratumModel>> {
    val boundaryField = DRAFT_SUBSTRATA.BOUNDARY.forMultiset()

    return DSL.multiset(
            DSL.select(boundaryField, DRAFT_SUBSTRATA.ID, DRAFT_SUBSTRATA.NAME)
                .from(DRAFT_SUBSTRATA)
                .where(DRAFT_SUBSTRATA.DRAFT_STRATUM_ID.eq(DRAFT_STRATA.ID))
                .orderBy(DRAFT_SUBSTRATA.ID)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            DraftSubstratumModel(
                boundary = record[boundaryField]!!.toMultiPolygon(),
                id = record[DRAFT_SUBSTRATA.ID]!!,
                name = record[DRAFT_SUBSTRATA.NAME]!!,
            )
          }
        }
  }

  private fun strataMultiset(): Field<List<DraftStratumModel>> {
    val boundaryField = DRAFT_STRATA.BOUNDARY.forMultiset()
    val substrataField = substrataMultiset()

    return DSL.multiset(
            DSL.select(
                    boundaryField,
                    DRAFT_STRATA.ID,
                    DRAFT_STRATA.INITIAL_PLANTING_DENSITY,
                    DRAFT_STRATA.NAME,
                    substrataField,
                )
                .from(DRAFT_STRATA)
                .where(DRAFT_STRATA.DRAFT_PLANTING_SITE_ID.eq(DRAFT_PLANTING_SITES.ID))
                .orderBy(DRAFT_STRATA.ID)
        )
        .convertFrom { result ->
          result.map { record: Record ->
            DraftStratumModel(
                boundary = record[boundaryField]!!.toMultiPolygon(),
                id = record[DRAFT_STRATA.ID]!!,
                initialPlantingDensity = record[DRAFT_STRATA.INITIAL_PLANTING_DENSITY]!!,
                name = record[DRAFT_STRATA.NAME]!!,
                substrata = record[substrataField],
            )
          }
        }
  }
}
