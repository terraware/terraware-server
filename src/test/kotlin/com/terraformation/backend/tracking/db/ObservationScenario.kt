@file:Suppress("EnumEntryName")

package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.db.SiteOperation.created
import com.terraformation.backend.tracking.model.BaseMonitoringResult
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingSubzoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.util.toPlantsPerHectare
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals

@DslMarker annotation class ObservationScenarioMarker

interface ScenarioNode {
  /**
   * Called before the node's init function is invoked. Can be used to insert any database objects
   * that are prerequisites of child objects that would be inserted in the init function.
   */
  fun prepare(): Unit = Unit

  /**
   * Called after the node's init function is invoked. The [finish] methods, if any, of child
   * objects created in the init function will have already been called.
   */
  fun finish(): Unit = Unit
}

sealed interface ScenarioStep : ScenarioNode

enum class SiteOperation {
  created,
  edited,
}

@ObservationScenarioMarker
abstract class NodeWithChildren<T : ScenarioNode> : ScenarioNode {
  val children = mutableListOf<T>()

  fun <C : ScenarioNode> initAndAppend(
      child: C,
      list: MutableList<in C>,
      init: C.() -> Unit,
  ): C {
    child.prepare()
    child.init()
    child.finish()
    list.add(child)
    return child
  }

  fun <U : T> initChild(child: U, init: U.() -> Unit) = initAndAppend(child, children, init)
}

