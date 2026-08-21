package com.terraformation.backend.eventlog.db

import com.terraformation.backend.customer.event.OrganizationCreatedEvent
import com.terraformation.backend.customer.event.OrganizationRenamedEvent
import com.terraformation.backend.customer.event.ProjectCreatedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV2
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEvent
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
  val withdrawalValuesMissingFromV1 = WithdrawalValuesMissingFromV1()

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

  fun getPreviousProjectNameFromV1Events(
      projectId: ProjectId,
      beforeEventLogId: EventLogId,
  ): String {
    val lastRename = eventLogStore.fetchLastById<ProjectRenamedEvent>(projectId, beforeEventLogId)
    return lastRename?.event?.changedTo?.name
        ?: eventLogStore
            .fetchLastById<ProjectCreatedEvent>(projectId, beforeEventLogId)
            ?.event
            ?.name
        ?: throw ProjectNotFoundException(projectId)
  }

  inner class WithdrawalValuesMissingFromV1 {
    fun upgrade(original: WithdrawalCreatedEventV1): WithdrawalCreatedEventV2 {
      val currentRow =
          dslContext
              .select(
                  WITHDRAWALS.DESTINATION,
                  WITHDRAWALS.VIABILITY_TEST_ID,
                  WITHDRAWALS.WITHDRAWN_BY,
              )
              .from(WITHDRAWALS)
              .where(WITHDRAWALS.ID.eq(original.withdrawalId))
              .fetchOne()

      // A withdrawal can't be edited before it exists, so every update event for it was published
      // after the creation event being upgraded.
      val laterEdits =
          eventLogStore
              .fetchByIds(
                  listOf(original.withdrawalId),
                  listOf(WithdrawalUpdatedEvent::class),
              )
              .map { it.event }

      return WithdrawalCreatedEventV2(
          accessionId = original.accessionId,
          batchId = original.batchId,
          date = original.date,
          destination =
              valueAtCreation(
                  laterEdits,
                  currentRow?.get(WITHDRAWALS.DESTINATION),
                  { it.changedFrom.destination },
                  { it.changedTo.destination },
              ),
          facilityId = original.facilityId,
          notes = original.notes,
          organizationId = original.organizationId,
          purpose = original.purpose,
          staffResponsible = original.staffResponsible,
          // A withdrawal's viability test can't be changed after it's created, so the current row
          // always has the creation-time value.
          viabilityTestId = currentRow?.get(WITHDRAWALS.VIABILITY_TEST_ID),
          withdrawalId = original.withdrawalId,
          withdrawnByUserId =
              valueAtCreation(
                  laterEdits,
                  currentRow?.get(WITHDRAWALS.WITHDRAWN_BY),
                  { it.changedFrom.withdrawnByUserId },
                  { it.changedTo.withdrawnByUserId },
              ),
          withdrawnQuantity = original.withdrawnQuantity,
      )
    }
  }

  private fun <T, V> valueAtCreation(
      laterEdits: List<T>,
      currentValue: V?,
      getChangedFrom: (T) -> V?,
      getChangedTo: (T) -> V?,
  ): V? {
    val firstEditOfField = laterEdits.firstOrNull {
      getChangedFrom(it) != null || getChangedTo(it) != null
    }

    return if (firstEditOfField != null) getChangedFrom(firstEditOfField) else currentValue
  }
}
