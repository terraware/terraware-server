package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.records.WithdrawalsRecord
import com.terraformation.backend.util.compareNullsFirst
import java.time.LocalDate

data class WithdrawalModel(
    val id: WithdrawalId? = null,
    val accessionId: AccessionId? = null,
    val date: LocalDate,
    val purpose: WithdrawalPurpose,
    val destination: String? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    val viabilityTestId: ViabilityTestId? = null,
    val remaining: SeedQuantityModel? = null,
    /** The user-entered withdrawal quantity. */
    val withdrawn: SeedQuantityModel? = null,
    val viabilityTest: ViabilityTestModel? = null,
    /**
     * The server-calculated withdrawal weight based on the difference between [remaining] on this
     * withdrawal and the previous one. Only valid for weight-based accessions.
     */
    val weightDifference: SeedQuantityModel? = null,
) {
  constructor(
      record: WithdrawalsRecord
  ) : this(
      record.id,
      record.accessionId,
      record.date!!,
      record.purposeId!!,
      record.destination,
      record.notes,
      record.staffResponsible,
      record.viabilityTestId,
      SeedQuantityModel.of(record.remainingQuantity, record.remainingUnitsId),
      SeedQuantityModel.of(record.withdrawnQuantity, record.withdrawnUnitsId),
  )

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
        viabilityTestId == other.viabilityTestId &&
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
      val idComparison = id?.value.compareNullsFirst(other.id?.value)
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