class ObservationScenario(
    val observationResultsStore: ObservationResultsStore,
    val observationStore: ObservationStore,
    val plantingSiteStore: PlantingSiteStore,
    val test: DatabaseBackedTest,
) : NodeWithChildren<ScenarioStep>() {
  val monitoringPlotIds = mutableMapOf<Long, MonitoringPlotId>()
  val monitoringPlotSubstratumIds = mutableMapOf<MonitoringPlotId, PlantingSubzoneId>()
  val observationIds = mutableMapOf<Int, ObservationId>()
  val speciesIds = mutableMapOf<Int, SpeciesId>()
  val monitoringPlotHistoryIds = mutableMapOf<MonitoringPlotId, MonitoringPlotHistoryId>()

  fun expect(observation: Int, init: ExpectedSiteResult.() -> Unit): ExpectedSiteResult {
    val observationId =
        observationIds[observation]
            ?: throw IllegalArgumentException(
                "Observation $observation was not declared before being referenced"
            )
    return initChild(
        ExpectedSiteResult(
            observation,
            observationResultsStore.fetchOneById(observationId, ObservationResultsDepth.Plant),
        ),
        init,
    )
  }

  fun observation(
      number: Int,
      init: ObservationCompleted.() -> Unit,
  ) = initChild(ObservationCompleted(number), init)

  fun site(operation: SiteOperation = created, init: Site.() -> Unit) =
      initChild(Site(operation), init)

  fun t0DensitySet(init: T0DensitySet.() -> Unit) = initChild(T0DensitySet(), init)

  private fun getOrInsertSpecies(number: Int): SpeciesId {
    return speciesIds.getOrPut(number) { test.insertSpecies("Species $number") }
  }

  private fun getMonitoringPlotId(number: Long): MonitoringPlotId =
      monitoringPlotIds[number]
          ?: throw IllegalArgumentException(
              "Monitoring plot $number was not declared before being referenced"
          )

  inner class Site(val operation: SiteOperation) : NodeWithChildren<Site.Stratum>(), ScenarioStep {
    lateinit var plantingSiteId: PlantingSiteId

    fun stratum(number: Int, init: Stratum.() -> Unit) = initChild(Stratum(number), init)

    override fun prepare() {
      with(this@ObservationScenario.test) {
        if (operation == created) {
          plantingSiteId = insertPlantingSite(boundary = rectangle(1))
        }
      }
    }

    inner class Stratum(val number: Int) : NodeWithChildren<Stratum.Substratum>() {
      var area: Int = 0

      lateinit var plantingZoneId: PlantingZoneId

      fun substratum(number: Int, area: Int = 1, init: Substratum.() -> Unit): Substratum {
        this.area += area
        return initChild(Substratum(number, area), init)
      }

      override fun prepare() {
        with(this@ObservationScenario.test) {
          if (this@Site.operation == created) {
            plantingZoneId = insertPlantingZone(name = "$number")
          }
        }
      }

      override fun finish() {
        with(this@ObservationScenario.test) {
          if (this@Site.operation == created) {
            dslContext
                .update(PLANTING_ZONES)
                .set(PLANTING_ZONES.AREA_HA, area.toBigDecimal())
                .where(PLANTING_ZONES.ID.eq(plantingZoneId))
                .execute()
          }
        }
      }

      inner class Substratum(val number: Int, val area: Int) : NodeWithChildren<Substratum.Plot>() {
        lateinit var substratumId: PlantingSubzoneId

        fun plot(plotNum: Long, init: Plot.() -> Unit = {}) = initChild(Plot(plotNum), init)

        override fun prepare() {
          with(this@ObservationScenario.test) {
            if (this@Site.operation == created) {
              substratumId =
                  insertPlantingSubzone(
                      fullName = "${this@Stratum.number}-$number",
                      name = "$number",
                      plantingCompletedTime = Instant.EPOCH,
                  )
            }
          }
        }

        @ObservationScenarioMarker
        inner class Plot(val number: Long) : ScenarioNode {
          lateinit var monitoringPlotId: MonitoringPlotId

          override fun prepare() {
            with(this@ObservationScenario.test) {
              if (this@Site.operation == created) {
                monitoringPlotId = insertMonitoringPlot(plotNumber = number)
                this@ObservationScenario.monitoringPlotIds[number] = monitoringPlotId
                this@ObservationScenario.monitoringPlotSubstratumIds[monitoringPlotId] =
                    this@Substratum.substratumId
                this@ObservationScenario.monitoringPlotHistoryIds[monitoringPlotId] =
                    inserted.monitoringPlotHistoryId
              }
            }
          }
        }
      }
    }
  }

  inner class T0DensitySet : NodeWithChildren<T0DensitySet.Plot>(), ScenarioStep {
    fun plot(number: Long, init: Plot.() -> Unit) = initChild(Plot(number), init)

    inner class Plot(val number: Long) : NodeWithChildren<Plot.Species>() {
      val monitoringPlotId = this@ObservationScenario.getMonitoringPlotId(number)

      fun species(speciesId: Int, density: Int, init: Species.() -> Unit = {}) =
          initChild(Species(speciesId, density), init)

      inner class Species(val number: Int, val density: Int) : ScenarioNode {
        override fun finish() {
          with(this@ObservationScenario.test) {
            insertPlotT0Density(
                monitoringPlotId = monitoringPlotId,
                plotDensity = density.toBigDecimal().toPlantsPerHectare(),
                speciesId = this@ObservationScenario.getOrInsertSpecies(number),
            )
          }
        }
      }
    }
  }

  inner class ObservationCompleted(val number: Int) :
      NodeWithChildren<ObservationCompleted.Plot>(), ScenarioStep {
    val requestedSubstratumIds = mutableSetOf<PlantingSubzoneId>()

    lateinit var observationId: ObservationId

    fun plot(
        number: Long,
        isPermanent: Boolean = true,
        conditions: Set<ObservableCondition> = emptySet(),
        init: Plot.() -> Unit = {},
    ) = initChild(Plot(number, isPermanent, conditions), init)

    override fun prepare() {
      with(this@ObservationScenario.test) {
        observationId = insertObservation()
        this@ObservationScenario.observationIds[number] = observationId
      }
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
        this@ObservationScenario.observationStore.completePlot(
            observationId = this@ObservationCompleted.observationId,
            monitoringPlotId = monitoringPlotId,
            conditions = conditions,
            notes = null,
            observedTime = Instant.EPOCH,
            plants = children.flatMap { species -> species.toRecordedPlantsRows() },
        )
      }

      override fun prepare() {
        with(this@ObservationScenario.test) {
          monitoringPlotId = this@ObservationScenario.getMonitoringPlotId(number)
          insertObservationPlot(
              claimedBy = currentUser().userId,
              isPermanent = isPermanent,
              monitoringPlotId = monitoringPlotId,
              monitoringPlotHistoryId =
                  this@ObservationScenario.monitoringPlotHistoryIds[monitoringPlotId]!!,
          )

          val substratumId =
              this@ObservationScenario.monitoringPlotSubstratumIds[monitoringPlotId]!!
          if (substratumId !in this@ObservationCompleted.requestedSubstratumIds) {
            insertObservationRequestedSubzone(plantingSubzoneId = substratumId)
            this@ObservationCompleted.requestedSubstratumIds.add(substratumId)
          }
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
                monitoringPlotId = this@Plot.monitoringPlotId,
                observationId = this@ObservationCompleted.observationId,
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
              speciesId = this@ObservationScenario.getOrInsertSpecies(number)
            }
          }
        }

        infix fun dead(value: Int) = apply { dead = value }

        infix fun existing(value: Int) = apply { existing = value }

        infix fun live(value: Int) = apply { live = value }
      }
    }
  }

  @ObservationScenarioMarker
  abstract class ExpectedResult<T : BaseMonitoringResult>(
      val name: String,
      val scenario: ObservationScenario,
      val actualResult: T,
      var survivalRate: Int? = null,
  ) : NodeWithChildren<ExpectedResult.Species>() {
    infix fun survivalRate(expected: Int?) = apply {
      survivalRate = expected
      assertEquals(expected, actualResult.survivalRate, "$name survival rate")
    }

    fun species(
        number: Int,
        survivalRate: Int? = null,
        init: Species.() -> Unit = {},
    ): Species {
      val speciesId = scenario.getOrInsertSpecies(number)
      return initChild(
          Species(
              name,
              number,
              actualResult.species.firstOrNull { it.speciesId == speciesId },
              survivalRate,
          ),
          init,
      )
    }

    class Species(
        val parentName: String,
        val number: Int,
        val speciesResult: ObservationSpeciesResultsModel?,
        val survivalRate: Int?,
    ) : ScenarioNode {
      override fun finish() {
        assertEquals(
            survivalRate,
            speciesResult?.survivalRate,
            "$parentName species $number survival rate",
        )
      }
    }
  }

  inner class ExpectedSiteResult(
      val observation: Int,
      actualResult: ObservationResultsModel,
      survivalRate: Int? = null,
  ) :
      ExpectedResult<ObservationResultsModel>(
          "Site",
          this@ObservationScenario,
          actualResult,
          survivalRate,
      ),
      ScenarioStep {
    val strata = mutableListOf<Stratum>()

    fun stratum(number: Int, survivalRate: Int? = null, init: Stratum.() -> Unit) =
        initAndAppend(Stratum(number, survivalRate), strata, init)

    inner class Stratum(val number: Int, survivalRate: Int? = null) :
        ExpectedResult<ObservationPlantingZoneResultsModel>(
            "Stratum $number",
            this@ObservationScenario,
            actualResult.plantingZones.first { it.name == "$number" },
            survivalRate,
        ) {
      val substrata = mutableListOf<Substratum>()

      fun substratum(number: Int, survivalRate: Int? = null, init: Substratum.() -> Unit) =
          initAndAppend(Substratum(number, survivalRate), substrata, init)

      inner class Substratum(val number: Int, survivalRate: Int?) :
          ExpectedResult<ObservationPlantingSubzoneResultsModel>(
              "$name substratum $number",
              this@ObservationScenario,
              actualResult.plantingSubzones.first { it.name == "$number" },
              survivalRate,
          ) {
        val plots = mutableListOf<Plot>()

        fun plot(number: Long, survivalRate: Int? = null, init: Plot.() -> Unit) =
            initAndAppend(
                Plot(number, resultForPlot(number), survivalRate),
                plots,
                init,
            )

        private fun resultForPlot(number: Long) =
            actualResult.monitoringPlots.firstOrNull { it.monitoringPlotNumber == number }
                ?: throw IllegalArgumentException(
                    "No result for $name plot $number in observation ${this@ExpectedSiteResult.observation}"
                )

        inner class Plot(
            val number: Long,
            actualResult: ObservationMonitoringPlotResultsModel,
            survivalRate: Int?,
        ) :
            ExpectedResult<ObservationMonitoringPlotResultsModel>(
                "$name plot $number",
                this@ObservationScenario,
                actualResult,
                survivalRate,
            )
      }
    }
  }
}
