package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.FacilityAlertRecipientsDao
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.pojos.FacilityAlertRecipientsRow
import com.terraformation.backend.db.tables.references.FACILITY_ALERT_RECIPIENTS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

/** Permission-aware accessors for facility information. */
@ManagedBean
class FacilityStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val facilitiesDao: FacilitiesDao,
    private val facilityAlertRecipientsDao: FacilityAlertRecipientsDao
) {
  private val log = perClassLogger()

  fun fetchById(facilityId: FacilityId): FacilityModel? {
    return if (!currentUser().canReadFacility(facilityId)) {
      null
    } else {
      facilitiesDao.fetchOneById(facilityId)?.toModel()
    }
  }

  /** Returns a list of all the facilities the current user can access. */
  fun fetchAll(): List<FacilityModel> {
    val availableIds = currentUser().facilityRoles.keys.toTypedArray()
    return if (availableIds.isEmpty()) {
      emptyList()
    } else {
      facilitiesDao.fetchById(*availableIds).map { it.toModel() }
    }
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
    requirePermissions { createFacility(siteId) }

    val row =
        FacilitiesRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
            siteId = siteId,
            typeId = type,
        )

    facilitiesDao.insert(row)

    return row.toModel()
  }

  /** Updates the settings of an existing facility. */
  fun update(facilityId: FacilityId, name: String, type: FacilityType) {
    requirePermissions { updateFacility(facilityId) }

    val existingRow =
        facilitiesDao.fetchOneById(facilityId) ?: throw FacilityNotFoundException(facilityId)

    facilitiesDao.update(
        existingRow.copy(
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
            typeId = type))
  }

  fun getAlertRecipients(facilityId: FacilityId): List<String> {
    requirePermissions { readFacility(facilityId) }

    return facilityAlertRecipientsDao.fetchByFacilityId(facilityId).mapNotNull { it.email }
  }

  fun insertAlertRecipient(facilityId: FacilityId, email: String) {
    requirePermissions { updateFacility(facilityId) }

    facilityAlertRecipientsDao.insert(
        FacilityAlertRecipientsRow(facilityId = facilityId, email = email))
  }

  fun deleteAlertRecipient(facilityId: FacilityId, email: String) {
    requirePermissions { updateFacility(facilityId) }

    with(FACILITY_ALERT_RECIPIENTS) {
      val id =
          dslContext
              .select(ID)
              .from(FACILITY_ALERT_RECIPIENTS)
              .where(FACILITY_ID.eq(facilityId))
              .and(EMAIL.eq(email))
              .fetchOne(ID)

      if (id != null) {
        dslContext.deleteFrom(FACILITY_ALERT_RECIPIENTS).where(ID.eq(id)).execute()
      } else {
        log.warn("Tried to delete nonexistent alert recipient $email for $facilityId")
      }
    }
  }
}
