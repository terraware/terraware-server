package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.LockService
import com.terraformation.backend.db.LockType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.db.ObservationRecalculationStore
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import jakarta.inject.Named
import java.sql.SQLException
import java.time.Duration
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Recalculates observation results that have been flagged for recalculation by
 * [ObservationResultsInvalidator].
 *
 * Each planting site is recalculated in its own REPEATABLE READ transaction, so the recalculation
 * sees a consistent snapshot of t0 data and species totals even if they're edited while it runs,
 * and readers see the site's new results all at once when it commits.
 *
 * An edit that lands while a site is being recalculated flags results rows the recalculation also
 * updates. Postgres then aborts the recalculation with a serialization failure, the flags stay set,
 * and the next run picks the site up again, so no invalidation is lost.
 */
@Named
class ObservationResultsRecalculator(
    private val lockService: LockService,
    private val observationResultsInvalidator: ObservationResultsInvalidator,
    private val observationRecalculationStore: ObservationRecalculationStore,
    private val systemUser: SystemUser,
    transactionManager: PlatformTransactionManager,
) {
  private val log = perClassLogger()

  /**
   * Each site is recalculated in a new transaction, even if the caller already has one, so the
   * recalculation always runs with REPEATABLE READ isolation.
   */
  internal var siteTransactionPropagation = TransactionDefinition.PROPAGATION_REQUIRES_NEW

  private val siteTransaction =
      TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
      }

  @Job(name = RECALCULATE_JOB_NAME, retries = 0)
  @Recurring(id = RECALCULATE_JOB_NAME, cron = "*/5 * * * *")
  fun recalculateFlaggedResults() {
    recalculateAllSites()
  }

  /**
   * Recalculates the flagged results of every planting site that has any.
   *
   * @return The planting sites whose results were not recalculated, either because another
   *   recalculation was already running or because the recalculation failed. Their results remain
   *   flagged.
   */
  fun recalculateAllSites(): List<PlantingSiteId> {
    return systemUser.run {
      observationResultsInvalidator.fetchPlantingSiteIdsNeedingRecalculation().filterNot {
        recalculateSite(it)
      }
    }
  }

  /**
   * Recalculates the flagged results of one planting site.
   *
   * @return true if the site was recalculated; false if another recalculation of the site was
   *   already running or the recalculation failed.
   */
  fun recalculateSite(plantingSiteId: PlantingSiteId): Boolean {
    return when (tryRecalculateSite(plantingSiteId)) {
      SiteRecalculationResult.Recalculated -> {
        log.info("Recalculated flagged results of planting site $plantingSiteId")
        true
      }
      SiteRecalculationResult.AlreadyRunning -> {
        log.info("Results of planting site $plantingSiteId are already being recalculated")
        false
      }
      SiteRecalculationResult.Conflict -> {
        log.info(
            "Results of planting site $plantingSiteId changed during recalculation; will retry"
        )
        false
      }
      SiteRecalculationResult.Failed -> false
    }
  }

  /**
   * Recalculates all the flagged results of one planting site, waiting for any recalculation that's
   * already running and retrying if edits land while the recalculation is in progress. Returns once
   * none of the site's results are flagged, or gives up after repeated failures or [maxWait], which
   * defaults to 10 minutes.
   *
   * @return true if none of the site's results are flagged for recalculation anymore.
   */
  fun completeSiteRecalculation(
      plantingSiteId: PlantingSiteId,
      maxWait: Duration? = null,
  ): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val deadline = System.nanoTime() + (maxWait ?: DEFAULT_MAX_WAIT).toNanos()
    var failures = 0
    var loggedWaiting = false

    while (observationResultsInvalidator.plantingSiteNeedsRecalculation(plantingSiteId)) {
      if (System.nanoTime() > deadline) {
        log.warn("Timed out waiting to recalculate results of planting site $plantingSiteId")
        return false
      }

      when (tryRecalculateSite(plantingSiteId)) {
        SiteRecalculationResult.Recalculated -> {}
        SiteRecalculationResult.AlreadyRunning -> {
          if (!loggedWaiting) {
            log.info("Waiting for the running recalculation of planting site $plantingSiteId")
            loggedWaiting = true
          }
          Thread.sleep(LOCK_POLL_INTERVAL.toMillis())
        }
        // The data changed while the recalculation was running. Give the change a moment to finish
        // and try again with a fresh snapshot.
        SiteRecalculationResult.Conflict -> Thread.sleep(CONFLICT_RETRY_INTERVAL.toMillis())
        SiteRecalculationResult.Failed -> {
          failures++
          if (failures >= MAX_FAILURES) {
            log.error(
                "Gave up recalculating results of planting site $plantingSiteId after $failures " +
                    "failures"
            )
            return false
          }
          Thread.sleep(CONFLICT_RETRY_INTERVAL.toMillis())
        }
      }
    }

    log.info("Completed recalculation of planting site $plantingSiteId")
    return true
  }

  /**
   * Makes one attempt to recalculate a site's flagged results. Logs when the recalculation starts
   * and when it fails; callers log the other outcomes.
   */
  private fun tryRecalculateSite(plantingSiteId: PlantingSiteId): SiteRecalculationResult {
    return try {
      systemUser.run {
        siteTransaction.propagationBehavior = siteTransactionPropagation
        siteTransaction.execute {
          if (
              lockService.tryExclusiveTransactional(
                  LockType.OBSERVATION_RESULTS_RECALCULATION,
                  plantingSiteId.value,
              )
          ) {
            log.info("Recalculating flagged results of planting site $plantingSiteId")
            observationRecalculationStore.recalculateFlaggedResults(plantingSiteId)
            observationResultsInvalidator.clearRecalculationFlags(plantingSiteId)
            SiteRecalculationResult.Recalculated
          } else {
            SiteRecalculationResult.AlreadyRunning
          }
        } ?: SiteRecalculationResult.Failed
      }
    } catch (e: Exception) {
      if (e.isSerializationFailure()) {
        SiteRecalculationResult.Conflict
      } else {
        log.error("Failed to recalculate results of planting site $plantingSiteId", e)
        SiteRecalculationResult.Failed
      }
    }
  }

  private fun Throwable.isSerializationFailure(): Boolean {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == SERIALIZATION_FAILURE || it.sqlState == DEADLOCK_DETECTED }
  }

  private enum class SiteRecalculationResult {
    Recalculated,
    AlreadyRunning,
    Conflict,
    Failed,
  }

  companion object {
    private val CONFLICT_RETRY_INTERVAL: Duration = Duration.ofMillis(200)
    private val DEFAULT_MAX_WAIT: Duration = Duration.ofMinutes(10)
    private val LOCK_POLL_INTERVAL: Duration = Duration.ofSeconds(5)
    private const val MAX_FAILURES = 3
    private const val RECALCULATE_JOB_NAME = "ObservationResultsRecalculator.recalculate"
    private const val SERIALIZATION_FAILURE = "40001"
    private const val DEADLOCK_DETECTED = "40P01"
  }
}
