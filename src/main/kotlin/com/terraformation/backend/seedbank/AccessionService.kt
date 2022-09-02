package com.terraformation.backend.seedbank

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.AccessionModel
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

  private fun updateAccession(
      accessionId: AccessionId,
      modify: (AccessionModel) -> AccessionModel
  ): AccessionModel {
    requirePermissions { updateAccession(accessionId) }

    return dslContext.transactionResult { _ ->
      lockAccession(accessionId)

      val existing = accessionStore.fetchOneById(accessionId)
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
