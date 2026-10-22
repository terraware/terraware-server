package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
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
