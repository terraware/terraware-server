package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.util.compareNullsFirst
import java.time.LocalDate

data class WithdrawalModel(
    val id: Long? = null,
    val accessionId: Long? = null,
    val date: LocalDate,
    val purpose: WithdrawalPurpose,
    val destination: String? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    val germinationTestId: Long? = null,
    val remaining: SeedQuantityModel? = null,
    /** The user-entered withdrawal quantity. */
    val withdrawn: SeedQuantityModel? = null,
    val germinationTest: GerminationTestModel? = null,
    /**
     * The server-calculated withdrawal weight based on the difference between [remaining] on this
     * withdrawal and the previous one. Only valid for weight-based accessions.
     */
    val weightDifference: SeedQuantityModel? = null,
) {

  fun validate() {
    withdrawn?.quantity?.signum()?.let { signum ->
      if (signum <= 0) {
        throw IllegalArgumentException("Withdrawn quantity must be greater than 0")
      }
    }
  }

  /**
   * Compares two withdrawals for equality disregarding scale differences in quantities and also
   * ignoring differences in modification timestamp.
   */
  fun fieldsEqual(other: WithdrawalModel): Boolean {
    return id == other.id &&
        date == other.date &&
        purpose == other.purpose &&
        destination == other.destination &&
        germinationTestId == other.germinationTestId &&
        notes == other.notes &&
        staffResponsible == other.staffResponsible &&
        remaining.equalsIgnoreScale(other.remaining) &&
        weightDifference.equalsIgnoreScale(other.weightDifference) &&
        withdrawn.equalsIgnoreScale(other.withdrawn)
  }

  fun compareByTime(other: WithdrawalModel): Int {
    val dateComparison = date.compareTo(other.date)
    return if (dateComparison != 0) {
      dateComparison
    } else {
      val idComparison = id.compareNullsFirst(other.id)
      if (idComparison != 0) {
        idComparison
      } else {
        // No useful sort order, but we want a stable one
        hashCode().compareTo(other.hashCode())
      }
    }
  }

  fun calculateEstimatedQuantity(): SeedQuantityModel? = withdrawn ?: weightDifference
}
