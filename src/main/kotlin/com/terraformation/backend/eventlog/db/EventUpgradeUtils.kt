package com.terraformation.backend.eventlog.db

import com.terraformation.backend.customer.event.OrganizationCreatedEvent
import com.terraformation.backend.customer.event.OrganizationRenamedEvent
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.eventlog.UpgradableEvent
import org.jooq.DSLContext

/**
 * Support functions for upgrading events. If a new version of an event adds data that wasn't
 * included in the previous version, [UpgradableEvent.toNextVersion] needs to be able to pull the
 * data from the database. An instance of this class is passed to that method to allow it to do so.
 */
class EventUpgradeUtils(
    val dslContext: DSLContext,
    val eventLogStore: EventLogStore,
) {
  fun getPreviousOrganizationName(
      organizationId: OrganizationId,
      beforeEventLogId: EventLogId,
  ): String {
    val lastRename =
        eventLogStore.fetchLastById<OrganizationRenamedEvent>(organizationId, beforeEventLogId)
    return lastRename?.event?.changedTo?.name
        ?: eventLogStore
            .fetchLastById<OrganizationCreatedEvent>(organizationId, beforeEventLogId)
            ?.event
            ?.name
        ?: throw OrganizationNotFoundException(organizationId)
  }
}
