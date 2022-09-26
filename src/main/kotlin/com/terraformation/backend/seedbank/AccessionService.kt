package com.terraformation.backend.seedbank

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

@ManagedBean
class AccessionService(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val photoRepository: PhotoRepository,
) {
  /** Deletes an accession and all its associated data. */
  fun deleteAccession(accessionId: AccessionId) {
    requirePermissions { deleteAccession(accessionId) }

    // Note that this is not wrapped in a transaction; if this bombs out after having deleted some
    // but not all photos from the file store, we don't want to roll back the removal of the photos
    // that were successfully deleted or else we'll end up with dangling storage URLs.
    photoRepository.deleteAllPhotos(accessionId)
    accessionStore.delete(accessionId)
  }

  fun createWithdrawal(withdrawal: WithdrawalModel): AccessionModel {
    val accessionId =
        withdrawal.accessionId ?: throw IllegalArgumentException("Accession ID must be non-null")

    return updateAccession(accessionId) { it.addWithdrawal(withdrawal, clock) }
  }

  fun updateWithdrawal(
      accessionId: AccessionId,
      withdrawalId: WithdrawalId,
      modify: (WithdrawalModel) -> WithdrawalModel
  ): AccessionModel {
    return updateAccession(accessionId) { it.updateWithdrawal(withdrawalId, clock, modify) }
  }

  fun deleteWithdrawal(accessionId: AccessionId, withdrawalId: WithdrawalId): AccessionModel {
    return updateAccession(accessionId) { it.deleteWithdrawal(withdrawalId, clock) }
  }

  fun createViabilityTest(viabilityTest: ViabilityTestModel): AccessionModel {
    val accessionId =
        viabilityTest.accessionId ?: throw IllegalArgumentException("Accession ID must be non-null")

    return updateAccession(accessionId) { it.addViabilityTest(viabilityTest, clock) }
  }

  fun updateViabilityTest(
      accessionId: AccessionId,
      viabilityTestId: ViabilityTestId,
      modify: (ViabilityTestModel) -> ViabilityTestModel
  ): AccessionModel {
    return updateAccession(accessionId) { it.updateViabilityTest(viabilityTestId, clock, modify) }
  }

  fun deleteViabilityTest(
      accessionId: AccessionId,
      viabilityTestId: ViabilityTestId
  ): AccessionModel {
    return updateAccession(accessionId) { it.deleteViabilityTest(viabilityTestId, clock) }
  }

  private fun updateAccession(
      accessionId: AccessionId,
      modify: (AccessionModel) -> AccessionModel
  ): AccessionModel {
    requirePermissions { updateAccession(accessionId) }

    return dslContext.transactionResult { _ ->
      lockAccession(accessionId)

      val existing = accessionStore.fetchOneById(accessionId).toV2Compatible(clock)
      val modified = modify(existing)
      accessionStore.updateAndFetch(modified)
    }
  }

  private fun lockAccession(accessionId: AccessionId) {
    dslContext
        .select(DSL.one())
        .from(ACCESSIONS)
        .where(ACCESSIONS.ID.eq(accessionId))
        .forUpdate()
        .execute()
  }
}
