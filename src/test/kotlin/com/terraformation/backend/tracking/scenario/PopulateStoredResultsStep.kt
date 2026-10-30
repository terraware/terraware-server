package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSiteResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationStratumResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSubstratumResultsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import java.math.BigDecimal

class PopulateStoredResultsStep(
    private val scenario: ObservationScenario,
    val observation: Int,
    private val pending: Boolean,
) : NodeWithChildren<ScenarioNode>() {
  private val test = scenario.test
  private val dslContext = test.dslContext
  private val observationId = scenario.observationIds.getValue(observation)
  private val observationRow =
      dslContext.selectFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).fetchSingle()
  private val siteHistoryId = observationRow.plantingSiteHistoryId!!

  internal fun populatePendingResults() {
    site { counts(totalLive = 0) }

    val substratumHistoryIds = mutableSetOf<SubstratumHistoryId>()
    val stratumHistoryIds = mutableSetOf<StratumHistoryId>()
    dslContext
        .select(
            OBSERVATION_PLOTS.MONITORING_PLOT_ID,
            OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID,
            SUBSTRATUM_HISTORIES.ID,
            SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
            STRATUM_HISTORIES.ID,
            STRATUM_HISTORIES.STRATUM_ID,
        )
        .from(OBSERVATION_PLOTS)
        .join(MONITORING_PLOT_HISTORIES)
        .on(MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID))
        .leftJoin(SUBSTRATUM_HISTORIES)
        .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
        .leftJoin(STRATUM_HISTORIES)
        .on(STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
        .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
        .fetch()
        .forEach { row ->
          Plot(
                  plotHistoryId = row[OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID]!!,
                  plotId = row[OBSERVATION_PLOTS.MONITORING_PLOT_ID]!!,
              )
              .counts(totalLive = 0)
          row[SUBSTRATUM_HISTORIES.ID]?.let { substratumHistoryId ->
            if (substratumHistoryIds.add(substratumHistoryId)) {
              Substratum(
                      substratumHistoryId = substratumHistoryId,
                      substratumId = row[SUBSTRATUM_HISTORIES.SUBSTRATUM_ID],
                  )
                  .counts(totalLive = 0)
            }
          }
          row[STRATUM_HISTORIES.ID]?.let { stratumHistoryId ->
            if (stratumHistoryIds.add(stratumHistoryId)) {
              Stratum(
                      stratumHistoryId = stratumHistoryId,
                      stratumId = row[STRATUM_HISTORIES.STRATUM_ID],
                  )
                  .counts(totalLive = 0)
            }
          }
        }
  }

  fun site(init: Site.() -> Unit) = initChild(Site(), init)

  fun stratum(number: Int, init: Stratum.() -> Unit): Stratum {
    val stratumId = scenario.stratumIds.getValue(number)
    val stratumHistoryId =
        dslContext
            .select(STRATUM_HISTORIES.ID)
            .from(STRATUM_HISTORIES)
            .where(STRATUM_HISTORIES.STRATUM_ID.eq(stratumId))
            .and(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(siteHistoryId))
            .fetchSingle(STRATUM_HISTORIES.ID)!!
    return initChild(Stratum(stratumId, stratumHistoryId), init)
  }

  abstract inner class ExpectedResult : NodeWithChildren<ScenarioNode>() {
    abstract fun counts(
        totalLive: Int,
        totalDead: Int = 0,
        totalExisting: Int = 0,
        permanentLive: Int = 0,
        pending: Boolean = this@PopulateStoredResultsStep.pending,
    )

    fun species(
        number: Int,
        totalLive: Int = 0,
        totalDead: Int = 0,
        totalExisting: Int = 0,
        permanentLive: Int = 0,
    ) {
      val biomassSpecies =
          if (observationRow.observationTypeId == ObservationType.BiomassMeasurements) {
            scenario.biomassSpecies.getValue(observationId to number)
          } else {
            null
          }
      val speciesId =
          if (biomassSpecies != null) biomassSpecies.speciesId
          else scenario.getOrInsertSpecies(number)
      val certainty =
          if (speciesId == null) RecordedSpeciesCertainty.Other else RecordedSpeciesCertainty.Known
      val speciesName = if (speciesId == null) biomassSpecies?.scientificName else null
      insertSpecies(
          biomassSpeciesId = biomassSpecies?.id,
          certainty = certainty,
          permanentLive = permanentLive,
          speciesId = speciesId,
          speciesName = speciesName,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }

    protected abstract fun insertSpecies(
        biomassSpeciesId: BiomassSpeciesId?,
        certainty: RecordedSpeciesCertainty,
        speciesId: SpeciesId?,
        speciesName: String?,
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
    )
  }

  inner class Site : ExpectedResult() {
    override fun counts(
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
        pending: Boolean,
    ) {
      test.insertObservationSiteResult(
          observationId = observationId,
          permanentLive = permanentLive,
          plantingSiteHistoryId = siteHistoryId,
          plantingSiteId = scenario.plantingSiteId,
          row = ObservationSiteResultsRow(needsRecalculation = pending),
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }

    override fun insertSpecies(
        biomassSpeciesId: BiomassSpeciesId?,
        certainty: RecordedSpeciesCertainty,
        speciesId: SpeciesId?,
        speciesName: String?,
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
    ) {
      test.insertObservedSiteSpeciesTotals(
          biomassSpeciesId = biomassSpeciesId,
          certainty = certainty,
          observationId = observationId,
          permanentLive = permanentLive,
          plantingSiteHistoryId = siteHistoryId,
          plantingSiteId = scenario.plantingSiteId,
          speciesId = speciesId,
          speciesName = speciesName,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }
  }

  inner class Stratum(
      private val stratumId: StratumId?,
      private val stratumHistoryId: StratumHistoryId,
  ) : ExpectedResult() {
    fun substratum(number: Int, init: Substratum.() -> Unit): Substratum {
      val substratumId = scenario.substratumIds.getValue(number)
      val substratumHistoryId =
          dslContext
              .select(SUBSTRATUM_HISTORIES.ID)
              .from(SUBSTRATUM_HISTORIES)
              .where(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(substratumId))
              .and(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(stratumHistoryId))
              .fetchSingle(SUBSTRATUM_HISTORIES.ID)!!
      return initChild(Substratum(substratumId, substratumHistoryId), init)
    }

    override fun counts(
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
        pending: Boolean,
    ) {
      test.insertObservationStratumResult(
          observationId = observationId,
          permanentLive = permanentLive,
          row = ObservationStratumResultsRow(needsRecalculation = pending),
          stratumHistoryId = stratumHistoryId,
          stratumId = stratumId,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }

    override fun insertSpecies(
        biomassSpeciesId: BiomassSpeciesId?,
        certainty: RecordedSpeciesCertainty,
        speciesId: SpeciesId?,
        speciesName: String?,
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
    ) {
      test.insertObservedStratumSpeciesTotals(
          biomassSpeciesId = biomassSpeciesId,
          certainty = certainty,
          observationId = observationId,
          permanentLive = permanentLive,
          speciesId = speciesId,
          speciesName = speciesName,
          stratumHistoryId = stratumHistoryId,
          stratumId = stratumId,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }
  }

  inner class Substratum(
      private val substratumId: SubstratumId?,
      private val substratumHistoryId: SubstratumHistoryId,
  ) : ExpectedResult() {
    fun plot(
        number: Long,
        completed: Boolean,
        isPermanent: Boolean = false,
        init: Plot.() -> Unit,
    ): Plot {
      val plotId = scenario.getMonitoringPlotId(number)
      val plotHistoryId =
          dslContext
              .select(MONITORING_PLOT_HISTORIES.ID)
              .from(MONITORING_PLOT_HISTORIES)
              .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId))
              .and(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(substratumHistoryId))
              .fetchSingle(MONITORING_PLOT_HISTORIES.ID)!!
      return initChild(Plot(plotId, plotHistoryId, completed, isPermanent), init)
    }

    override fun counts(
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
        pending: Boolean,
    ) {
      test.insertObservationSubstratumResult(
          observationId = observationId,
          permanentLive = permanentLive,
          row = ObservationSubstratumResultsRow(needsRecalculation = pending),
          substratumHistoryId = substratumHistoryId,
          substratumId = substratumId,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }

    override fun insertSpecies(
        biomassSpeciesId: BiomassSpeciesId?,
        certainty: RecordedSpeciesCertainty,
        speciesId: SpeciesId?,
        speciesName: String?,
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
    ) {
      test.insertObservedSubstratumSpeciesTotals(
          biomassSpeciesId = biomassSpeciesId,
          certainty = certainty,
          observationId = observationId,
          permanentLive = permanentLive,
          speciesId = speciesId,
          speciesName = speciesName,
          substratumHistoryId = substratumHistoryId,
          substratumId = substratumId,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }
  }

  inner class Plot(
      private val plotId: MonitoringPlotId,
      private val plotHistoryId: MonitoringPlotHistoryId,
      private val completed: Boolean = false,
      private val isPermanent: Boolean = false,
  ) : ExpectedResult() {
    override fun prepare() {
      test.insertObservationPlot(
          completedTime = if (completed) scenario.clock.instant() else null,
          isPermanent = isPermanent,
          monitoringPlotHistoryId = plotHistoryId,
          monitoringPlotId = plotId,
          observationId = observationId,
      )
    }

    fun t0Density(species: Int, density: BigDecimal) {
      test.insertPlotT0Density(
          monitoringPlotId = plotId,
          plotDensity = density,
          speciesId = scenario.getOrInsertSpecies(species),
      )
    }

    override fun counts(
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
        pending: Boolean,
    ) {
      test.insertObservationPlotResult(
          monitoringPlotHistoryId = plotHistoryId,
          monitoringPlotId = plotId,
          observationId = observationId,
          permanentLive = permanentLive,
          row = ObservationPlotResultsRow(needsRecalculation = pending),
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }

    override fun insertSpecies(
        biomassSpeciesId: BiomassSpeciesId?,
        certainty: RecordedSpeciesCertainty,
        speciesId: SpeciesId?,
        speciesName: String?,
        totalLive: Int,
        totalDead: Int,
        totalExisting: Int,
        permanentLive: Int,
    ) {
      test.insertObservedPlotSpeciesTotals(
          biomassSpeciesId = biomassSpeciesId,
          certainty = certainty,
          monitoringPlotHistoryId = plotHistoryId,
          monitoringPlotId = plotId,
          observationId = observationId,
          permanentLive = permanentLive,
          speciesId = speciesId,
          speciesName = speciesName,
          totalDead = totalDead,
          totalExisting = totalExisting,
          totalLive = totalLive,
      )
    }
  }
}
