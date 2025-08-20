package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

@Named
class WithdrawalStore(
    private val dslContext: DSLContext,
    private val clock: Clock,
    private val messages: Messages,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()

  fun fetchOneById(withdrawalId: WithdrawalId): WithdrawalModel {
    val record =
        dslContext
            .select(WITHDRAWALS.asterisk(), USERS.FIRST_NAME, USERS.LAST_NAME)
            .from(WITHDRAWALS)
            .leftJoin(USERS)
            .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
            .where(WITHDRAWALS.ID.eq(withdrawalId))
            .fetchOne() ?: throw WithdrawalNotFoundException(withdrawalId)

    requirePermissions { readAccession(record[WITHDRAWALS.ACCESSION_ID]!!) }

    return WithdrawalModel(
        record,
        TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
    )
  }

  fun fetchWithdrawals(accessionId: AccessionId): List<WithdrawalModel> {
    requirePermissions { readAccession(accessionId) }

    return dslContext
        .select(WITHDRAWALS.asterisk(), USERS.FIRST_NAME, USERS.LAST_NAME)
        .from(WITHDRAWALS)
        .leftJoin(USERS)
        .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
        .where(WITHDRAWALS.ACCESSION_ID.eq(accessionId))
        .orderBy(WITHDRAWALS.DATE.desc(), WITHDRAWALS.CREATED_TIME.desc())
        .fetch()
        .map { record ->
          WithdrawalModel(
              record,
              TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
          )
        }
  }

  fun fetchHistory(accessionId: AccessionId): List<AccessionHistoryModel> {
    return with(WITHDRAWALS) {
      dslContext
          .select(
              BATCH_ID,
              CREATED_TIME,
              DATE,
              NOTES,
              PURPOSE_ID,
              STAFF_RESPONSIBLE,
              WITHDRAWN_BY,
              WITHDRAWN_QUANTITY,
              WITHDRAWN_UNITS_ID,
              USERS.FIRST_NAME,
              USERS.LAST_NAME,
          )
          .from(WITHDRAWALS)
          .leftJoin(USERS)
          .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
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
                batchId = record[BATCH_ID],
                createdTime = record[CREATED_TIME]!!,
                date = record[DATE]!!,
                description = description,
                fullName =
                    TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME])
                        ?: record[STAFF_RESPONSIBLE],
                notes = record[NOTES],
                type = type,
                userId = record[WITHDRAWN_BY],
            )
          }
    }
  }

  fun withdrawalsMultiset(
      idField: Field<AccessionId?> = ACCESSIONS.ID
  ): Field<List<WithdrawalModel>> {
    return DSL.multiset(
            DSL.select(WITHDRAWALS.asterisk(), USERS.FIRST_NAME, USERS.LAST_NAME)
                .from(WITHDRAWALS)
                .leftJoin(USERS)
                .on(WITHDRAWALS.WITHDRAWN_BY.eq(USERS.ID))
                .where(WITHDRAWALS.ACCESSION_ID.eq(idField))
                .orderBy(WITHDRAWALS.DATE.desc(), WITHDRAWALS.CREATED_TIME.desc())
        )
        .convertFrom { result ->
          result.map { record ->
            WithdrawalModel(
                record,
                TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
            )
          }
        }
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

    val organizationId =
        parentStore.getOrganizationId(accessionId) ?: throw AccessionNotFoundException(accessionId)

    // For any withdrawals where withdrawnByUserId is being changed, the current user must have
    // permission to read the user in question in the organization.
    desiredWithdrawals
        .orEmpty()
        .filter {
          it.id == null ||
              it.id !in existingById ||
              it.withdrawnByUserId != existingById[it.id]?.withdrawnByUserId
        }
        .mapNotNull { it.withdrawnByUserId }
        .distinct()
        .forEach { userId -> requirePermissions { readOrganizationUser(organizationId, userId) } }

    idsToUpdate.forEach { id ->
      val existing = existingById[id]!!
      val desired = desiredById[id]!!

      if (
          (existing.purpose == WithdrawalPurpose.ViabilityTesting || existing.purpose == null) xor
              (desired.purpose == WithdrawalPurpose.ViabilityTesting)
      ) {
        throw IllegalArgumentException(
            "Cannot change withdrawal purpose to or from Germination Testing"
        )
      }

      if (existing.viabilityTestId != desired.viabilityTestId) {
        throw IllegalArgumentException("Cannot change test ID of viability testing withdrawal")
      }
    }

    newWithdrawals.forEach { withdrawal ->
      if (
          withdrawal.purpose == WithdrawalPurpose.ViabilityTesting &&
              withdrawal.viabilityTestId == null
      ) {
        throw IllegalArgumentException("Viability testing withdrawals must have test IDs")
      }

      if (
          withdrawal.purpose != WithdrawalPurpose.ViabilityTesting &&
              withdrawal.viabilityTestId != null
      ) {
        throw IllegalArgumentException("Only viability testing withdrawals may have test IDs")
      }
    }

    with(WITHDRAWALS) {
      newWithdrawals.forEach { withdrawal ->
        val withdrawnByUserId =
            if (currentUser().canSetWithdrawalUser(accessionId)) {
              withdrawal.withdrawnByUserId ?: currentUser().userId
            } else {
              currentUser().userId
            }

        val newId =
            dslContext
                .insertInto(WITHDRAWALS)
                .set(ACCESSION_ID, accessionId)
                .set(BATCH_ID, withdrawal.batchId)
                .set(CREATED_BY, currentUser().userId)
                .set(CREATED_TIME, clock.instant())
                .set(DATE, withdrawal.date)
                .set(DESTINATION, withdrawal.destination)
                .set(ESTIMATED_COUNT, withdrawal.estimatedCount)
                .set(ESTIMATED_WEIGHT_QUANTITY, withdrawal.estimatedWeight?.quantity)
                .set(ESTIMATED_WEIGHT_UNITS_ID, withdrawal.estimatedWeight?.units)
                .set(VIABILITY_TEST_ID, withdrawal.viabilityTestId)
                .set(NOTES, withdrawal.notes)
                .set(PURPOSE_ID, withdrawal.purpose)
                .set(STAFF_RESPONSIBLE, withdrawal.staffResponsible)
                .set(UPDATED_TIME, clock.instant())
                .set(WITHDRAWN_BY, withdrawnByUserId)
                .set(WITHDRAWN_GRAMS, withdrawal.withdrawn?.grams)
                .set(WITHDRAWN_QUANTITY, withdrawal.withdrawn?.quantity)
                .set(WITHDRAWN_UNITS_ID, withdrawal.withdrawn?.units)
                .returning(ID)
                .fetchOne()
                ?.id

        log.debug(
            "Inserted withdrawal $newId for accession $accessionId with computed seed " +
                "count ${withdrawal.withdrawn}"
        )
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

          val withdrawnByUserId =
              if (currentUser().canSetWithdrawalUser(accessionId)) {
                desired.withdrawnByUserId ?: existing.withdrawnByUserId
              } else {
                existing.withdrawnByUserId
              }

          dslContext
              .update(WITHDRAWALS)
              .set(DATE, desired.date)
              .set(DESTINATION, desired.destination)
              .set(ESTIMATED_COUNT, desired.estimatedCount)
              .set(ESTIMATED_WEIGHT_QUANTITY, desired.estimatedWeight?.quantity)
              .set(ESTIMATED_WEIGHT_UNITS_ID, desired.estimatedWeight?.units)
              .set(NOTES, desired.notes)
              .set(PURPOSE_ID, desired.purpose)
              .set(STAFF_RESPONSIBLE, desired.staffResponsible)
              .set(UPDATED_TIME, clock.instant())
              .set(WITHDRAWN_BY, withdrawnByUserId)
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
