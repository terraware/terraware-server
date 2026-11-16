package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.pojos.StratumPlotCountsRow
import com.terraformation.backend.db.tracking.tables.records.StratumPlotCountsRecord
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_COUNTS
import java.math.BigDecimal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DataIntegrityViolationException

internal class StratumPlotCountsTest : DatabaseTest() {
  @Test
  fun `stores all three count sets and deletes them with the stratum`() {
    insertUser()
    insertOrganization()
    insertPlantingSite()
    val stratumId = insertStratum(baselineNumTemporaryPlots = 0, biomassNumPermanentPlots = 7)

    val common =
        StratumPlotCountsRow(
            stratumId = stratumId,
            errorMargin = BigDecimal(10),
            numPermanentPlots = 18,
            numTemporaryPlots = 6,
            studentsT = BigDecimal("1.645"),
            variance = BigDecimal(850),
        )
    assertTableEquals(
        listOf(
            StratumPlotCountsRecord(common.copy(observationTypeId = ObservationType.Monitoring)),
            StratumPlotCountsRecord(
                common.copy(
                    observationTypeId = ObservationType.BiomassMeasurements,
                    numPermanentPlots = 7,
                )
            ),
            StratumPlotCountsRecord(
                common.copy(
                    observationTypeId = ObservationType.BaselineSurvey,
                    numPermanentPlots = null,
                    numTemporaryPlots = 0,
                )
            ),
        )
    )

    dslContext.deleteFrom(STRATA).where(STRATA.ID.eq(stratumId)).execute()

    assertTableEmpty(STRATUM_PLOT_COUNTS)
  }

  @ParameterizedTest
  @EnumSource(ObservationType::class)
  fun `only baseline surveys have a null permanent count`(observationType: ObservationType) {
    insertUser()
    insertOrganization()
    insertPlantingSite()
    insertStratum()

    assertThrows<DataIntegrityViolationException> {
      dslContext
          .update(STRATUM_PLOT_COUNTS)
          .set(
              STRATUM_PLOT_COUNTS.NUM_PERMANENT_PLOTS,
              if (observationType == ObservationType.BaselineSurvey) 1 else null,
          )
          .where(STRATUM_PLOT_COUNTS.OBSERVATION_TYPE_ID.eq(observationType))
          .execute()
    }
  }
}
