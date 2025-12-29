package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.point
import org.jooq.impl.DSL

class ObservationCompletedStep(val scenario: ObservationScenario, val number: Int) :
    NodeWithChildren<ObservationCompletedStep.Plot>() {
  private val test: DatabaseBackedTest
    get() = scenario.test

  val requestedSubstratumIds = mutableSetOf<SubstratumId>()

  lateinit var observationId: ObservationId

  fun plot(
      number: Long,
      isPermanent: Boolean = true,
      conditions: Set<ObservableCondition> = emptySet(),
      init: Plot.() -> Unit = {},
  ) = initChild(Plot(number, isPermanent, conditions), init)

  override fun prepare() {
    val plantingSiteHistoryId =
        test.dslContext
            .select(DSL.max(PLANTING_SITE_HISTORIES.ID))
            .from(PLANTING_SITE_HISTORIES)
            .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(scenario.plantingSiteId))
            .fetchSingle()
            .value1()!!
    observationId =
        test.insertObservation(
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = scenario.plantingSiteId,
            state = ObservationState.InProgress,
        )
    scenario.observationIds[number] = observationId
  }

  override fun finish() {
    // We need to complete all the plots here, rather than immediately completing them as they're
    // declared. Otherwise the observation would be marked complete after the first plot was
    // finished (since it will look like the last incomplete one).
    children.forEach { it.completePlot() }
  }

  inner class Plot(
      val number: Long,
      val isPermanent: Boolean,
      val conditions: Set<ObservableCondition>,
  ) : NodeWithChildren<Plot.Species>() {
    private lateinit var monitoringPlotId: MonitoringPlotId

    val unknown = -1
    val other = -2

    fun completePlot() {
      scenario.observationStore.completePlot(
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          conditions = conditions,
          notes = null,
          observedTime = scenario.clock.instant(),
          plants = children.flatMap { species -> species.toRecordedPlantsRows() },
      )
    }

    override fun prepare() {
      monitoringPlotId = scenario.getMonitoringPlotId(number)
      test.insertObservationPlot(
          claimedBy = currentUser().userId,
          isPermanent = isPermanent,
          monitoringPlotId = monitoringPlotId,
          monitoringPlotHistoryId = scenario.monitoringPlotHistoryIds[monitoringPlotId]!!,
      )

      val substratumId = scenario.monitoringPlotSubstratumIds[monitoringPlotId]!!
      if (substratumId !in requestedSubstratumIds) {
        test.insertObservationRequestedSubstratum(substratumId = substratumId)
        requestedSubstratumIds.add(substratumId)
      }
    }

    fun species(
        number: Int,
        existing: Int? = null,
        live: Int? = null,
        dead: Int? = null,
        init: Species.() -> Unit = {},
    ) = initChild(Species(number, existing, live, dead), init)

    inner class Species(
        val number: Int,
        var existing: Int? = null,
        var live: Int? = null,
        var dead: Int? = null,
    ) : ScenarioNode {
      private lateinit var certainty: RecordedSpeciesCertainty
      private var speciesId: SpeciesId? = null
      private var speciesName: String? = null

      fun toRecordedPlantsRows(): List<RecordedPlantsRow> {
        return toRecordedPlantsRows(RecordedPlantStatus.Dead, dead) +
            toRecordedPlantsRows(RecordedPlantStatus.Existing, existing) +
            toRecordedPlantsRows(RecordedPlantStatus.Live, live)
      }

      private fun toRecordedPlantsRows(
          status: RecordedPlantStatus,
          count: Int?,
      ): List<RecordedPlantsRow> {
        return (0..<(count ?: 0)).map { toRecordedPlantsRow(status) }
      }

      private fun toRecordedPlantsRow(status: RecordedPlantStatus) =
          RecordedPlantsRow(
              certaintyId = certainty,
              gpsCoordinates = point(1),
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              speciesId = speciesId,
              speciesName = speciesName,
              statusId = status,
          )

      override fun prepare() {
        when (number) {
          unknown -> {
            certainty = RecordedSpeciesCertainty.Unknown
          }
          other -> {
            certainty = RecordedSpeciesCertainty.Other
            speciesName = "Other"
          }
          else -> {
            certainty = RecordedSpeciesCertainty.Known
            speciesId = scenario.getOrInsertSpecies(number)
          }
        }
      }

      infix fun dead(value: Int) = apply { dead = value }

      infix fun existing(value: Int) = apply { existing = value }

      infix fun live(value: Int) = apply { live = value }
    }
  }
}
