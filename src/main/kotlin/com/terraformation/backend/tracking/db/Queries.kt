package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_REQUESTED_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import org.jooq.Field
import org.jooq.impl.DSL

fun latestObservationForSubzoneField(observationIdField: Field<ObservationId?>) =
    with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
      DSL.select(OBSERVATIONS.ID)
          .from(OBSERVATIONS)
          .join(OBSERVATION_REQUESTED_SUBZONES)
          .on(OBSERVATIONS.ID.eq(OBSERVATION_REQUESTED_SUBZONES.OBSERVATION_ID))
          .where(OBSERVATION_REQUESTED_SUBZONES.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_ID))
          .and(
              OBSERVATIONS.COMPLETED_TIME.le(
                  DSL.select(OBSERVATIONS.COMPLETED_TIME)
                      .from(OBSERVATIONS)
                      .where(OBSERVATIONS.ID.eq(observationIdField))
              )
          )
          .and(OBSERVATIONS.IS_AD_HOC.isFalse)
          .orderBy(
              // Prefer results from the same observation as the `observationId` if it exists.
              // True is considered greater than false, so sorting by an "=" expression in
              // descending order means the matches come first.
              OBSERVATION_ID.eq(observationIdField).desc(),
              // Otherwise take the results from the next latest observation for that area.
              OBSERVATIONS.COMPLETED_TIME.desc(),
          )
          .limit(1)
    }
