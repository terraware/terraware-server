package com.terraformation.backend.seedbank.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@ManagedBean
class WithdrawalStore(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val messages: Messages,
) {
  private val log = perClassLogger()

  fun fetchOneById(withdrawalId: WithdrawalId): WithdrawalModel {
    val record =
        dslContext.selectFrom(WITHDRAWALS).where(WITHDRAWALS.ID.eq(withdrawalId)).fetchOne()
            ?: throw WithdrawalNotFoundException(withdrawalId)

    requirePermissions { readAccession(record.accessionId!!) }

    return WithdrawalModel(record)
  }

  fun fetchWithdrawals(accessionId: AccessionId): List<WithdrawalModel> {
    requirePermissions { readAccession(accessionId) }

    return dslContext
        .selectFrom(WITHDRAWALS)
        .where(WITHDRAWALS.ACCESSION_ID.eq(accessionId))
        .orderBy(WITHDRAWALS.DATE.desc(), WITHDRAWALS.CREATED_TIME.desc())
        .fetch()
        .map { WithdrawalModel(it) }
  }

  fun fetchHistory(accessionId: AccessionId): List<AccessionHistoryModel> {
    return with(WITHDRAWALS) {
      dslContext
          .select(
              CREATED_TIME,
              DATE,
              PURPOSE_ID,
              STAFF_RESPONSIBLE,
              WITHDRAWN_QUANTITY,
              WITHDRAWN_UNITS_ID)
          .from(WITHDRAWALS)
          .where(ACCESSION_ID.eq(accessionId))
          .fetch { record ->
            val quantity =
                SeedQuantityModel.of(record[WITHDRAWN_QUANTITY], record[WITHDRAWN_UNITS_ID])
            val purpose = record[PURPOSE_ID]
            val description = messages.historyAccessionWithdrawal(quantity, purpose)
            val type =
                if (purpose == WithdrawalPurpose.ViabilityTesting) {
                  AccessionHistoryType.ViabilityTesting
                } else {
                  AccessionHistoryType.Withdrawal
                }

            AccessionHistoryModel(
                createdTime = record[CREATED_TIME]!!,
                date = record[DATE]!!,
                description = description,
                fullName = record[STAFF_RESPONSIBLE],
                type = type,
                userId = null,
            )
          }
    }
  }

  fun withdrawalsMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<List<WithdrawalModel>> {
    return DSL.multiset(
            DSL.selectFrom(WITHDRAWALS)
                .where(WITHDRAWALS.ACCESSION_ID.eq(idField))
                .orderBy(WITHDRAWALS.DATE.desc(), WITHDRAWALS.CREATED_TIME.desc()))
        .convertFrom { result -> result.map { WithdrawalModel(it) } }
  }

  fun updateWithdrawals(
      accessionId: AccessionId,
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

      if ((existing.purpose == WithdrawalPurpose.ViabilityTesting) xor
          (desired.purpose == WithdrawalPurpose.ViabilityTesting)) {
        throw IllegalArgumentException(
            "Cannot change withdrawal purpose to or from Germination Testing")
      }

      if (existing.viabilityTestId != desired.viabilityTestId) {
        throw IllegalArgumentException("Cannot change test ID of viability testing withdrawal")
      }
    }

    newWithdrawals.forEach { withdrawal ->
      if (withdrawal.purpose == WithdrawalPurpose.ViabilityTesting &&
          withdrawal.viabilityTestId == null) {
        throw IllegalArgumentException("Viability testing withdrawals must have test IDs")
      }

      if (withdrawal.purpose != WithdrawalPurpose.ViabilityTesting &&
          withdrawal.viabilityTestId != null) {
        throw IllegalArgumentException("Only viability testing withdrawals may have test IDs")
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
                .set(VIABILITY_TEST_ID, withdrawal.viabilityTestId)
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
