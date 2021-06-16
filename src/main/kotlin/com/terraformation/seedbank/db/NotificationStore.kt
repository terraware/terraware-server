package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.NotificationPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.SiteModulesDao
import com.terraformation.seedbank.db.tables.references.NOTIFICATIONS
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class NotificationStore(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val siteModulesDao: SiteModulesDao
) {
  private val log = perClassLogger()

  fun fetchSince(since: Instant? = null, maxResults: Long? = null): List<NotificationPayload> {
    return dslContext
        .select(
            NOTIFICATIONS.ID,
            NOTIFICATIONS.CREATED_TIME,
            NOTIFICATIONS.TYPE_ID,
            NOTIFICATIONS.READ,
            NOTIFICATIONS.MESSAGE,
            NOTIFICATIONS.accessions().NUMBER,
            NOTIFICATIONS.ACCESSION_STATE_ID)
        .from(NOTIFICATIONS)
        .apply { if (since != null) where(NOTIFICATIONS.CREATED_TIME.ge(since)) }
        .orderBy(NOTIFICATIONS.CREATED_TIME.desc(), NOTIFICATIONS.ID.desc())
        .apply { if (maxResults != null) limit(maxResults) }
        .fetch { record ->
          NotificationPayload(
              id = record[NOTIFICATIONS.ID].toString(),
              timestamp = record[NOTIFICATIONS.CREATED_TIME]!!,
              type = record[NOTIFICATIONS.TYPE_ID]!!,
              read = record[NOTIFICATIONS.READ]!!,
              text = record[NOTIFICATIONS.MESSAGE]!!,
              accessionNumber = record[NOTIFICATIONS.accessions().NUMBER],
              state = record[NOTIFICATIONS.ACCESSION_STATE_ID])
        }
  }

  fun markRead(notificationId: Long): Boolean {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATIONS)
            .set(NOTIFICATIONS.READ, true)
            .where(NOTIFICATIONS.ID.eq(notificationId))
            .execute()

    return rowsUpdated == 1
  }

  fun markAllRead() {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATIONS)
            .set(NOTIFICATIONS.READ, true)
            .where(DSL.not(NOTIFICATIONS.READ))
            .execute()

    log.debug("Marked $rowsUpdated notifications as read")
  }

  fun markAllUnread() {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATIONS)
            .set(NOTIFICATIONS.READ, false)
            .where(NOTIFICATIONS.READ)
            .execute()

    log.debug("Marked $rowsUpdated notifications as unread")
  }

  fun insertStateNotification(state: AccessionState, message: String) {
    val siteId = siteModulesDao.fetchOneById(config.siteModuleId)!!.siteId!!

    dslContext
        .insertInto(NOTIFICATIONS)
        .set(NOTIFICATIONS.ACCESSION_STATE_ID, state)
        .set(NOTIFICATIONS.CREATED_TIME, clock.instant())
        .set(NOTIFICATIONS.MESSAGE, message)
        .set(NOTIFICATIONS.SITE_ID, siteId)
        .set(NOTIFICATIONS.TYPE_ID, NotificationType.State)
        .execute()
  }

  fun insertDateNotification(accessionId: Long, message: String) {
    val siteId = siteModulesDao.fetchOneById(config.siteModuleId)!!.siteId!!

    dslContext
        .insertInto(NOTIFICATIONS)
        .set(NOTIFICATIONS.ACCESSION_ID, accessionId)
        .set(NOTIFICATIONS.CREATED_TIME, clock.instant())
        .set(NOTIFICATIONS.MESSAGE, message)
        .set(NOTIFICATIONS.SITE_ID, siteId)
        .set(NOTIFICATIONS.TYPE_ID, NotificationType.Date)
        .execute()
  }
}
