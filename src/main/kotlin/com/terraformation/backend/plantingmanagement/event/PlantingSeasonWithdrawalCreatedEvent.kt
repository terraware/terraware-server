package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils
import java.time.LocalDate

data class PlantingSeasonWithdrawalCreatedEventV1(
    val facilityId: FacilityId,
    val organizationId: OrganizationId,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val withdrawalDate: LocalDate,
    val withdrawalId: WithdrawalId,
) : UpgradableEvent {
  override fun toNextVersion(
      eventLogId: EventLogId,
      eventUpgradeUtils: EventUpgradeUtils,
  ): PlantingSeasonWithdrawalCreatedEventV2 =
      PlantingSeasonWithdrawalCreatedEventV2(
          facilityId = facilityId,
          nurseryWithdrawalId = withdrawalId,
          organizationId = organizationId,
          plantingSeasonId = plantingSeasonId,
          plantingSiteId = plantingSiteId,
          withdrawalDate = withdrawalDate,
      )
}

data class PlantingSeasonWithdrawalCreatedEventV2(
    val facilityId: FacilityId,
    val nurseryWithdrawalId: WithdrawalId,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val withdrawalDate: LocalDate,
) : EntityCreatedPersistentEvent, PlantingSeasonRelatedPersistentEvent

typealias PlantingSeasonWithdrawalCreatedEvent = PlantingSeasonWithdrawalCreatedEventV2
