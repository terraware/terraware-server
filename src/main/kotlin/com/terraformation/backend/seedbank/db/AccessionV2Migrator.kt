package com.terraformation.backend.seedbank.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.log.perClassLogger
import java.lang.Exception
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

/**
 * Migrates v1-style accessions to v2-style. This will be removed after it has been deployed and has
 * completed successfully; it's a prerequisite to getting rid of the v1 code.
 */
// HACK: Disable for "gradle generateOpenApiDocs"
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@ManagedBean
class AccessionV2Migrator(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val parentStore: ParentStore,
    private val systemUser: SystemUser,
) : Thread() {
  private val log = perClassLogger()

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    isDaemon = true
    name = javaClass.simpleName
    start()
  }

  override fun run() {
    systemUser.run { convert() }
  }

  private fun convert() {
    var nextAccessionId: AccessionId? = null
    var totalConverted = 0
    var totalFailed = 0

    log.info("Scanning for v1 accessions")

    do {
      try {
        // Convert each accession in a separate transaction so failed conversions don't stop
        // successful ones from being committed to the database.
        dslContext.transaction { _ ->
          nextAccessionId =
              dslContext
                  .select(ACCESSIONS.ID)
                  .from(ACCESSIONS)
                  .where(ACCESSIONS.IS_MANUAL_STATE.isNull)
                  .and(nextAccessionId?.let { ACCESSIONS.ID.gt(it) } ?: DSL.trueCondition())
                  .orderBy(ACCESSIONS.ID)
                  .limit(1)
                  .forUpdate()
                  .skipLocked()
                  .fetchOne(ACCESSIONS.ID)

          nextAccessionId?.let { accessionId ->
            log.debug("Converting accession $accessionId")

            val accession = accessionStore.fetchOneById(accessionId)
            val converted = accession.toV2Compatible(clock)
            accessionStore.update(converted)

            totalConverted++
          }
        }
      } catch (e: Exception) {
        val organizationId = nextAccessionId?.let { parentStore.getOrganizationId(it) }
        log.error("Unable to convert accession $nextAccessionId in organization $organizationId", e)
        totalFailed++
      }
    } while (nextAccessionId != null)

    log.info("Done scanning for v1 accessions. $totalConverted converted, $totalFailed failed")
  }
}
