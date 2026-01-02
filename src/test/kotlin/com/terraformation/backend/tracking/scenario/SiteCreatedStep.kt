package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.util.toMultiPolygon
import java.time.Instant
import org.locationtech.jts.geom.MultiPolygon

class SiteCreatedStep(
    val scenario: ObservationScenario,
    val survivalRateIncludesTempPlots: Boolean,
) : NodeWithChildren<SiteCreatedStep.Stratum>() {
  var boundary: MultiPolygon = rectangle(0)

  private val test: DatabaseBackedTest
    get() = scenario.test

  fun stratum(number: Int, init: Stratum.() -> Unit) = initChild(Stratum(number), init)

  override fun prepare() {
    scenario.plantingSiteId =
        test.insertPlantingSite(
            boundary = rectangle(1),
            gridOrigin = point(0),
            survivalRateIncludesTempPlots = survivalRateIncludesTempPlots,
        )
  }

  override fun finish() {
    children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }

    test.dslContext
        .update(PLANTING_SITES)
        .set(PLANTING_SITES.BOUNDARY, boundary)
        .where(PLANTING_SITES.ID.eq(scenario.plantingSiteId))
        .execute()
  }

  inner class Stratum(val number: Int) : NodeWithChildren<Stratum.Substratum>() {
    var boundary: MultiPolygon = rectangle(0)
    var area: Int = 0

    lateinit var stratumId: StratumId

    fun substratum(number: Int, area: Int = 1, init: Substratum.() -> Unit): Substratum {
      this.area += area
      return initChild(Substratum(number, area), init)
    }

    override fun prepare() {
      stratumId = test.insertStratum(name = "$number")
      scenario.stratumIds[number] = stratumId
    }

    override fun finish() {
      children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }

      test.dslContext
          .update(STRATA)
          .set(STRATA.AREA_HA, area.toBigDecimal())
          .set(STRATA.BOUNDARY, boundary)
          .where(STRATA.ID.eq(stratumId))
          .execute()
    }

    inner class Substratum(val number: Int, val area: Int) : NodeWithChildren<Substratum.Plot>() {
      var boundary: MultiPolygon = rectangle(0)
      lateinit var substratumId: SubstratumId

      fun plot(plotNum: Long, permanentIndex: Int? = plotNum.toInt(), init: Plot.() -> Unit = {}) =
          initChild(Plot(plotNum, permanentIndex), init)

      override fun prepare() {
        substratumId =
            test.insertSubstratum(
                fullName = "${this@Stratum.number}-$number",
                name = "$number",
                plantingCompletedTime = Instant.EPOCH,
                stableId = "$number",
            )
      }

      override fun finish() {
        children.forEach { boundary = boundary.union(it.boundary).toMultiPolygon() }

        test.dslContext
            .update(SUBSTRATA)
            .set(SUBSTRATA.BOUNDARY, boundary)
            .where(SUBSTRATA.ID.eq(substratumId))
            .execute()
      }

      inner class Plot(val number: Long, val permanentIndex: Int?) : ScenarioNode {
        val boundary =
            rectanglePolygon(width = MONITORING_PLOT_SIZE, x = number * MONITORING_PLOT_SIZE * 2)
        lateinit var monitoringPlotId: MonitoringPlotId

        override fun prepare() {
          monitoringPlotId =
              test.insertMonitoringPlot(
                  boundary = boundary,
                  plotNumber = number,
                  permanentIndex = permanentIndex,
              )
          scenario.monitoringPlotIds[number] = monitoringPlotId
          scenario.monitoringPlotSubstratumIds[monitoringPlotId] = substratumId
          scenario.monitoringPlotHistoryIds[monitoringPlotId] =
              test.inserted.monitoringPlotHistoryId
        }
      }
    }
  }
}
