package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.WITHDRAWAL
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.WithdrawalFields
import com.terraformation.seedbank.model.WithdrawalModel
import com.terraformation.seedbank.services.perClassLogger
import com.terraformation.seedbank.services.toListOrNull
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class WithdrawalStore(private val dslContext: DSLContext, private val clock: Clock) {
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
              record.germinationTestId,
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

    idsToUpdate.forEach { id ->
      val existing = existingById[id]!!
      val desired = desiredById[id]!!

      if ((existing.purpose == WithdrawalPurpose.GerminationTesting) xor
          (desired.purpose == WithdrawalPurpose.GerminationTesting)) {
        throw IllegalArgumentException(
            "Cannot change withdrawal purpose to or from Germination Testing")
      }

      if (existing.germinationTestId != desired.germinationTestId) {
        throw IllegalArgumentException("Cannot change test ID of germination testing withdrawal")
      }
    }

    newWithdrawals.forEach { withdrawal ->
      if (withdrawal.purpose == WithdrawalPurpose.GerminationTesting &&
          withdrawal.germinationTestId == null) {
        throw IllegalArgumentException("Germination testing withdrawals must have test IDs")
      }

      if (withdrawal.purpose != WithdrawalPurpose.GerminationTesting &&
          withdrawal.germinationTestId != null) {
        throw IllegalArgumentException("Only germination testing withdrawals may have test IDs")
      }
    }

    with(WITHDRAWAL) {
      newWithdrawals.forEach { withdrawal ->
        val seedsWithdrawn = withdrawal.computeSeedsWithdrawn(accession, false)

        val newId =
            dslContext
                .insertInto(WITHDRAWAL)
                .set(ACCESSION_ID, accessionId)
                .set(CREATED_TIME, clock.instant())
                .set(DATE, withdrawal.date)
                .set(DESTINATION, withdrawal.destination)
                .set(GERMINATION_TEST_ID, withdrawal.germinationTestId)
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

          val seedsWithdrawn = desired.computeSeedsWithdrawn(accession, true)
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
}
