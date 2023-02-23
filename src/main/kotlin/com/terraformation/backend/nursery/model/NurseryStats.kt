package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.WithdrawalPurpose

/** Aggregated statistics for a nursery. Totals are across all batches and withdrawals. */
data class NurseryStats(
    val facilityId: FacilityId,
    val totalGerminating: Long,
    val totalNotReady: Long,
    val totalReady: Long,
    val totalWithdrawnByPurpose: Map<WithdrawalPurpose, Long>,
) {
  /**
   * The nursery's mortality rate as a percentage. The mortality rate is calculated as the total
   * number of plants withdrawn with a purpose of "Dead" divided by the total number of plants that
   * have ever been in the nursery (either current inventory or already withdrawn).
   */
  val mortalityRate: Int
    get() {
      val totalDead = totalWithdrawnByPurpose[WithdrawalPurpose.Dead] ?: 0L
      val totalPlants = totalInventory + totalWithdrawnByPurpose.values.sum()

      return if (totalPlants == 0L) {
        0
      } else {
        ((totalDead * 100) / totalPlants).toInt()
      }
    }

  /** The total number of plants currently in inventory. */
  private val totalInventory: Long
    get() = totalNotReady + totalReady

  val totalPlantsPropagated: Long
    get() = (totalWithdrawnByPurpose[WithdrawalPurpose.OutPlant] ?: 0L) + totalInventory
}
