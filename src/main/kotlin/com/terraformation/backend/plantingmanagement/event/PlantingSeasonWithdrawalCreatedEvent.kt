package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import java.time.LocalDate

data class PlantingSeasonWithdrawalCreatedEventV1(
    val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val withdrawalDate: LocalDate,
    val withdrawalId: WithdrawalId,
) : EntityCreatedPersistentEvent, PlantingSeasonRelatedPersistentEvent

typealias PlantingSeasonWithdrawalCreatedEvent = PlantingSeasonWithdrawalCreatedEventV1
