package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import javax.annotation.ManagedBean
import org.springframework.security.access.AccessDeniedException

/** Permission-aware accessors for facility information. */
@ManagedBean
class FacilityStore(private val facilitiesDao: FacilitiesDao) {
  fun fetchById(facilityId: FacilityId): FacilityModel? {
    return if (!currentUser().canReadFacility(facilityId)) {
      null
    } else {
      facilitiesDao.fetchOneById(facilityId)?.toModel()
    }
  }

  fun fetchById(ids: Collection<FacilityId>): List<FacilityModel> {
    val user = currentUser()
    val availableIds = ids.filter { user.canReadFacility(it) }.toTypedArray()

    return if (availableIds.isEmpty()) {
      emptyList()
    } else {
      facilitiesDao.fetchById(*availableIds).map { it.toModel() }
    }
  }

  /** Returns a list of all the facilities the current user can access. */
  fun fetchAll(): List<FacilityModel> {
    val user = currentUser()
    val facilityIds = user.facilityRoles.keys
    return fetchById(facilityIds)
  }

  /** Returns all the facilities the current user can access at a site. */
  fun fetchBySiteId(siteId: SiteId): List<FacilityModel> {
    return if (currentUser().canListFacilities(siteId)) {
      facilitiesDao.fetchBySiteId(siteId).map { it.toModel() }
    } else {
      emptyList()
    }
  }

  /**
   * Creates a new facility.
   *
   * @throws AccessDeniedException The current user does not have permission to create facilities at
   * the site.
   */
  fun create(siteId: SiteId, name: String, type: FacilityType): FacilityModel {
    if (!currentUser().canCreateFacility(siteId)) {
      throw AccessDeniedException("No permission to create facilities on this site.")
    }

    val row = FacilitiesRow(name = name, siteId = siteId, typeId = type)

    facilitiesDao.insert(row)

    return row.toModel()
  }
}
