package com.terraformation.backend.tracking

import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.tracking.db.ObservationStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.sql.SQLException
import javax.sql.DataSource
import org.jooq.Record
import org.jooq.Table
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition

class ObservationResultsRecalculatorTest : ObservationScenarioTest() {
  @Autowired private lateinit var dataSource: DataSource
  @Autowired private lateinit var transactionManager: PlatformTransactionManager

  private val invalidator: ObservationResultsInvalidator by lazy {
    ObservationResultsInvalidator(dslContext)
  }

  private val recalculator: ObservationResultsRecalculator by lazy {
    ObservationResultsRecalculator(
            LockService(dslContext),
            invalidator,
            observationStore,
            systemUser,
            transactionManager,
        )
        .apply {
          // Join the test's transaction so the rebuild can see the test data.
          siteTransactionPropagation = TransactionDefinition.PROPAGATION_REQUIRED
        }
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
  }

  @Nested
  inner class Parity {
    @Test
    fun `rebuilds the same results as plot completion for a single observation`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 1, 30)

      assertRecalculationMatchesCurrentResults()
    }

    @Test
    fun `rebuilds the same results as plot completion for multiple observations`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 2, 30)

      assertRecalculationMatchesCurrentResults()
    }

    @Test
    fun `rebuilds the same results as plot completion when substrata are rolled forward`() {
      importFromCsvFiles("/tracking/observation/DisjointSubstrata", 3, 30)

      assertRecalculationMatchesCurrentResults()
    }

    @Test
    fun `rebuilds the same results as plot completion when strata are rolled forward`() {
      importFromCsvFiles("/tracking/observation/DisjointStrata", 2, 30)

      assertRecalculationMatchesCurrentResults()
    }

    @Test
    fun `rebuilds the same results as plot completion when permanent plots change`() {
      importFromCsvFiles("/tracking/observation/PermanentPlotChanges", 3, 30)

      assertRecalculationMatchesCurrentResults()
    }

    @Test
    fun `rebuilds the same results as a site recalculation after t0 densities change`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 2, 30)

      dslContext
          .update(PLOT_T0_DENSITIES)
          .set(PLOT_T0_DENSITIES.PLOT_DENSITY, PLOT_T0_DENSITIES.PLOT_DENSITY.times(2))
          .execute()
      observationStore.recalculateSurvivalRates(plantingSiteId)

      assertRecalculationMatchesCurrentResults()
    }
  }

  @Nested
  inner class Flags {
    @Test
    fun `clears flags on the rebuilt observations`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 2, 30)
      invalidator.invalidateSite(plantingSiteId)

      assertTrue(recalculator.recalculateSite(plantingSiteId), "Recalculated")

      assertEquals(
          emptyList<PlantingSiteId>(),
          invalidator.fetchPlantingSiteIdsNeedingRecalculation(),
          "Sites needing recalculation",
      )
    }

    @Test
    fun `recalculates only flagged observations`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 2, 30)
      val observationIds = inserted.observationIds.sorted()
      val untouchedObservationId = observationIds.first()
      invalidator.invalidateObservation(observationIds.last())
      scrambleSurvivalRates()

      recalculator.recalculateAllSites()

      assertEquals(
          setOf(-1),
          dslContext
              .select(OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE)
              .from(OBSERVATION_PLOT_RESULTS)
              .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(untouchedObservationId))
              .fetchSet(OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE),
          "Survival rates of unflagged observation should not be recalculated",
      )
    }

    @Test
    fun `skips a site whose results are being recalculated elsewhere`() {
      importFromCsvFiles("/tracking/observation/TwoObservations", 1, 30)
      invalidator.invalidateSite(plantingSiteId)

      dataSource.connection.use { otherSession ->
        otherSession.prepareStatement("SELECT pg_advisory_lock(?, ?)").use { statement ->
          statement.setInt(1, LockType.OBSERVATION_RESULTS_RECALCULATION.key.toInt())
          statement.setInt(2, plantingSiteId.value.toInt())
          statement.execute()
        }

        try {
          assertFalse(recalculator.recalculateSite(plantingSiteId), "Recalculated")
        } finally {
          otherSession.prepareStatement("SELECT pg_advisory_unlock_all()").use { it.execute() }
        }
      }

      assertEquals(
          listOf(plantingSiteId),
          invalidator.fetchPlantingSiteIdsNeedingRecalculation(),
          "Sites needing recalculation",
      )
    }
  }

  @Nested
  inner class Failures {
    private val mockInvalidator: ObservationResultsInvalidator = mockk(relaxed = true)
    private val mockStore: ObservationStore = mockk()

    private val recalculatorWithMocks by lazy {
      ObservationResultsRecalculator(
          LockService(dslContext),
          mockInvalidator,
          mockStore,
          systemUser,
          transactionManager,
      )
    }

    private val failingObservationId = ObservationId(1)
    private val succeedingObservationId = ObservationId(2)
    private val failingSiteId = PlantingSiteId(1)
    private val succeedingSiteId = PlantingSiteId(2)

    @BeforeEach
    fun setUpMocks() {
      every { mockInvalidator.fetchPlantingSiteIdsNeedingRecalculation() } returns
          listOf(failingSiteId, succeedingSiteId)
      every { mockInvalidator.fetchObservationIdsNeedingRecalculation(failingSiteId) } returns
          listOf(failingObservationId)
      every { mockInvalidator.fetchObservationIdsNeedingRecalculation(succeedingSiteId) } returns
          listOf(succeedingObservationId)
      every { mockStore.rebuildObservationDerivedData(succeedingObservationId) } returns Unit
    }

    @Test
    fun `serialization failure leaves flags set and does not block other sites`() {
      every { mockStore.rebuildObservationDerivedData(failingObservationId) } throws
          DataAccessException(
              "Serialization failure",
              SQLException("could not serialize access", "40001"),
          )

      recalculatorWithMocks.recalculateAllSites()

      verify(exactly = 0) { mockInvalidator.clearRecalculationFlags(listOf(failingObservationId)) }
      verify { mockInvalidator.clearRecalculationFlags(listOf(succeedingObservationId)) }
    }

    @Test
    fun `unexpected failure leaves flags set and does not block other sites`() {
      every { mockStore.rebuildObservationDerivedData(failingObservationId) } throws
          IllegalStateException("Oops")

      recalculatorWithMocks.recalculateAllSites()

      verify(exactly = 0) { mockInvalidator.clearRecalculationFlags(listOf(failingObservationId)) }
      verify { mockInvalidator.clearRecalculationFlags(listOf(succeedingObservationId)) }
    }
  }

  private val derivedTables: List<Table<out Record>> =
      listOf(
          OBSERVED_PLOT_SPECIES_TOTALS,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
          OBSERVED_STRATUM_SPECIES_TOTALS,
          OBSERVED_SITE_SPECIES_TOTALS,
          OBSERVATION_PLOT_RESULTS,
          OBSERVATION_SUBSTRATUM_RESULTS,
          OBSERVATION_STRATUM_RESULTS,
          OBSERVATION_SITE_RESULTS,
      )

  /**
   * Scrambles the derived values the recalculation job is responsible for, flags the whole site,
   * runs the job, and asserts that every derived table ends up with the same contents it had before
   * scrambling.
   */
  private fun assertRecalculationMatchesCurrentResults() {
    val expected = derivedTables.associate { it.name to fetchSorted(it) }

    scrambleSurvivalRates()
    invalidator.invalidateSite(plantingSiteId)
    assertTrue(recalculator.recalculateSite(plantingSiteId), "Recalculated")

    val actual = derivedTables.associate { it.name to fetchSorted(it) }

    assertAll(
        derivedTables.map { table ->
          {
            val expectedRows = expected[table.name]!!
            val actualRows = actual[table.name]!!
            assertEquals(
                emptyList<String>() to emptyList<String>(),
                (expectedRows - actualRows.toSet()) to (actualRows - expectedRows.toSet()),
                "${table.name}: (missing rows, unexpected rows)",
            )
          }
        }
    )
  }

  private fun fetchSorted(table: Table<out Record>): List<String> =
      dslContext.selectFrom(table).fetch().map { it.formatCSV(false) }.sorted()

  private fun scrambleSurvivalRates() {
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVED_STRATUM_SPECIES_TOTALS)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVED_SITE_SPECIES_TOTALS)
        .set(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_SITE_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_PLOT_RESULTS)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_SUBSTRATUM_RESULTS)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
  }
}
