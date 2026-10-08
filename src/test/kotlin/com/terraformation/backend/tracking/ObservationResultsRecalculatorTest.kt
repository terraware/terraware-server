package com.terraformation.backend.tracking

import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.tracking.db.ObservationRecalculationStore
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
            ObservationRecalculationStore(dslContext),
            systemUser,
            transactionManager,
        )
        .apply {
          siteTransactionPropagation = TransactionDefinition.PROPAGATION_REQUIRED
        }
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
  }

  /**
   * Acquires locks on the test's planting site in a transaction on another database session, the
   * same way a recalculation running elsewhere would. The locks are held until the session's
   * transaction is rolled back.
   */
  private fun lockInSession(session: Connection, vararg lockTypes: LockType) {
    session.autoCommit = false
    val lockService = LockService(DSL.using(session, SQLDialect.POSTGRES))
    lockTypes.forEach { lockType ->
      assertTrue(
          lockService.tryExclusiveTransactional(lockType, plantingSiteId.value),
          "Acquired $lockType",
      )
    }
  }

  @Nested
  inner class Flags {
    @Test
    fun `clears flags on the recalculated observations`() {
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
        lockInSession(otherSession, LockType.OBSERVATION_RESULTS_RECALCULATION)

        try {
          assertFalse(recalculator.recalculateSite(plantingSiteId), "Recalculated")
        } finally {
          otherSession.rollback()
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
    private val mockStore: ObservationRecalculationStore = mockk()

    // The real lock would be shared with other tests running in parallel, which could make these
    // tests' sites look like they're already being recalculated.
    private val mockLockService: LockService =
        mockk(relaxed = true) {
          every { tryExclusiveTransactional(any(), any<Long>()) } returns true
        }

    private val recalculatorWithMocks by lazy {
      ObservationResultsRecalculator(
          mockLockService,
          mockInvalidator,
          mockStore,
          systemUser,
          transactionManager,
      )
    }

    private val failingSiteId = PlantingSiteId(1)
    private val succeedingSiteId = PlantingSiteId(2)

    @BeforeEach
    fun setUpMocks() {
      every { mockInvalidator.fetchPlantingSiteIdsNeedingRecalculation() } returns
          listOf(failingSiteId, succeedingSiteId)
      every { mockStore.recalculateFlaggedResults(succeedingSiteId) } returns Unit
    }

    @Test
    fun `serialization failure leaves flags set and does not block other sites`() {
      every { mockStore.recalculateFlaggedResults(failingSiteId) } throws
          DataAccessException(
              "Serialization failure",
              SQLException("could not serialize access", "40001"),
          )

      recalculatorWithMocks.recalculateAllSites()

      verify(exactly = 0) { mockInvalidator.clearRecalculationFlags(failingSiteId) }
      verify { mockInvalidator.clearRecalculationFlags(succeedingSiteId) }
    }

    @Test
    fun `unexpected failure leaves flags set and does not block other sites`() {
      every { mockStore.recalculateFlaggedResults(failingSiteId) } throws
          IllegalStateException("Oops")

      recalculatorWithMocks.recalculateAllSites()

      verify(exactly = 0) { mockInvalidator.clearRecalculationFlags(failingSiteId) }
      verify { mockInvalidator.clearRecalculationFlags(succeedingSiteId) }
    }
  }

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
