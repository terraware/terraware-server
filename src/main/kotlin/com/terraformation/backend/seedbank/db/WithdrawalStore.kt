package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class WithdrawalStore(private val dslContext: DSLContext, private val clock: Clock) {
  val log = perClassLogger()

  fun fetchWithdrawals(accessionId: Long): List<WithdrawalModel> {
    return dslContext
        .selectFrom(WITHDRAWALS)
        .where(WITHDRAWALS.ACCESSION_ID.eq(accessionId))
        .orderBy(WITHDRAWALS.DATE.desc(), WITHDRAWALS.CREATED_TIME.desc())
        .fetch()
        .map { record ->
          WithdrawalModel(
              record.id!!,
              accessionId,
              record.date!!,
              record.purposeId!!,
              record.destination,
              record.notes,
              record.staffResponsible,
              record.germinationTestId,
              SeedQuantityModel.of(record.remainingQuantity, record.remainingUnitsId)!!,
              SeedQuantityModel.of(record.withdrawnQuantity, record.withdrawnUnitsId))
        }
  }

  fun updateWithdrawals(
      accessionId: Long,
      existingWithdrawals: Collection<WithdrawalModel>?,
      desiredWithdrawals: Collection<WithdrawalModel>?,
  ) {
    desiredWithdrawals?.forEach { it.validate() }

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

    with(WITHDRAWALS) {
      newWithdrawals.forEach { withdrawal ->
        val newId =
            dslContext
                .insertInto(WITHDRAWALS)
                .set(ACCESSION_ID, accessionId)
                .set(CREATED_TIME, clock.instant())
                .set(DATE, withdrawal.date)
                .set(DESTINATION, withdrawal.destination)
                .set(GERMINATION_TEST_ID, withdrawal.germinationTestId)
                .set(NOTES, withdrawal.notes)
                .set(PURPOSE_ID, withdrawal.purpose)
                .set(REMAINING_GRAMS, withdrawal.remaining?.grams)
                .set(REMAINING_QUANTITY, withdrawal.remaining?.quantity)
                .set(REMAINING_UNITS_ID, withdrawal.remaining?.units)
                .set(STAFF_RESPONSIBLE, withdrawal.staffResponsible)
                .set(UPDATED_TIME, clock.instant())
                .set(WITHDRAWN_GRAMS, withdrawal.withdrawn?.grams)
                .set(WITHDRAWN_QUANTITY, withdrawal.withdrawn?.quantity)
                .set(WITHDRAWN_UNITS_ID, withdrawal.withdrawn?.units)
                .returning(ID)
                .fetchOne()
                ?.id

        log.debug(
            "Inserted withdrawal $newId for accession $accessionId with computed seed " +
                "count ${withdrawal.withdrawn}")
      }

      if (idsToDelete.isNotEmpty()) {
        log.debug("Deleting withdrawals from accession $accessionId with IDs $idsToDelete")
        dslContext
            .deleteFrom(WITHDRAWALS)
            .where(ID.`in`(idsToDelete))
            .and(ACCESSION_ID.eq(accessionId))
            .execute()
      }

      idsToUpdate.forEach { withdrawalId ->
        val existing = existingById[withdrawalId]!!
        val desired = desiredById[withdrawalId]!!

        if (!existing.fieldsEqual(desired)) {
          log.debug("Updating withdrawal $withdrawalId for accession $accessionId")

          dslContext
              .update(WITHDRAWALS)
              .set(DATE, desired.date)
              .set(DESTINATION, desired.destination)
              .set(NOTES, desired.notes)
              .set(PURPOSE_ID, desired.purpose)
              .set(REMAINING_GRAMS, desired.remaining?.grams)
              .set(REMAINING_QUANTITY, desired.remaining?.quantity)
              .set(REMAINING_UNITS_ID, desired.remaining?.units)
              .set(STAFF_RESPONSIBLE, desired.staffResponsible)
              .set(UPDATED_TIME, clock.instant())
              .set(WITHDRAWN_GRAMS, desired.withdrawn?.grams)
              .set(WITHDRAWN_QUANTITY, desired.withdrawn?.quantity)
              .set(WITHDRAWN_UNITS_ID, desired.withdrawn?.units)
              .where(ID.eq(withdrawalId))
              .and(ACCESSION_ID.eq(accessionId))
              .execute()
        }
      }
    }
  }
}
