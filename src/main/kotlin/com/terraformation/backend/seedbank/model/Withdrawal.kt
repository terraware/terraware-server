package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.util.compareNullsFirst
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.Record

data class WithdrawalModel(
    val accessionId: AccessionId? = null,
    val createdTime: Instant? = null,
    val date: LocalDate,
    val destination: String? = null,
    val estimatedCount: Int? = null,
    val estimatedWeight: SeedQuantityModel? = null,
    val id: WithdrawalId? = null,
    val notes: String? = null,
    val purpose: WithdrawalPurpose? = null,
    val remaining: SeedQuantityModel? = null,
    val staffResponsible: String? = null,
    val viabilityTest: ViabilityTestModel? = null,
    val viabilityTestId: ViabilityTestId? = null,
    /**
     * The server-calculated withdrawal weight based on the difference between [remaining] on this
     * withdrawal and the previous one. Only valid for weight-based accessions.
     */
    val weightDifference: SeedQuantityModel? = null,
    /** The user-entered withdrawal quantity. */
    val withdrawn: SeedQuantityModel? = null,
    val withdrawnByName: String? = null,
    val withdrawnByUserId: UserId? = null,
) {
  constructor(
      record: Record,
      fullName: String?,
  ) : this(
      accessionId = record[WITHDRAWALS.ACCESSION_ID],
      createdTime = record[WITHDRAWALS.CREATED_TIME],
      date = record[WITHDRAWALS.DATE]!!,
      destination = record[WITHDRAWALS.DESTINATION],
      estimatedCount = record[WITHDRAWALS.ESTIMATED_COUNT],
      estimatedWeight =
          SeedQuantityModel.of(
              record[WITHDRAWALS.ESTIMATED_WEIGHT_QUANTITY],
              record[WITHDRAWALS.ESTIMATED_WEIGHT_UNITS_ID]),
      id = record[WITHDRAWALS.ID],
      notes = record[WITHDRAWALS.NOTES],
      purpose = record[WITHDRAWALS.PURPOSE_ID],
      remaining =
          SeedQuantityModel.of(
              record[WITHDRAWALS.REMAINING_QUANTITY], record[WITHDRAWALS.REMAINING_UNITS_ID]),
      staffResponsible = record[WITHDRAWALS.STAFF_RESPONSIBLE],
      viabilityTestId = record[WITHDRAWALS.VIABILITY_TEST_ID],
      withdrawn =
          SeedQuantityModel.of(
              record[WITHDRAWALS.WITHDRAWN_QUANTITY], record[WITHDRAWALS.WITHDRAWN_UNITS_ID]),
      withdrawnByName = fullName,
      withdrawnByUserId = record[WITHDRAWALS.WITHDRAWN_BY],
  )

  init {
    validate()
  }

  fun validate() {
    remaining?.quantity?.signum()?.let { signum ->
      if (signum < 0) {
        throw IllegalArgumentException("Remaining quantity may not be negative")
      }
    }

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
        withdrawn.equalsIgnoreScale(other.withdrawn) &&
        withdrawnByUserId == other.withdrawnByUserId
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
      weightDifference != null -> weightDifference
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
