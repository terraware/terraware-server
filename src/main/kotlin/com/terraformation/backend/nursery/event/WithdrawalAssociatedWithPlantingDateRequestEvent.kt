package com.terraformation.backend.nursery.event

import com.terraformation.backend.db.tracking.ScheduledPlantingDateId

data class WithdrawalAssociatedWithPlantingDateRequestEvent(
    val scheduledPlantingDateRequestId: ScheduledPlantingDateId,
)
