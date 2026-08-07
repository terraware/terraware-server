package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class PlantingSiteLocker(
    private val dslContext: DSLContext,
) {
  /**
   * Acquires a row lock on a planting site and executes a function in a transaction with the lock
   * held.
   */
  fun <T> withLockedPlantingSite(plantingSiteId: PlantingSiteId, func: () -> T): T {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    return dslContext.transactionResult { _ ->
      val rowsLocked =
          dslContext
              .selectOne()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(plantingSiteId))
              .forUpdate()
              .execute()

      if (rowsLocked != 1) {
        throw PlantingSiteNotFoundException(plantingSiteId)
      }

      func()
    }
  }
}
