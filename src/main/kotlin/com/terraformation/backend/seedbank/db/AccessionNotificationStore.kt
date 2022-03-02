package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotificationId
import com.terraformation.backend.db.AccessionNotificationType
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.references.ACCESSION_NOTIFICATIONS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.api.AccessionNotificationPayload
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class AccessionNotificationStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val facilitiesDao: FacilitiesDao
) {
  private val log = perClassLogger()

  fun fetchSince(
      facilityId: FacilityId,
      since: Instant? = null,
      maxResults: Long? = null
  ): List<AccessionNotificationPayload> {
    return dslContext
        .select(
            ACCESSION_NOTIFICATIONS.ID,
            ACCESSION_NOTIFICATIONS.CREATED_TIME,
            ACCESSION_NOTIFICATIONS.TYPE_ID,
            ACCESSION_NOTIFICATIONS.READ,
            ACCESSION_NOTIFICATIONS.MESSAGE,
            ACCESSION_NOTIFICATIONS.accessions().ID,
            ACCESSION_NOTIFICATIONS.accessions().NUMBER,
            ACCESSION_NOTIFICATIONS.ACCESSION_STATE_ID)
        .from(ACCESSION_NOTIFICATIONS)
        .join(FACILITIES)
        .on(ACCESSION_NOTIFICATIONS.SITE_ID.eq(FACILITIES.SITE_ID))
        .where(FACILITIES.ID.eq(facilityId))
        .apply { if (since != null) and(ACCESSION_NOTIFICATIONS.CREATED_TIME.ge(since)) }
        .orderBy(ACCESSION_NOTIFICATIONS.CREATED_TIME.desc(), ACCESSION_NOTIFICATIONS.ID.desc())
        .apply { if (maxResults != null) limit(maxResults) }
        .fetch { record ->
          AccessionNotificationPayload(
              id = record[ACCESSION_NOTIFICATIONS.ID].toString(),
              timestamp = record[ACCESSION_NOTIFICATIONS.CREATED_TIME]!!,
              type = record[ACCESSION_NOTIFICATIONS.TYPE_ID]!!,
              read = record[ACCESSION_NOTIFICATIONS.READ]!!,
              text = record[ACCESSION_NOTIFICATIONS.MESSAGE]!!,
              accessionId = record[ACCESSION_NOTIFICATIONS.accessions().ID],
              state = record[ACCESSION_NOTIFICATIONS.ACCESSION_STATE_ID])
        }
  }

  fun markRead(notificationId: AccessionNotificationId): Boolean {
    val rowsUpdated =
        dslContext
            .update(ACCESSION_NOTIFICATIONS)
            .set(ACCESSION_NOTIFICATIONS.READ, true)
            .where(ACCESSION_NOTIFICATIONS.ID.eq(notificationId))
            .execute()

    return rowsUpdated == 1
  }

  fun markAllRead() {
    val rowsUpdated =
        dslContext
            .update(ACCESSION_NOTIFICATIONS)
            .set(ACCESSION_NOTIFICATIONS.READ, true)
            .where(DSL.not(ACCESSION_NOTIFICATIONS.READ))
            .execute()

    log.debug("Marked $rowsUpdated notifications as read")
  }

  fun markAllUnread() {
    val rowsUpdated =
        dslContext
            .update(ACCESSION_NOTIFICATIONS)
            .set(ACCESSION_NOTIFICATIONS.READ, false)
            .where(ACCESSION_NOTIFICATIONS.READ)
            .execute()

    log.debug("Marked $rowsUpdated notifications as unread")
  }

  fun insertStateNotification(facilityId: FacilityId, state: AccessionState, message: String) {
    val siteId =
        facilitiesDao.fetchOneById(facilityId)?.siteId
            ?: throw IllegalStateException("Facility $facilityId not found")

    dslContext
        .insertInto(ACCESSION_NOTIFICATIONS)
        .set(ACCESSION_NOTIFICATIONS.ACCESSION_STATE_ID, state)
        .set(ACCESSION_NOTIFICATIONS.CREATED_TIME, clock.instant())
        .set(ACCESSION_NOTIFICATIONS.MESSAGE, message)
        .set(ACCESSION_NOTIFICATIONS.SITE_ID, siteId)
        .set(ACCESSION_NOTIFICATIONS.TYPE_ID, AccessionNotificationType.State)
        .execute()
  }

  fun insertDateNotification(facilityId: FacilityId, accessionId: AccessionId, message: String) {
    val siteId =
        facilitiesDao.fetchOneById(facilityId)?.siteId
            ?: throw IllegalStateException("Facility $facilityId not found")

    dslContext
        .insertInto(ACCESSION_NOTIFICATIONS)
        .set(ACCESSION_NOTIFICATIONS.ACCESSION_ID, accessionId)
        .set(ACCESSION_NOTIFICATIONS.CREATED_TIME, clock.instant())
        .set(ACCESSION_NOTIFICATIONS.MESSAGE, message)
        .set(ACCESSION_NOTIFICATIONS.SITE_ID, siteId)
        .set(ACCESSION_NOTIFICATIONS.TYPE_ID, AccessionNotificationType.Date)
        .execute()
  }
}
