package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.WITHDRAWAL
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.WithdrawalFields
import com.terraformation.seedbank.model.WithdrawalModel
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toListOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class WithdrawalFetcher(private val dslContext: DSLContext, private val clock: Clock) {
  val log = perClassLogger()

  fun fetchWithdrawals(accessionId: Long): List<WithdrawalModel>? {
    return dslContext
        .selectFrom(WITHDRAWAL)
        .where(WITHDRAWAL.ACCESSION_ID.eq(accessionId))
        .orderBy(WITHDRAWAL.DATE.desc(), WITHDRAWAL.CREATED_TIME.desc())
        .fetch()
        .map { record ->
          WithdrawalModel(
              record.id!!,
              accessionId,
              record.date!!,
              record.purposeId!!,
              record.seedsWithdrawn!!,
              record.gramsWithdrawn,
              record.destination,
              record.notes,
              record.staffResponsible,
          )
        }
        .toListOrNull()
  }

  fun updateWithdrawals(
      accessionId: Long,
      accession: AccessionFields,
      existingWithdrawals: Collection<WithdrawalModel>?,
      desiredWithdrawals: Collection<WithdrawalFields>?,
  ) {
    val existingById = existingWithdrawals?.associateBy { it.id } ?: emptyMap()
    val desiredById =
        desiredWithdrawals?.filter { it.id != null }?.associateBy { it.id } ?: emptyMap()
    val newWithdrawals = desiredWithdrawals?.filter { it.id == null } ?: emptyList()
    val idsToDelete = existingById.keys - desiredById.keys
    val idsToUpdate = existingById.keys.intersect(desiredById.keys)

    with(WITHDRAWAL) {
      newWithdrawals.forEach { withdrawal ->
        val seedsWithdrawn = computeSeedsWithdrawn(withdrawal, accession, false)

        val newId =
            dslContext
                .insertInto(WITHDRAWAL)
                .set(ACCESSION_ID, accessionId)
                .set(CREATED_TIME, clock.instant())
                .set(DATE, withdrawal.date)
                .set(DESTINATION, withdrawal.destination)
                .set(GRAMS_WITHDRAWN, withdrawal.gramsWithdrawn)
                .set(NOTES, withdrawal.notes)
                .set(PURPOSE_ID, withdrawal.purpose)
                .set(SEEDS_WITHDRAWN, seedsWithdrawn)
                .set(STAFF_RESPONSIBLE, withdrawal.staffResponsible)
                .set(UPDATED_TIME, clock.instant())
                .returning(ID)
                .fetchOne()
                ?.id

        log.debug(
            "Inserted withdrawal $newId for accession $accessionId with computed seed " +
                "count $seedsWithdrawn")
      }

      if (idsToDelete.isNotEmpty()) {
        log.debug("Deleting withdrawals from accession $accessionId with IDs $idsToDelete")
        dslContext
            .deleteFrom(WITHDRAWAL)
            .where(ID.`in`(idsToDelete))
            .and(ACCESSION_ID.eq(accessionId))
            .execute()
      }

      idsToUpdate.forEach { withdrawalId ->
        val existing = existingById[withdrawalId]!!
        val desired = desiredById[withdrawalId]!!

        if (!existing.fieldsEqual(desired)) {
          log.debug("Updating withdrawal $withdrawalId for accession $accessionId")

          val seedsWithdrawn = computeSeedsWithdrawn(desired, accession, true)
          if (seedsWithdrawn != existing.seedsWithdrawn) {
            log.info(
                "Recomputed seeds withdrawn for withdrawal $withdrawalId from accession " +
                    "$accessionId: was ${existing.seedsWithdrawn}, now $seedsWithdrawn")
          }

          dslContext
              .update(WITHDRAWAL)
              .set(DATE, desired.date)
              .set(DESTINATION, desired.destination)
              .set(GRAMS_WITHDRAWN, desired.gramsWithdrawn)
              .set(NOTES, desired.notes)
              .set(PURPOSE_ID, desired.purpose)
              .set(SEEDS_WITHDRAWN, seedsWithdrawn)
              .set(STAFF_RESPONSIBLE, desired.staffResponsible)
              .set(UPDATED_TIME, clock.instant())
              .where(ID.eq(withdrawalId))
              .and(ACCESSION_ID.eq(accessionId))
              .execute()
        }
      }
    }
  }

  /**
   * Computes a withdrawal's seed count. A withdrawal can be sized in number of seeds or number of
   * grams. Weight-based sizing is only available if the accession has seed weight information,
   * specifically a subset count and subset weight.
   */
  fun computeSeedsWithdrawn(
      withdrawal: WithdrawalFields,
      accession: AccessionFields,
      isExistingWithdrawal: Boolean
  ): Int {
    val desiredGrams = withdrawal.gramsWithdrawn
    val desiredSeeds = withdrawal.seedsWithdrawn

    if (desiredGrams == null && desiredSeeds == null) {
      throw IllegalArgumentException("Withdrawal must have either a seed count or a weight.")
    }

    if (desiredGrams != null && desiredSeeds != null && !isExistingWithdrawal) {
      throw IllegalArgumentException("New withdrawals can have a seed count or a weight, not both.")
    }

    return if (desiredGrams != null) {
      val subsetWeightGrams = accession.subsetWeightGrams
      val subsetCount = accession.subsetCount

      if (desiredGrams <= BigDecimal.ZERO) {
        throw IllegalArgumentException("Withdrawal weight must be greater than zero.")
      }

      if (subsetWeightGrams != null && subsetCount != null) {
        BigDecimal(subsetCount)
            .multiply(desiredGrams)
            .divide(subsetWeightGrams, 5, RoundingMode.CEILING)
            .setScale(0, RoundingMode.CEILING)
            .toInt()
      } else if (desiredSeeds != null && isExistingWithdrawal) {
        // If the user removed the weight from an existing accession after there were already
        // weight-based withdrawals, retain the previously-computed withdrawal count.
        desiredSeeds
      } else {
        throw IllegalArgumentException(
            "Withdrawal can only be measured by weight if accession was measured by weight.")
      }
    } else {
      desiredSeeds!!
    }
  }
}
