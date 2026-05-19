package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import jakarta.inject.Named
import java.time.InstantSource
import java.time.ZoneOffset
import org.jooq.DSLContext

@Named
class PlantingSeasonStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun create(newModel: NewPlantingSeasonModel): PlantingSeasonId {
    requirePermissions { createPlantingSeason(newModel.plantingSiteId) }

    val userId = currentUser().userId
    val now = clock.instant()
    val today = now.atZone(ZoneOffset.UTC).toLocalDate()
    val status =
        when {
          today < newModel.startDate -> PlantingSeasonStatus.Upcoming
          today <= newModel.endDate -> PlantingSeasonStatus.Active
          else -> PlantingSeasonStatus.PastEndDate
        }

    return with(PLANTING_SEASONS) {
      dslContext
          .insertInto(PLANTING_SEASONS)
          .set(NAME, newModel.name)
          .set(PLANTING_SITE_ID, newModel.plantingSiteId)
          .set(START_DATE, newModel.startDate)
          .set(END_DATE, newModel.endDate)
          .set(STATUS_ID, status)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, now)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .onConflictDoNothing()
          .returning(ID)
          .fetchOne(ID)
          ?: throw PlantingSeasonExistsException(newModel.plantingSiteId, newModel.name)
    }
  }
}
