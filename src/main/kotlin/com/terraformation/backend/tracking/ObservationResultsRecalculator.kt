package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import com.terraformation.backend.tracking.db.ObservationStore
import jakarta.inject.Named
import java.sql.SQLException
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Rebuilds observation results that have been flagged for recalculation by
 * [ObservationResultsInvalidator].
 *
 * Each planting site is rebuilt in its own REPEATABLE READ transaction, so the rebuild sees a
 * consistent snapshot of t0 data and species totals even if they're edited while it runs, and
 * readers see the site's new results all at once when it commits.
 *
 * An edit that lands while a site is being rebuilt flags results rows the rebuild also updates.
 * Postgres then aborts the rebuild with a serialization failure, the flags stay set, and the next
 * run picks the site up again, so no invalidation is lost.
 */
@Named
class ObservationResultsRecalculator(
    private val lockService: LockService,
    private val observationResultsInvalidator: ObservationResultsInvalidator,
    private val observationStore: ObservationStore,
    private val systemUser: SystemUser,
    transactionManager: PlatformTransactionManager,
) {
  private val log = perClassLogger()

  /**
   * Each site is rebuilt in a new transaction, even if the caller already has one, so the rebuild
   * always runs with REPEATABLE READ isolation. Tests that need the rebuild to see uncommitted test
   * data can switch this to join the existing transaction.
   */
  internal var siteTransactionPropagation = TransactionDefinition.PROPAGATION_REQUIRES_NEW

  private val siteTransaction =
      TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
      }

  @Job(name = RECALCULATE_JOB_NAME, retries = 0)
  @Recurring(id = RECALCULATE_JOB_NAME, cron = "* * * * *")
  fun recalculateFlaggedResults() {
    recalculateAllSites()
  }

  /** Rebuilds the flagged results of every planting site that has any. */
  fun recalculateAllSites() {
    systemUser.run {
      observationResultsInvalidator.fetchPlantingSiteIdsNeedingRecalculation().forEach {
        recalculateSite(it)
      }
    }
  }

  /**
   * Rebuilds the flagged results of one planting site.
   *
   * @return true if the site was rebuilt; false if another rebuild of the site was already running
   *   or the rebuild failed.
   */
  fun recalculateSite(plantingSiteId: PlantingSiteId): Boolean {
    return try {
      systemUser.run {
        siteTransaction.propagationBehavior = siteTransactionPropagation
        siteTransaction.execute {
          if (
              !lockService.tryExclusiveTransactional(
                  LockType.OBSERVATION_RESULTS_RECALCULATION,
                  plantingSiteId.value,
              )
          ) {
            log.info("Results of planting site $plantingSiteId are already being recalculated")
            false
          } else {
            val observationIds =
                observationResultsInvalidator.fetchObservationIdsNeedingRecalculation(
                    plantingSiteId
                )

            observationIds.forEach { observationStore.rebuildObservationDerivedData(it) }
            observationResultsInvalidator.clearRecalculationFlags(observationIds)

            log.info(
                "Recalculated results of observations $observationIds of planting site " +
                    "$plantingSiteId"
            )
            true
          }
        } == true
      }
    } catch (e: Exception) {
      if (e.isSerializationFailure()) {
        log.info(
            "Results of planting site $plantingSiteId changed during recalculation; will retry"
        )
      } else {
        log.error("Failed to recalculate results of planting site $plantingSiteId", e)
      }
      false
    }
  }

  private fun Throwable.isSerializationFailure(): Boolean {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == SERIALIZATION_FAILURE || it.sqlState == DEADLOCK_DETECTED }
  }

  companion object {
    private const val RECALCULATE_JOB_NAME = "ObservationResultsRecalculator.recalculate"
    private const val SERIALIZATION_FAILURE = "40001"
    private const val DEADLOCK_DETECTED = "40P01"
  }
}
