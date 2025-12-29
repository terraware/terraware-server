package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.NewStratumModel
import com.terraformation.backend.tracking.model.NewSubstratumModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.util.toMultiPolygon
import org.locationtech.jts.geom.MultiPolygon

class SiteEditedStep(
    val scenario: ObservationScenario,
    val survivalRateIncludesTempPlots: Boolean,
) : NodeWithChildren<SiteEditedStep.Stratum>() {
  var boundary: MultiPolygon = rectangle(0)

  private val test: DatabaseBackedTest
    get() = scenario.test

  fun stratum(number: Int, init: Stratum.() -> Unit) = initChild(Stratum(number), init)

  override fun finish() {
    children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }

    val existing =
        scenario.plantingSiteStore.fetchSiteById(scenario.plantingSiteId, PlantingSiteDepth.Plot)
    val desired =
        PlantingSiteModel.create(
                boundary = boundary,
                gridOrigin = point(0),
                name = existing.name,
                organizationId = existing.organizationId,
                strata = children.map { it.toModel() },
            )
            .copy(survivalRateIncludesTempPlots = survivalRateIncludesTempPlots)

    val edits = PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()

    scenario.plantingSiteStore.applyPlantingSiteEdit(edits)

    scenario.monitoringPlotHistoryIds.clear()
    scenario.monitoringPlotSubstratumIds.clear()

    with(MONITORING_PLOT_HISTORIES) {
      test.dslContext
          .select(MONITORING_PLOT_ID, ID, SUBSTRATUM_ID)
          .from(MONITORING_PLOT_HISTORIES)
          .fetch()
          .forEach { (monitoringPlotId, monitoringPlotHistoryId, substratumId) ->
            scenario.monitoringPlotHistoryIds[monitoringPlotId!!] = monitoringPlotHistoryId!!
            if (substratumId != null) {
              scenario.monitoringPlotSubstratumIds[monitoringPlotId] = substratumId
            }
          }
    }
  }

  inner class Stratum(val number: Int) : NodeWithChildren<Stratum.Substratum>() {
    var boundary: MultiPolygon = rectangle(0)
    var area: Int = 0

    lateinit var stratumId: StratumId

    fun substratum(number: Int, area: Int = 1, init: Substratum.() -> Unit): Substratum {
      this.area += area
      return initChild(Substratum(number, area), init)
    }

    override fun finish() {
      children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }
    }

    fun toModel() =
        NewStratumModel.create(
            boundary = boundary,
            name = "$number",
            stableId = StableId("$number"),
            substrata = children.map { it.toModel() },
        )

    inner class Substratum(val number: Int, val area: Int) : NodeWithChildren<Substratum.Plot>() {
      var boundary: MultiPolygon = rectangle(0)
      lateinit var substratumId: SubstratumId

      fun plot(plotNum: Long, init: Plot.() -> Unit = {}) = initChild(Plot(plotNum), init)

      override fun finish() {
        children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }
      }

      fun toModel() =
          NewSubstratumModel.create(
              boundary = boundary,
              fullName = "${this@Stratum.number}-$number",
              name = "$number",
              monitoringPlots = children.map { it.toModel() },
              stableId = StableId("${this@Stratum.number}-$number"),
          )

      inner class Plot(val number: Long) : ScenarioNode {
        val boundary =
            rectanglePolygon(width = MONITORING_PLOT_SIZE, x = number * MONITORING_PLOT_SIZE * 2)
        lateinit var monitoringPlotId: MonitoringPlotId

        override fun prepare() {
          if (number in scenario.monitoringPlotIds) {
            monitoringPlotId = scenario.monitoringPlotIds[number]!!
          } else {
            monitoringPlotId =
                test.insertMonitoringPlot(
                    plotNumber = number,
                    boundary = boundary,
                    substratumId = null,
                )
            scenario.monitoringPlotIds[number] = monitoringPlotId
            scenario.monitoringPlotHistoryIds[monitoringPlotId] =
                test.inserted.monitoringPlotHistoryId
          }
        }

        fun toModel() =
            MonitoringPlotModel(
                boundary = boundary,
                elevationMeters = null,
                id = monitoringPlotId,
                isAdHoc = false,
                isAvailable = true,
                permanentIndex = number.toInt(),
                plotNumber = number,
                sizeMeters = MONITORING_PLOT_SIZE_INT,
            )
      }
    }
  }
}
