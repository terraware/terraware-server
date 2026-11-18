package com.terraformation.backend.auth.zerotap

import com.terraformation.backend.auth.zerotap.db.RestoreCredentialStore
import com.terraformation.backend.config.TerrawareServerConfig
import jakarta.inject.Inject
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jobrunr.scheduling.cron.Cron
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Deletes expired restore credential challenges. Challenges can be created by unauthenticated
 * clients, so this runs often to keep the table from growing too large.
 */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class RestoreCredentialChallengeCleaner(
    private val config: TerrawareServerConfig,
    private val restoreCredentialStore: RestoreCredentialStore,
) {
  @Inject
  fun schedule(scheduler: JobScheduler) {
    if (config.dailyTasks.enabled) {
      scheduler.scheduleRecurrently<RestoreCredentialChallengeCleaner>(
          javaClass.simpleName,
          Cron.every5minutes(),
      ) {
        deleteExpiredChallenges()
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun deleteExpiredChallenges() {
    restoreCredentialStore.deleteExpiredChallenges()
  }
}
