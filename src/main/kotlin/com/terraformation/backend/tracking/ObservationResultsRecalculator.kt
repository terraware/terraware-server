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
 * updates. Postgres then aborts the recalculation with a serialization failure, or the
 * recalculation gives up if it would have to wait for the edit's row locks. Either way the flags
 * stay set and a later run picks the site up again, so no invalidation is lost and user edits never
 * wait behind a recalculation's lock requests.
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
            "Results of planting site $plantingSiteId changed or were requested elsewhere " +
                "during recalculation; will retry"
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

    return recalculateUntilDone(plantingSiteId, maxWait ?: DEFAULT_MAX_WAIT, cancelRunning = false)
  }

  /**
   * Recalculates all the flagged results of one planting site right away, canceling any
   * recalculation of the site that's already running unless it is also a forced one, in which case
   * this waits for it to finish. Retries if edits land while the recalculation is in progress.
   * Returns once none of the site's results are flagged, or gives up after repeated failures or
   * [maxWait].
   *
   * @return true if none of the site's results are flagged for recalculation anymore.
   */
  fun forceSiteRecalculation(
      plantingSiteId: PlantingSiteId,
      maxWait: Duration = DEFAULT_MAX_WAIT,
  ): Boolean = recalculateUntilDone(plantingSiteId, maxWait, cancelRunning = true)

  /**
   * Recalculates the flagged results of every planting site right away, canceling any
   * recalculations that are already running.
   *
   * @return The planting sites whose results could not be recalculated. Their results remain
   *   flagged.
   */
  fun forceAllSitesRecalculation(): List<PlantingSiteId> {
    return systemUser.run {
      observationResultsInvalidator.fetchPlantingSiteIdsNeedingRecalculation().filterNot {
        forceSiteRecalculation(it)
      }
    }
  }

  private fun recalculateUntilDone(
      plantingSiteId: PlantingSiteId,
      maxWait: Duration,
      cancelRunning: Boolean,
  ): Boolean {
    val deadline = System.nanoTime() + maxWait.toNanos()
    var failures = 0
    var loggedWaiting = false

    while (observationResultsInvalidator.plantingSiteNeedsRecalculation(plantingSiteId)) {
      if (System.nanoTime() > deadline) {
        log.warn("Timed out waiting to recalculate results of planting site $plantingSiteId")
        return false
      }

      when (tryRecalculateSite(plantingSiteId, forced = cancelRunning)) {
        SiteRecalculationResult.Recalculated -> {}
        SiteRecalculationResult.AlreadyRunning -> {
          if (!loggedWaiting) {
            if (cancelRunning) {
              log.info("Canceling the running recalculation of planting site $plantingSiteId")
            } else {
              log.info("Waiting for the running recalculation of planting site $plantingSiteId")
            }
            loggedWaiting = true
          }

          if (cancelRunning) {
            // Canceling another forced recalculation would let two of them keep canceling each
            // other.
            lockService.cancelExclusiveTransactionalHolders(
                LockType.OBSERVATION_RESULTS_RECALCULATION,
                plantingSiteId.value,
                unlessHolding = LockType.OBSERVATION_RESULTS_FORCED_RECALCULATION,
            )
            Thread.sleep(CANCEL_POLL_INTERVAL.toMillis())
          } else {
            Thread.sleep(LOCK_POLL_INTERVAL.toMillis())
          }
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
  private fun tryRecalculateSite(
      plantingSiteId: PlantingSiteId,
      forced: Boolean = false,
  ): SiteRecalculationResult {
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
            if (forced) {
              lockService.tryExclusiveTransactional(
                  LockType.OBSERVATION_RESULTS_FORCED_RECALCULATION,
                  plantingSiteId.value,
              )
            }

            log.info("Recalculating flagged results of planting site $plantingSiteId")

            // Never wait for user edits. If an edit holds a row this recalculation needs, its
            // results would be stale anyway, so give up and let a later run redo it.
            lockService.setTransactionLockTimeout(LOCK_TIMEOUT)

            observationRecalculationStore.recalculateFlaggedResults(plantingSiteId)
            observationResultsInvalidator.clearRecalculationFlags(plantingSiteId)

            // The timeout lasts until the end of the transaction, which is an enclosing one if
            // siteTransactionPropagation joins it.
            lockService.resetTransactionLockTimeout()

            SiteRecalculationResult.Recalculated
          } else {
            SiteRecalculationResult.AlreadyRunning
          }
        } ?: SiteRecalculationResult.Failed
      }
    } catch (e: Exception) {
      if (e.isConflict()) {
        SiteRecalculationResult.Conflict
      } else {
        log.error("Failed to recalculate results of planting site $plantingSiteId", e)
        SiteRecalculationResult.Failed
      }
    }
  }

  /**
   * Returns true if an exception means the recalculation ran into concurrent activity: another
   * transaction changed data the recalculation read, holds a lock the recalculation needed, or
   * canceled the recalculation so it could run one of its own.
   */
  private fun Throwable.isConflict(): Boolean {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState in CONFLICT_SQL_STATES }
  }

  private enum class SiteRecalculationResult {
    Recalculated,
    AlreadyRunning,
    Conflict,
    Failed,
  }

  companion object {
    private val CANCEL_POLL_INTERVAL: Duration = Duration.ofMillis(200)
    private val CONFLICT_RETRY_INTERVAL: Duration = Duration.ofMillis(200)
    private val DEFAULT_MAX_WAIT: Duration = Duration.ofMinutes(10)
    private val LOCK_POLL_INTERVAL: Duration = Duration.ofSeconds(5)
    private val LOCK_TIMEOUT: Duration = Duration.ofMillis(100)
    private const val MAX_FAILURES = 3
    private const val RECALCULATE_JOB_NAME = "ObservationResultsRecalculator.recalculate"

    /**
     * SQL states for serialization failure, deadlock, lock wait timeout, and statement canceled by
     * another session.
     */
    private val CONFLICT_SQL_STATES = setOf("40001", "40P01", "55P03", "57014")
  }
}
