package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import kotlin.math.roundToInt

/**
 * Aggregated statistics for a nursery or an organization. Totals are across all batches and
 * withdrawals.
 */
data class NurseryStats(
    /** If null, stats are organization-wide. */
    val facilityId: FacilityId?,
    val germinationRate: Int?,
    val lossRate: Int?,
    val totalActiveGrowth: Long,
    val totalGerminating: Long,
    val totalHardeningOff: Long,
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
      val totalDead = totalWithdrawnByPurpose[WithdrawalPurpose.Dead]?.toDouble() ?: 0.0
      val totalPlants = (totalInventory + totalWithdrawnByPurpose.values.sum()).toDouble()

      return if (totalPlants == 0.0) {
        0
      } else {
        ((totalDead * 100) / totalPlants).roundToInt()
      }
    }

  /** The total number of plants currently in inventory. */
  val totalInventory: Long
    get() = totalActiveGrowth + totalHardeningOff + totalReady

  val totalPlantsPropagated: Long
    get() = (totalWithdrawnByPurpose[WithdrawalPurpose.OutPlant] ?: 0L) + totalInventory

  val totalWithdrawn: Long
    get() = totalWithdrawnByPurpose.values.sum()
}
