package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.ObservationModel
import java.time.InstantSource
import javax.inject.Named
import org.jooq.DSLContext

@Named
class ObservationStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val observationsDao: ObservationsDao,
) {
  fun fetchObservationsByPlantingSite(
      plantingSiteId: PlantingSiteId
  ): List<ExistingObservationModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return dslContext
        .selectFrom(OBSERVATIONS)
        .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
        .orderBy(OBSERVATIONS.START_DATE, OBSERVATIONS.ID)
        .fetch { ObservationModel.of(it) }
  }

  fun createObservation(newModel: NewObservationModel): ObservationId {
    requirePermissions { createObservation(newModel.plantingSiteId) }

    val row =
        ObservationsRow(
            createdTime = clock.instant(),
            endDate = newModel.endDate,
            plantingSiteId = newModel.plantingSiteId,
            startDate = newModel.startDate,
            stateId = ObservationState.Upcoming,
        )

    observationsDao.insert(row)

    return row.id!!
  }
}
