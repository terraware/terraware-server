package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import java.math.BigDecimal
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

/**
 * Cleans up after a bug in the v1 accession code that caused withdrawals to be inserted into the
 * database even when the withdrawal ultimately failed, which then triggered a bug in the v1-to-v2
 * migration that caused subsequent withdrawals to have negative estimated weights to be calculated
 * for weight-based accessions with count-based withdrawals.
 *
 * This class can be deleted after it has run in production.
 */
@ManagedBean
class QuantityRepairer(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    systemUser.run {
      log.info("Scanning for accessions with inconsistent withdrawal quantities")

      var accessionId: AccessionId? = AccessionId(0)

      while (accessionId != null) {
        dslContext.transaction { _ ->
          accessionId =
              dslContext
                  .select(ACCESSIONS.ID)
                  .from(ACCESSIONS)
                  .whereExists(
                      DSL.selectOne()
                          .from(WITHDRAWALS)
                          .where(WITHDRAWALS.ESTIMATED_WEIGHT_QUANTITY.lt(BigDecimal.ZERO))
                          .and(WITHDRAWALS.ACCESSION_ID.eq(ACCESSIONS.ID)))
                  .and(ACCESSIONS.ID.gt(accessionId))
                  .and(ACCESSIONS.REMAINING_UNITS_ID.ne(SeedQuantityUnits.Seeds))
                  .orderBy(ACCESSIONS.ID)
                  .limit(1)
                  .forUpdate()
                  .skipLocked()
                  .fetchOne(ACCESSIONS.ID)

          if (accessionId != null) {
            try {
              val accession = accessionStore.fetchOneById(accessionId!!)

              // Get rid of withdrawals more recent than the latest observed time that don't have
              // corresponding quantity history entries, where "corresponding" means "created within
              // a few seconds of the withdrawal."
              val quantityHistoryTimes =
                  dslContext
                      .select(ACCESSION_QUANTITY_HISTORY.CREATED_TIME)
                      .from(ACCESSION_QUANTITY_HISTORY)
                      .where(ACCESSION_QUANTITY_HISTORY.ACCESSION_ID.eq(accessionId))
                      .fetch(ACCESSION_QUANTITY_HISTORY.CREATED_TIME)
                      .filterNotNull()
              val withdrawalsToRetain =
                  accession.withdrawals.filter { withdrawal ->
                    val startTime = withdrawal.createdTime!!
                    val endTime = startTime.plusSeconds(3L)
                    val isBeforeLatestObservation =
                        !withdrawal.isAfter(accession.latestObservedTime!!)
                    val hasHistoryEntry =
                        quantityHistoryTimes.any { it >= startTime && it < endTime }

                    isBeforeLatestObservation || hasHistoryEntry
                  }

              if (withdrawalsToRetain != accession.withdrawals) {
                log.info("Accession $accessionId had withdrawals without quantity history entries")
              }

              val observedQuantityToKeepExistingRemainingQuantity =
                  withdrawalsToRetain
                      .filter { it.isAfter(accession.latestObservedTime!!) }
                      .map {
                        it.withdrawn!!.toUnits(
                            SeedQuantityUnits.Milligrams,
                            accession.subsetWeightQuantity,
                            accession.subsetCount)
                      }
                      .fold(accession.remaining!!.toUnits(SeedQuantityUnits.Milligrams)) {
                          subtotal,
                          withdrawn ->
                        subtotal + withdrawn
                      }
                      .toUnits(accession.remaining.units)

              val adjustedAccession =
                  accession
                      .copy(
                          latestObservedQuantity = observedQuantityToKeepExistingRemainingQuantity,
                          latestObservedQuantityCalculated = true,
                          withdrawals = withdrawalsToRetain)
                      .withCalculatedValues(clock)

              accessionStore.update(adjustedAccession)
            } catch (e: Exception) {
              log.error("Unable to recalculate quantity for accession $accessionId: ${e.message}")
              // Proceed to the next accession
            }
          }
        }
      }

      log.debug("Done scanning for inconsistent remaining quantities")
    }
  }
}
