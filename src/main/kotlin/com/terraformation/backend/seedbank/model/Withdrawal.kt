package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.references.WITHDRAWALS
import com.terraformation.backend.util.compareNullsLast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.Record

data class WithdrawalModel(
    val accessionId: AccessionId? = null,
    val batchId: BatchId? = null,
    val createdTime: Instant? = null,
    val date: LocalDate,
    val destination: String? = null,
    val estimatedCount: Int? = null,
    val estimatedWeight: SeedQuantityModel? = null,
    val id: WithdrawalId? = null,
    val notes: String? = null,
    val purpose: WithdrawalPurpose? = null,
    val staffResponsible: String? = null,
    val viabilityTest: ViabilityTestModel? = null,
    val viabilityTestId: ViabilityTestId? = null,
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
      batchId = record[WITHDRAWALS.BATCH_ID],
      createdTime = record[WITHDRAWALS.CREATED_TIME],
      date = record[WITHDRAWALS.DATE]!!,
      destination = record[WITHDRAWALS.DESTINATION],
      estimatedCount = record[WITHDRAWALS.ESTIMATED_COUNT],
      estimatedWeight =
          SeedQuantityModel.of(
              record[WITHDRAWALS.ESTIMATED_WEIGHT_QUANTITY],
              record[WITHDRAWALS.ESTIMATED_WEIGHT_UNITS_ID],
          ),
      id = record[WITHDRAWALS.ID],
      notes = record[WITHDRAWALS.NOTES],
      purpose = record[WITHDRAWALS.PURPOSE_ID],
      staffResponsible = record[WITHDRAWALS.STAFF_RESPONSIBLE],
      viabilityTestId = record[WITHDRAWALS.VIABILITY_TEST_ID],
      withdrawn =
          SeedQuantityModel.of(
              record[WITHDRAWALS.WITHDRAWN_QUANTITY],
              record[WITHDRAWALS.WITHDRAWN_UNITS_ID],
          ),
      withdrawnByName = fullName,
      withdrawnByUserId = record[WITHDRAWALS.WITHDRAWN_BY],
  )

  init {
    validate()
  }

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
        withdrawn.equalsIgnoreScale(other.withdrawn) &&
        withdrawnByUserId == other.withdrawnByUserId
  }

  fun compareByTime(other: WithdrawalModel): Int {
    val dateComparison = date.compareTo(other.date)
    return if (dateComparison != 0) {
      dateComparison
    } else {
      val idComparison = id?.value.compareNullsLast(other.id?.value)
      if (idComparison != 0) {
        idComparison
      } else {
        // No useful sort order, but we want a stable one
        hashCode().compareTo(other.hashCode())
      }
    }
  }

  fun calculateEstimatedCount(subsetWeight: SeedQuantityModel?, subsetCount: Int?): Int? {
    return when {
      withdrawn == null -> null
      withdrawn.units == SeedQuantityUnits.Seeds -> withdrawn.quantity.toInt()
      subsetCount == null || subsetWeight == null -> null
      else -> withdrawn.toUnits(SeedQuantityUnits.Seeds, subsetWeight, subsetCount).quantity.toInt()
    }
  }

  fun calculateEstimatedWeight(
      subsetWeight: SeedQuantityModel?,
      subsetCount: Int?,
      units: SeedQuantityUnits?,
  ): SeedQuantityModel? {
    return when {
      withdrawn == null -> null
      withdrawn.units != SeedQuantityUnits.Seeds -> withdrawn
      subsetWeight == null || subsetCount == null -> null
      units == SeedQuantityUnits.Seeds || units == null ->
          withdrawn.toUnits(subsetWeight.units, subsetWeight, subsetCount)
      else -> withdrawn.toUnits(units, subsetWeight, subsetCount)
    }
  }

  fun isAfter(instant: Instant, timeZone: ZoneId): Boolean {
    val instantDate = LocalDate.ofInstant(instant, timeZone)
    return when {
      date < instantDate -> false
      date > instantDate -> true
      createdTime != null -> createdTime > instant
      // This is a newly-created withdrawal, so it comes after any existing times.
      else -> true
    }
  }
}
