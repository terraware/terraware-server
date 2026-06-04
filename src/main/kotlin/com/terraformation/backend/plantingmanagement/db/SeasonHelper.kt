package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class SeasonHelper(private val dslContext: DSLContext) {

  fun validateSeasonNotClosed(plantingSeasonId: PlantingSeasonId) {
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
}
