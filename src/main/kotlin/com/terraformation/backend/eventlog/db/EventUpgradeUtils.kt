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
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.eventlog.UpgradableEvent
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

  fun getWithdrawalValuesMissingFromV1(
      withdrawalId: WithdrawalId,
      createdEventLogId: EventLogId,
  ): WithdrawalValuesMissingFromV1 {
    val currentRow =
        dslContext
            .select(
                WITHDRAWALS.DESTINATION,
                WITHDRAWALS.VIABILITY_TEST_ID,
                WITHDRAWALS.WITHDRAWN_BY,
            )
            .from(WITHDRAWALS)
            .where(WITHDRAWALS.ID.eq(withdrawalId))
            .fetchOne()

    val laterEdits =
        eventLogStore
            .fetchByIdsSince(
                mapOf(withdrawalId to createdEventLogId),
                listOf(WithdrawalUpdatedEvent::class),
            )
            .map { it.event }

    return WithdrawalValuesMissingFromV1(
        destination =
            valueAtCreation(
                laterEdits,
                currentRow?.get(WITHDRAWALS.DESTINATION),
                { it.changedFrom.destination },
                { it.changedTo.destination },
            ),
        // A withdrawal's viability test can't be changed after it's created, so the current row
        // always has the creation-time value.
        viabilityTestId = currentRow?.get(WITHDRAWALS.VIABILITY_TEST_ID),
        withdrawnByUserId =
            valueAtCreation(
                laterEdits,
                currentRow?.get(WITHDRAWALS.WITHDRAWN_BY),
                { it.changedFrom.withdrawnByUserId },
                { it.changedTo.withdrawnByUserId },
            ),
    )
  }

  data class WithdrawalValuesMissingFromV1(
      val destination: String?,
      val viabilityTestId: ViabilityTestId?,
      val withdrawnByUserId: UserId?,
  )

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
