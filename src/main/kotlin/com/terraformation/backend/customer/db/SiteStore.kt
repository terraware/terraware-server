package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.transformSrid
import java.time.Clock
import javax.annotation.ManagedBean
import net.postgis.jdbc.geometry.Point
import org.jooq.Condition
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class SiteStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val sitesDao: SitesDao
) {
  fun fetchById(siteId: SiteId, srid: Int = SRID.LONG_LAT): SiteModel? {
    return if (currentUser().canReadSite(siteId)) {
      return fetchModelsWhere(srid, SITES.ID.eq(siteId)).firstOrNull()
    } else {
      null
    }
  }

  fun fetchByProjectId(projectId: ProjectId, srid: Int = SRID.LONG_LAT): List<SiteModel> {
    return if (currentUser().canListSites(projectId)) {
      fetchModelsWhere(srid, SITES.PROJECT_ID.eq(projectId))
    } else {
      emptyList()
    }
  }

  /** Returns all the sites the user has access to. */
  fun fetchAll(srid: Int = SRID.LONG_LAT): List<SiteModel> {
    val user = currentUser()
    val listableProjects = user.projectRoles.keys.filter { user.canListSites(it) }

    return if (listableProjects.isNotEmpty()) {
      fetchModelsWhere(srid, SITES.PROJECT_ID.`in`(listableProjects))
    } else {
      emptyList()
    }
  }

  fun create(projectId: ProjectId, name: String, location: Point): SiteModel {
    if (!currentUser().canCreateSite(projectId)) {
      throw AccessDeniedException("No permission to create sites in this project")
    }

    val row =
        SitesRow(
            createdTime = clock.instant(),
            location = location,
            modifiedTime = clock.instant(),
            name = name,
            projectId = projectId)

    sitesDao.insert(row)

    return row.toModel()
  }

  private fun fetchModelsWhere(srid: Int, condition: Condition): List<SiteModel> {
    return dslContext
        .select(
            SITES.CREATED_TIME,
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
