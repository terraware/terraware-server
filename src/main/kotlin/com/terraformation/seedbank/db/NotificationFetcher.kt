package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.NotificationPayload
import com.terraformation.seedbank.db.tables.references.NOTIFICATION
import com.terraformation.seedbank.services.perClassLogger
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class NotificationFetcher(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  fun fetchSince(since: Instant? = null, maxResults: Long? = null): List<NotificationPayload> {
    return dslContext
        .select(
            NOTIFICATION.ID,
            NOTIFICATION.CREATED_TIME,
            NOTIFICATION.TYPE_ID,
            NOTIFICATION.READ,
            NOTIFICATION.MESSAGE,
            NOTIFICATION.accession().NUMBER,
            NOTIFICATION.ACCESSION_STATE_ID)
        .from(NOTIFICATION)
        .apply { if (since != null) where(NOTIFICATION.CREATED_TIME.ge(since)) }
        .orderBy(NOTIFICATION.CREATED_TIME.desc(), NOTIFICATION.ID.desc())
        .apply { if (maxResults != null) limit(maxResults) }
        .fetch { record ->
          NotificationPayload(
              id = record[NOTIFICATION.ID].toString(),
              timestamp = record[NOTIFICATION.CREATED_TIME]!!,
              type = record[NOTIFICATION.TYPE_ID]!!,
              read = record[NOTIFICATION.READ]!!,
              text = record[NOTIFICATION.MESSAGE]!!,
              accessionNumber = record[NOTIFICATION.accession().NUMBER],
              state = record[NOTIFICATION.ACCESSION_STATE_ID])
        }
  }

  fun markRead(notificationId: Long): Boolean {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATION)
            .set(NOTIFICATION.READ, true)
            .where(NOTIFICATION.ID.eq(notificationId))
            .execute()

    return rowsUpdated == 1
  }

  fun markAllRead() {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATION)
            .set(NOTIFICATION.READ, true)
            .where(DSL.not(NOTIFICATION.READ))
            .execute()

    log.debug("Marked $rowsUpdated notifications as read")
  }

  fun markAllUnread() {
    val rowsUpdated =
        dslContext
            .update(NOTIFICATION)
            .set(NOTIFICATION.READ, false)
            .where(NOTIFICATION.READ)
            .execute()

    log.debug("Marked $rowsUpdated notifications as unread")
  }
}
