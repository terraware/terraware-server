package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.records.WithdrawalsRecord
import com.terraformation.backend.util.compareNullsFirst
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class WithdrawalModel(
    val id: WithdrawalId? = null,
    val accessionId: AccessionId? = null,
    val createdTime: Instant? = null,
    val date: LocalDate,
    val estimatedCount: Int? = null,
    val estimatedWeight: SeedQuantityModel? = null,
    val purpose: WithdrawalPurpose? = null,
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
      record.createdTime,
      record.date!!,
      record.estimatedCount,
      SeedQuantityModel.of(record.estimatedWeightQuantity, record.estimatedWeightUnitsId),
      record.purposeId,
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
        estimatedCount == other.estimatedCount &&
        estimatedWeight == other.estimatedWeight &&
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

  fun calculateEstimatedCount(subsetWeight: SeedQuantityModel?, subsetCount: Int?): Int? {
    val quantity = calculateEstimatedQuantity()
    return when {
      quantity == null -> null
      quantity.units == SeedQuantityUnits.Seeds -> quantity.quantity.toInt()
      subsetCount == null || subsetWeight == null -> null
      else -> quantity.toUnits(SeedQuantityUnits.Seeds, subsetWeight, subsetCount).quantity.toInt()
    }
  }

  fun calculateEstimatedQuantity(): SeedQuantityModel? = withdrawn ?: weightDifference

  fun calculateEstimatedWeight(
      subsetWeight: SeedQuantityModel?,
      subsetCount: Int?,
      units: SeedQuantityUnits?
  ): SeedQuantityModel? {
    val quantity = calculateEstimatedQuantity()
    return when {
      quantity == null -> null
      quantity.units != SeedQuantityUnits.Seeds -> quantity
      subsetWeight == null || subsetCount == null -> null
      units == SeedQuantityUnits.Seeds || units == null ->
          quantity.toUnits(subsetWeight.units, subsetWeight, subsetCount)
      else -> quantity.toUnits(units, subsetWeight, subsetCount)
    }
  }

  fun isAfter(instant: Instant): Boolean {
    val instantDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
    return when {
      date < instantDate -> false
      date > instantDate -> true
      createdTime != null -> createdTime > instant
      // This is a newly-created withdrawal, so it comes after any existing times.
      else -> true
    }
  }

  fun toV1Compatible(
      remaining: SeedQuantityModel?,
      subsetWeight: SeedQuantityModel?,
      subsetCount: Int?
  ): WithdrawalModel {
    val estimatedQuantity =
        calculateEstimatedQuantity()
            ?: throw IllegalStateException("Cannot calculate withdrawal quantity")

    return if (remaining?.units == SeedQuantityUnits.Seeds &&
        estimatedQuantity.units != SeedQuantityUnits.Seeds) {
      copy(
          withdrawn = estimatedQuantity.toUnits(SeedQuantityUnits.Seeds, subsetWeight, subsetCount))
    } else {
      // Withdrawn can be in seeds or weight for weight-based accessions
      this
    }
  }
}
