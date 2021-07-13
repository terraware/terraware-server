package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import java.math.BigDecimal
import javax.annotation.ManagedBean
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class SiteStore(private val sitesDao: SitesDao) {
  fun fetchById(siteId: SiteId): SiteModel? {
    return if (currentUser().canReadSite(siteId)) {
      sitesDao.fetchOneById(siteId)?.toModel()
    } else {
      null
    }
  }

  fun fetchByProjectId(projectId: ProjectId): List<SiteModel> {
    return if (currentUser().canListSites(projectId)) {
      sitesDao.fetchByProjectId(projectId).map { it.toModel() }
    } else {
      emptyList()
    }
  }

  fun create(
      projectId: ProjectId,
      name: String,
      latitude: BigDecimal,
      longitude: BigDecimal
  ): SiteModel {
    if (!currentUser().canCreateSite(projectId)) {
      throw AccessDeniedException("No permission to create sites in this project")
    }

    val row =
        SitesRow(
            latitude = latitude,
            longitude = longitude,
            name = name,
            projectId = projectId,
        )

    sitesDao.insert(row)

    return row.toModel()
  }
}
