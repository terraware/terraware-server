package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SiteNotFoundException
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.transformSrid
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext

@ManagedBean
class SiteStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
    private val sitesDao: SitesDao
) {
  fun fetchOneById(siteId: SiteId, srid: Int = SRID.LONG_LAT): SiteModel {
    requirePermissions { readSite(siteId) }

    return fetchModelsWhere(srid, SITES.ID.eq(siteId)).firstOrNull()
        ?: throw SiteNotFoundException(siteId)
  }

  fun fetchByProjectId(projectId: ProjectId, srid: Int = SRID.LONG_LAT): List<SiteModel> {
    return if (currentUser().canListSites(projectId)) {
      fetchModelsWhere(srid, SITES.PROJECT_ID.eq(projectId))
    } else {
      emptyList()
    }
  }

  /** Returns all the sites the user has access to. */
  fun findAll(srid: Int = SRID.LONG_LAT): List<SiteModel> {
    val user = currentUser()
    val listableProjects = user.projectRoles.keys.filter { user.canListSites(it) }

    return if (listableProjects.isNotEmpty()) {
      fetchModelsWhere(srid, SITES.PROJECT_ID.`in`(listableProjects))
    } else {
      emptyList()
    }
  }

  /**
   * Creates a new site.
   *
   * @param row Initial site information. Project ID is required; ID and timestamps are ignored.
   */
  fun create(row: SitesRow): SiteModel {
    val projectId = row.projectId ?: throw IllegalArgumentException("Must specify project ID")

    requirePermissions { createSite(projectId) }

    val rowWithTimestamps =
        row.copy(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            id = null,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
        )

    sitesDao.insert(rowWithTimestamps)

    return rowWithTimestamps.toModel()
  }

  /**
   * Updates an existing site.
   *
   * @param[row] New site information. `id` is required. Timestamps are ignored. If `projectId` is
   * null, the site's existing project ID is not modified.
   */
  fun update(row: SitesRow) {
    val siteId = row.id ?: throw IllegalArgumentException("Must specify site ID")
    val newProjectId = row.projectId

    requirePermissions { updateSite(siteId) }

    val existingProjectId = parentStore.getProjectId(siteId) ?: throw SiteNotFoundException(siteId)
    if (newProjectId != null && newProjectId != existingProjectId) {
      val newOrganizationId =
          parentStore.getOrganizationId(newProjectId)
              ?: throw ProjectNotFoundException(newProjectId)
      val existingOrganizationId = parentStore.getOrganizationId(existingProjectId)
      if (newOrganizationId != existingOrganizationId) {
        throw IllegalArgumentException("Cannot move sites between organizations")
      }

      requirePermissions {
        // For permission-checking purposes, model moving a site between projects as "delete from
        // the old project and create in the new one." You need to be able to do both.
        deleteSite(siteId)
        createSite(newProjectId)
      }
    }

    with(SITES) {
      dslContext
          .update(SITES)
          .set(DESCRIPTION, row.description)
          .set(LOCALE, row.locale)
          .set(LOCATION, row.location)
          .set(MODIFIED_BY, currentUser().userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, row.name)
          .apply { if (newProjectId != null) set(PROJECT_ID, newProjectId) }
          .set(TIMEZONE, row.timezone)
          .where(ID.eq(siteId))
          .execute()
    }
  }

  private fun fetchModelsWhere(srid: Int, condition: Condition): List<SiteModel> {
    return dslContext
        .select(
            SITES.CREATED_TIME,
            SITES.DESCRIPTION,
            SITES.ENABLED,
            SITES.ID,
            SITES.LOCALE,
            SITES.LOCATION.transformSrid(srid).`as`(SITES.LOCATION),
            SITES.MODIFIED_TIME,
            SITES.NAME,
            SITES.PROJECT_ID,
            SITES.TIMEZONE,
        )
        .from(SITES)
        .where(condition)
        .fetchInto(SitesRow::class.java)
        .map { it.toModel() }
  }
}
