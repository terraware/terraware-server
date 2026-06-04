package com.terraformation.backend.plantingmanagement.util

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonClosedException
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonNotFoundException
import org.jooq.DSLContext

fun validateSeasonNotClosed(dslContext: DSLContext, plantingSeasonId: PlantingSeasonId) {
  with(PLANTING_SEASONS) {
    val status =
        dslContext
            .select(STATUS_ID)
            .from(PLANTING_SEASONS)
            .where(ID.eq(plantingSeasonId))
            .fetchOne(STATUS_ID) ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

    if (status == PlantingSeasonStatus.Closed) {
      throw PlantingSeasonClosedException(plantingSeasonId)
    }
  }
}
