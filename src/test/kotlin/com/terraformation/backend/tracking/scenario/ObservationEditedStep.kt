package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS

class ObservationEditedStep(
    private val observationScenario: ObservationScenario,
    val scenario: ObservationScenario,
    val number: Int,
) : NodeWithChildren<ObservationEditedStep.Plot>() {
  lateinit var observationId: ObservationId

  fun plot(number: Number, init: Plot.() -> Unit) = initChild(Plot(number.toLong()), init)

  override fun prepare() {
    observationId =
        scenario.observationIds[number]
            ?: throw IllegalArgumentException("Observation $number edited but was never completed")
  }

  inner class Plot(val number: Long) : NodeWithChildren<Plot.Species>() {
    private lateinit var monitoringPlotId: MonitoringPlotId

    override fun prepare() {
      monitoringPlotId = observationScenario.getMonitoringPlotId(number)
      if (
          !observationScenario.test.dslContext.fetchExists(
              OBSERVATION_PLOTS,
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      ) {
        throw IllegalArgumentException(
            "Plot $number not in observation ${this@ObservationEditedStep.number}"
        )
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
      override fun prepare() {
        val certainty: RecordedSpeciesCertainty
        val speciesId: SpeciesId?
        val speciesName: String?

        when (number) {
          ObservationScenario.UNKNOWN -> {
            certainty = RecordedSpeciesCertainty.Unknown
            speciesId = null
            speciesName = null
          }
          ObservationScenario.OTHER -> {
            certainty = RecordedSpeciesCertainty.Other
            speciesId = null
            speciesName = "Other"
          }
          else -> {
            certainty = RecordedSpeciesCertainty.Known
            speciesId = observationScenario.getOrInsertSpecies(number)
            speciesName = null
          }
        }

        scenario.observationStore.updateMonitoringSpecies(
            observationId,
            monitoringPlotId,
            certainty,
            speciesId,
            speciesName,
        ) { model ->
          model.copy(
              totalDead = dead ?: model.totalDead,
              totalExisting = existing ?: model.totalExisting,
              totalLive = live ?: model.totalLive,
          )
        }
      }
    }
  }
}
