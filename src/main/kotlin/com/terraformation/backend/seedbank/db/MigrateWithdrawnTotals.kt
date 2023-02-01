package com.terraformation.backend.seedbank.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import javax.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

/**
 * Migration to calculate total withdrawal weights and counts for accessions that don't already have
 * them. This can be removed after it has run.
 */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class MigrateWithdrawnTotals(
    private val accessionStore: AccessionStore,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun migrate(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    val condition =
        ACCESSIONS.TOTAL_WITHDRAWN_COUNT.isNull
            .and(ACCESSIONS.TOTAL_WITHDRAWN_WEIGHT_GRAMS.isNull)
            .andExists(
                DSL.selectOne()
                    .from(WITHDRAWALS)
                    .where(WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID))
                    .and(WITHDRAWALS.WITHDRAWN_QUANTITY.isNotNull))

    systemUser.run {
      accessionStore.forEachAccession(condition) {
        val accession = it.withCalculatedValues()

        if (accession.totalWithdrawnCount != null || accession.totalWithdrawnWeight != null) {
          log.info(
              "Updating accession ${accession.id} total withdrawn count " +
                  "${accession.totalWithdrawnCount} weight ${accession.totalWithdrawnWeight}")

          with(ACCESSIONS) {
            // Update the database directly rather than via AccessionStore because we don't
            // want this migration to bump accession modification times.
            dslContext
                .update(ACCESSIONS)
                .set(TOTAL_WITHDRAWN_COUNT, accession.totalWithdrawnCount)
                .set(TOTAL_WITHDRAWN_WEIGHT_GRAMS, accession.totalWithdrawnWeight?.grams)
                .set(TOTAL_WITHDRAWN_WEIGHT_QUANTITY, accession.totalWithdrawnWeight?.quantity)
                .set(TOTAL_WITHDRAWN_WEIGHT_UNITS_ID, accession.totalWithdrawnWeight?.units)
                .where(ID.eq(accession.id))
                .execute()
          }
        } else {
          log.warn("Accession ${accession.id} had no withdrawn totals but had withdrawals")
        }
      }
    }
  }
}
