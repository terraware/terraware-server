package com.terraformation.backend.customer.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.tables.references.NOTIFICATIONS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import java.time.Duration
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.NOTIFICATIONS_CLEANUP_ENABLED_PROPERTY)
@ManagedBean
class NotificationsCleanupTask(
    private val clock: Clock,
    private val terrawareServerConfig: TerrawareServerConfig,
    private val dslContext: DSLContext
) {
  private val log = perClassLogger()

  @EventListener
  fun cleanup(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    try {
      dslContext.transaction { _ ->
        val rowsDeleted =
            dslContext
                .deleteFrom(NOTIFICATIONS)
                .where(
                    NOTIFICATIONS.CREATED_TIME.le(
                        clock
                            .instant()
                            .minus(
                                Duration.ofDays(
                                    terrawareServerConfig.notificationsCleanup.retentionDays)),
                    ),
                )
                .execute()
        log.info(
            "Deleted $rowsDeleted notification(s) that expired in the last ${terrawareServerConfig.notificationsCleanup.retentionDays} days.",
        )
      }
    } catch (e: Exception) {
      log.error("Failed running notifications cleanup.", e)
    }
  }
}
