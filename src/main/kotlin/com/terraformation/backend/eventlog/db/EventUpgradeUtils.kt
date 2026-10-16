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
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.seedbank.event.ViabilityTestUpdatedEvent
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV1
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV2
import com.terraformation.backend.seedbank.event.WithdrawalCreatedEventV3
import com.terraformation.backend.seedbank.event.WithdrawalUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV1
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV2
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

  fun upgrade(original: BiomassSpeciesCreatedEventV1): BiomassSpeciesCreatedEventV2 {
    val speciesName =
        original.speciesId?.let(::getSpeciesScientificName)
            ?: original.scientificName
            ?: throw IllegalStateException(
                "Biomass species ${original.biomassSpeciesId} has no scientific name"
            )

    return BiomassSpeciesCreatedEventV2(
        biomassSpeciesId = original.biomassSpeciesId,
        commonName = original.commonName,
        isInvasive = original.isInvasive,
        isThreatened = original.isThreatened,
        monitoringPlotId = original.monitoringPlotId,
        observationId = original.observationId,
        organizationId = original.organizationId,
        plantingSiteId = original.plantingSiteId,
        scientificName = original.scientificName,
        speciesId = original.speciesId,
        speciesName = speciesName,
    )
  }

  private fun getSpeciesScientificName(speciesId: SpeciesId): String =
      dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(speciesId)) ?: "$speciesId"

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

  fun upgrade(original: WithdrawalCreatedEventV2): WithdrawalCreatedEventV3 {
    val estimatedCount =
        if (
            original.withdrawnQuantity != null &&
                original.withdrawnQuantity.units != SeedQuantityUnits.Seeds
        ) {
          dslContext
              .select(WITHDRAWALS.ESTIMATED_COUNT)
              .from(WITHDRAWALS)
              .where(WITHDRAWALS.ID.eq(original.withdrawalId))
              .fetchOne()
              ?.get(WITHDRAWALS.ESTIMATED_COUNT)
        } else {
          null
        }

    return WithdrawalCreatedEventV3(
        accessionId = original.accessionId,
        batchId = original.batchId,
        date = original.date,
        destination = original.destination,
        estimatedCount = estimatedCount,
        facilityId = original.facilityId,
        notes = original.notes,
        organizationId = original.organizationId,
        purpose = original.purpose,
        staffResponsible = original.staffResponsible,
        viabilityTestId = original.viabilityTestId,
        withdrawalId = original.withdrawalId,
        withdrawnByUserId = original.withdrawnByUserId,
        withdrawnQuantity = original.withdrawnQuantity,
    )
  }

  fun getViabilityTestValuesMissingFromV1(
      viabilityTestId: ViabilityTestId,
      createdEventLogId: EventLogId,
  ): ViabilityTestValuesMissingFromV1 {
    val currentRow =
        dslContext
            .select(VIABILITY_TESTS.NOTES, VIABILITY_TESTS.STAFF_RESPONSIBLE)
            .from(VIABILITY_TESTS)
            .where(VIABILITY_TESTS.ID.eq(viabilityTestId))
            .fetchOne()

    val laterEdits =
        eventLogStore
            .fetchByIdsSince(
                mapOf(viabilityTestId to createdEventLogId),
                listOf(ViabilityTestUpdatedEvent::class),
            )
            .map { it.event }

    return ViabilityTestValuesMissingFromV1(
        notes =
            valueAtCreation(
                laterEdits,
                currentRow?.get(VIABILITY_TESTS.NOTES),
                { it.changedFrom.notes },
                { it.changedTo.notes },
            ),
        staffResponsible =
            valueAtCreation(
                laterEdits,
                currentRow?.get(VIABILITY_TESTS.STAFF_RESPONSIBLE),
                { it.changedFrom.staffResponsible },
                { it.changedTo.staffResponsible },
            ),
    )
  }

  data class ViabilityTestValuesMissingFromV1(
      val notes: String?,
      val staffResponsible: String?,
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
