package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import jakarta.inject.Named
import java.time.InstantSource
import java.time.ZoneId
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.security.access.AccessDeniedException

@Named
class DraftPlantingSiteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
) {
  fun fetchOneById(draftPlantingSiteId: DraftPlantingSiteId): DraftPlantingSitesRecord {
    requirePermissions { readDraftPlantingSite(draftPlantingSiteId) }

    return dslContext
        .selectFrom(DRAFT_PLANTING_SITES)
        .where(DRAFT_PLANTING_SITES.ID.eq(draftPlantingSiteId))
        .fetchOne() ?: throw DraftPlantingSiteNotFoundException(draftPlantingSiteId)
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
}
