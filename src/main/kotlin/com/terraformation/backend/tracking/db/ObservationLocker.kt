package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ObservationModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ObservationLocker(val dslContext: DSLContext) {
  /**
   * Locks an observation and calls a function. Starts a database transaction; the function is
   * called with the transaction open, such that the lock is held while the function runs.
   */
  fun <T> withLockedObservation(
      observationId: ObservationId,
      func: (ExistingObservationModel) -> T,
  ): T {
    requirePermissions { updateObservation(observationId) }

    return dslContext.transactionResult { _ ->
      val model =
          dslContext
              .select(OBSERVATIONS.asterisk(), ObservationStore.requestedSubzoneIdsField)
              .from(OBSERVATIONS)
              .where(OBSERVATIONS.ID.eq(observationId))
              .forUpdate()
              .of(OBSERVATIONS)
              .fetchOne { ObservationModel.of(it, ObservationStore.requestedSubzoneIdsField) }
              ?: throw ObservationNotFoundException(observationId)

      func(model)
    }
  }
}
