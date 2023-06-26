package com.terraformation.backend.nursery

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.model.ExistingWithdrawalModel
import com.terraformation.backend.nursery.model.NewWithdrawalModel
import com.terraformation.backend.tracking.db.DeliveryStore
import jakarta.inject.Named
import java.time.LocalDate
import org.jooq.DSLContext

@Named
class BatchService(
    private val batchStore: BatchStore,
    private val deliveryStore: DeliveryStore,
    private val dslContext: DSLContext,
) {
  fun withdraw(
      newWithdrawal: NewWithdrawalModel,
      readyByDate: LocalDate? = null,
      plantingSiteId: PlantingSiteId? = null,
      plantingSubzoneId: PlantingSubzoneId? = null,
  ): ExistingWithdrawalModel {
    return when {
      newWithdrawal.purpose == WithdrawalPurpose.OutPlant && plantingSiteId != null ->
          withdrawToPlantingSite(newWithdrawal, plantingSiteId, plantingSubzoneId)
      newWithdrawal.purpose == WithdrawalPurpose.OutPlant ->
          throw IllegalArgumentException("Planting site ID is required for outplanting withdrawals")
      else -> batchStore.withdraw(newWithdrawal, readyByDate)
    }
  }

  private fun withdrawToPlantingSite(
      newWithdrawal: NewWithdrawalModel,
      plantingSiteId: PlantingSiteId,
      plantingSubzoneId: PlantingSubzoneId? = null,
  ): ExistingWithdrawalModel {
    return dslContext.transactionResult { _ ->
      val withdrawal = batchStore.withdraw(newWithdrawal)

      val quantitiesBySpecies: Map<SpeciesId, Int> =
          withdrawal.batchWithdrawals
              .groupBy { batchStore.fetchOneById(it.batchId).speciesId!! }
              .mapValues { (_, batchWithdrawals) -> batchWithdrawals.sumOf { it.totalWithdrawn } }

      val deliveryId =
          deliveryStore.createDelivery(
              withdrawal.id, plantingSiteId, plantingSubzoneId, quantitiesBySpecies)

      withdrawal.copy(deliveryId = deliveryId)
    }
  }
}
