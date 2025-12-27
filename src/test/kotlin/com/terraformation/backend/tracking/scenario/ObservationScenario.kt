package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.StrataDao
import com.terraformation.backend.db.tracking.tables.daos.SubstrataDao
import com.terraformation.backend.gis.CountryDetector
import com.terraformation.backend.tracking.db.ObservationLocker
import com.terraformation.backend.tracking.db.ObservationResultsStore
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import java.time.temporal.ChronoUnit
import org.jooq.Configuration

/**
 * Top-level element of the observation scenario testing DSL. This is intended to make it easy to
 * specify test scenarios in reasonably human-readable form, such that they can be reviewed by
 * someone who doesn't have deep Kotlin knowledge.
 *
 * The DSL structures scenarios as a sequence of top-level steps, each of which can be composed of
 * smaller steps internally. Steps are executed in order, and the implementation tries, whenever
 * possible, to do the requested work (e.g., inserting into the database) synchronously such that
 * failures have meaningful stack traces that indicate which specific step failed.
 *
 * In addition to immediately doing the requested work, the DSL builds up an in-memory data
 * structure that holds IDs and other information about the work that's been done. This allows the
 * current state of the scenario, including the results of previous steps, to be inspected in a
 * debugger or introspected by test code.
 *
 * Though a scenario using this DSL will often look like it's being specified declaratively (and
 * that's one of the DSL's design goals), note that it's actually just Kotlin code. Tests are free
 * to intersperse DSL function calls with other arbitrary code as needed, e.g., if they want to
 * update a value in the database in a way that the DSL doesn't support, or if they want to specify
 * an expected value using an expression rather than a hardwired constant.
 *
 * Nested elements of the DSL are implemented as inner classes, with the nesting mirroring the
 * nesting of elements in scenarios. We do not use Kotlin's `DslMarker` annotation, since that
 * annotation makes it awkward for inner classes to access properties of their enclosing classes.
 */
class ObservationScenario(
    val clock: TestClock,
    val observationResultsStore: ObservationResultsStore,
    val observationStore: ObservationStore,
    val plantingSiteStore: PlantingSiteStore,
    val test: DatabaseBackedTest,
) : NodeWithChildren<ScenarioNode>() {
  companion object {
    fun forTest(
        test: DatabaseBackedTest,
        clock: TestClock = TestClock(),
        eventPublisher: TestEventPublisher = TestEventPublisher(),
        identifierGenerator: IdentifierGenerator = IdentifierGenerator(clock, test.dslContext),
        observationResultsStore: ObservationResultsStore = ObservationResultsStore(test.dslContext),
        observationLocker: ObservationLocker = ObservationLocker(test.dslContext),
        parentStore: ParentStore = ParentStore(test.dslContext),
        configuration: Configuration = test.dslContext.configuration(),
        observationStore: ObservationStore =
            ObservationStore(
                clock,
                test.dslContext,
                eventPublisher,
                observationLocker,
                ObservationsDao(configuration),
                ObservationPlotConditionsDao(configuration),
                ObservationPlotsDao(configuration),
                ObservationRequestedSubstrataDao(configuration),
                parentStore,
            ),
        plantingSiteStore: PlantingSiteStore =
            PlantingSiteStore(
                clock,
                CountryDetector(),
                test.dslContext,
                eventPublisher,
                identifierGenerator,
                MonitoringPlotsDao(configuration),
                parentStore,
                PlantingSeasonsDao(configuration),
                PlantingSitesDao(configuration),
                eventPublisher,
                StrataDao(configuration),
                SubstrataDao(configuration),
            ),
    ): ObservationScenario {
      return ObservationScenario(
          clock,
          observationResultsStore,
          observationStore,
          plantingSiteStore,
          test,
      )
    }
  }

  val monitoringPlotHistoryIds = mutableMapOf<MonitoringPlotId, MonitoringPlotHistoryId>()
  val monitoringPlotIds = mutableMapOf<Long, MonitoringPlotId>()
  val monitoringPlotSubstratumIds = mutableMapOf<MonitoringPlotId, SubstratumId>()
  val observationIds = mutableMapOf<Int, ObservationId>()
  val speciesIds = mutableMapOf<Int, SpeciesId>()
  val stratumIds = mutableMapOf<Int, StratumId>()
  lateinit var plantingSiteId: PlantingSiteId

  fun expectResults(
      observation: Int,
      baseline: ExpectedSiteResultStep? = null,
      init: ExpectedSiteResultStep.() -> Unit,
  ): ExpectedSiteResultStep {
    val observationId =
        observationIds[observation]
            ?: throw IllegalArgumentException(
                "Observation $observation was not declared before being referenced"
            )
    return initChild(
        ExpectedSiteResultStep(
            this,
            observation,
            observationResultsStore.fetchOneById(observationId, ObservationResultsDepth.Plant),
            baseline = baseline,
        ),
        init,
    )
  }

  fun observation(
      number: Int,
      init: ObservationCompletedStep.() -> Unit,
  ) = initChild(ObservationCompletedStep(this, number), init).andAdvanceClock()

  fun siteCreated(
      survivalRateIncludesTempPlots: Boolean = false,
      init: SiteCreatedStep.() -> Unit,
  ) = initChild(SiteCreatedStep(this, survivalRateIncludesTempPlots), init).andAdvanceClock()

  fun siteEdited(survivalRateIncludesTempPlots: Boolean = false, init: SiteEditedStep.() -> Unit) =
      initChild(SiteEditedStep(this, survivalRateIncludesTempPlots), init).andAdvanceClock()

  fun t0DensitySet(init: T0DensitySetStep.() -> Unit) =
      initChild(T0DensitySetStep(this), init).andAdvanceClock()

  fun getOrInsertSpecies(number: Int): SpeciesId {
    return speciesIds.getOrPut(number) { test.insertSpecies("Species $number") }
  }

  fun getMonitoringPlotId(number: Long): MonitoringPlotId =
      monitoringPlotIds[number]
          ?: throw IllegalArgumentException(
              "Monitoring plot $number was not declared before being referenced"
          )

  private fun <T> T.andAdvanceClock(): T = apply {
    clock.instant = clock.instant.plus(1, ChronoUnit.DAYS)
  }
}
