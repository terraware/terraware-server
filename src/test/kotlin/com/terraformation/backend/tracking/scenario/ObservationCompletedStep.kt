package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedTreesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.point
import java.math.BigDecimal
import org.jooq.impl.DSL

class ObservationCompletedStep(
    val scenario: ObservationScenario,
    val number: Int,
    val type: ObservationType = ObservationType.Monitoring,
    val isAdHoc: Boolean = false,
    val recalculate: Boolean = true,
    val initialState: ObservationState = ObservationState.InProgress,
) : NodeWithChildren<ObservationCompletedStep.Plot>() {
  private val test: DatabaseBackedTest
    get() = scenario.test

  val requestedSubstratumIds = mutableSetOf<SubstratumId>()

  lateinit var observationId: ObservationId

  fun plot(
      number: Number,
      isPermanent: Boolean = true,
      conditions: Set<ObservableCondition> = emptySet(),
      status: ObservationPlotStatus = ObservationPlotStatus.Completed,
      init: Plot.() -> Unit = {},
  ) = initChild(Plot(number.toLong(), isPermanent, conditions, status), init)

  fun biomassSpecies(
      number: Int,
      scientificName: String? = null,
      commonName: String? = null,
      isInvasive: Boolean = false,
      isThreatened: Boolean = false,
  ) {
    require(type == ObservationType.BiomassMeasurements) { "Not a biomass observation" }
    val species =
        ObservationBiomassSpeciesRow(
            commonName = commonName,
            isInvasive = isInvasive,
            isThreatened = isThreatened,
            observationId = observationId,
            scientificName = scientificName,
            speciesId = if (scientificName == null) scenario.getOrInsertSpecies(number) else null,
        )
    val id = test.insertObservationBiomassSpecies(species)
    scenario.biomassSpecies[observationId to number] = species.copy(id = id)
  }

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
            completedTime =
                if (initialState == ObservationState.Completed) scenario.clock.instant() else null,
            isAdHoc = isAdHoc,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = scenario.plantingSiteId,
            state = initialState,
            observationType = type,
        )
    scenario.observationIds[number] = observationId
  }

  override fun finish() {
    // We need to complete all the plots here, rather than immediately completing them as they're
    // declared. Otherwise the observation would be marked complete after the first plot was
    // finished (since it will look like the last incomplete one).
    children.filter { it.status == ObservationPlotStatus.Completed }.forEach { it.completePlot() }
    if (recalculate) {
      scenario.observationResultsRecalculator.recalculateAllSites()
    }
  }

  inner class Plot(
      val number: Long,
      val isPermanent: Boolean,
      val conditions: Set<ObservableCondition>,
      val status: ObservationPlotStatus,
  ) : NodeWithChildren<Plot.Species>() {
    private lateinit var monitoringPlotId: MonitoringPlotId

    fun completePlot() {
      scenario.observationStore.completePlot(
          observationId = observationId,
          monitoringPlotId = monitoringPlotId,
          conditions = conditions,
          notes = null,
          observedTime = scenario.clock.instant(),
          plants =
              if (type == ObservationType.Monitoring) {
                children.flatMap { species -> species.toRecordedPlantsRows() }
              } else {
                emptyList()
              },
      )
    }

    override fun prepare() {
      monitoringPlotId = scenario.getMonitoringPlotId(number)
      test.insertObservationPlot(
          claimedBy = if (status == ObservationPlotStatus.Unclaimed) null else currentUser().userId,
          isPermanent = isPermanent,
          monitoringPlotId = monitoringPlotId,
          monitoringPlotHistoryId = scenario.monitoringPlotHistoryIds[monitoringPlotId]!!,
          observationId = observationId,
          statusId =
              if (status == ObservationPlotStatus.Completed) ObservationPlotStatus.Claimed
              else status,
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
    ): Species {
      require(type == ObservationType.Monitoring) { "Not a plant monitoring observation" }
      return initChild(Species(number, existing, live, dead), init)
    }

    fun tree(
        species: Int,
        treeNumber: Int? = null,
        trunkNumber: Int? = null,
        growthForm: TreeGrowthForm = TreeGrowthForm.Tree,
        isDead: Boolean = false,
        diameterAtBreastHeightCm: BigDecimal? =
            if (growthForm == TreeGrowthForm.Shrub) null else BigDecimal.TEN,
        pointOfMeasurementM: BigDecimal? =
            if (growthForm == TreeGrowthForm.Shrub) null else BigDecimal("1.3"),
        shrubDiameterCm: Int? = if (growthForm == TreeGrowthForm.Shrub) 10 else null,
    ): RecordedTreeId {
      require(type == ObservationType.BiomassMeasurements) { "Not a biomass observation" }
      return test.insertRecordedTree(
          row = RecordedTreesRow(treeNumber = treeNumber, trunkNumber = trunkNumber),
          biomassSpeciesId =
              scenario.getBiomassSpeciesId(this@ObservationCompletedStep.number, species),
          diameterAtBreastHeightCm = diameterAtBreastHeightCm,
          isDead = isDead,
          monitoringPlotId = monitoringPlotId,
          observationId = observationId,
          pointOfMeasurementM = pointOfMeasurementM,
          shrubDiameterCm = shrubDiameterCm,
          treeGrowthForm = growthForm,
      )
    }

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
          ObservationScenario.UNKNOWN -> {
            certainty = RecordedSpeciesCertainty.Unknown
          }
          ObservationScenario.OTHER -> {
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
