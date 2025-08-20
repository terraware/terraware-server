package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class DeliveryModel(
    val createdTime: Instant,
    val id: DeliveryId,
    val plantings: List<PlantingModel>,
    val plantingSiteId: PlantingSiteId,
    val withdrawalId: WithdrawalId,
) {
  constructor(
      record: Record,
      plantingsMultisetField: Field<List<PlantingModel>>,
  ) : this(
      record[DELIVERIES.CREATED_TIME]!!,
      record[DELIVERIES.ID]!!,
      record[plantingsMultisetField],
      record[DELIVERIES.PLANTING_SITE_ID]!!,
      record[DELIVERIES.WITHDRAWAL_ID]!!,
  )
}
