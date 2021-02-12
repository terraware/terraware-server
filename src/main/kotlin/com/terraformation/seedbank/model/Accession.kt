package com.terraformation.seedbank.model

import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.StorageCondition
import java.math.BigDecimal
import java.time.LocalDate

enum class AccessionActive {
  Inactive,
  Active
}

fun AccessionState.toActiveEnum() =
    when (this) {
      AccessionState.Withdrawn -> AccessionActive.Inactive

      // Don't use "else" here -- we want it to be a compile error if we add a state and forget
      // to specify whether it is active or inactive.
      AccessionState.Pending,
      AccessionState.Processing,
      AccessionState.Processed,
      AccessionState.Drying,
      AccessionState.Dried,
      AccessionState.InStorage -> AccessionActive.Active
    }

interface AccessionFields {
  val accessionNumber: String?
    get() = null
  val state: AccessionState?
    get() = null
  val active: AccessionActive?
    get() = state?.toActiveEnum()
  val species: String?
    get() = null
  val family: String?
    get() = null
  val numberOfTrees: Int?
    get() = null
  val founderId: String?
    get() = null
  val endangered: Boolean?
    get() = null
  val rare: Boolean?
    get() = null
  val fieldNotes: String?
    get() = null
  val collectedDate: LocalDate?
    get() = null
  val receivedDate: LocalDate?
    get() = null
  val primaryCollector: String?
    get() = null
  val secondaryCollectors: Set<String>?
    get() = null
  val siteLocation: String?
    get() = null
  val landowner: String?
    get() = null
  val environmentalNotes: String?
    get() = null
  val processingStartDate: LocalDate?
    get() = null
  val processingMethod: ProcessingMethod?
    get() = null
  val seedsCounted: Int?
    get() = null
  val subsetWeightGrams: BigDecimal?
    get() = null
  val totalWeightGrams: BigDecimal?
    get() = null
  val subsetCount: Int?
    get() = null
  val estimatedSeedCount: Int?
    get() = null
  val targetStorageCondition: StorageCondition?
    get() = null
  val dryingStartDate: LocalDate?
    get() = null
  val dryingEndDate: LocalDate?
    get() = null
  val dryingMoveDate: LocalDate?
    get() = null
  val processingNotes: String?
    get() = null
  val processingStaffResponsible: String?
    get() = null
  val bagNumbers: Set<String>?
    get() = null
  val storageStartDate: LocalDate?
    get() = null
  val storagePackets: Int?
    get() = null
  val storageLocation: String?
    get() = null
  val storageCondition: StorageCondition?
    get() = null
  val storageNotes: String?
    get() = null
  val storageStaffResponsible: String?
    get() = null
  val photoFilenames: List<String>?
    get() = null
  val geolocations: Set<Geolocation>?
    get() = null
  val germinationTestTypes: Set<GerminationTestType>?
    get() = null
  val germinationTests: List<GerminationTestFields>?
    get() = null
  val withdrawals: List<WithdrawalFields>?
    get() = null
  val latestGerminationTestDate: LocalDate?
    get() = null
  val latestViabilityPercent: Int?
    get() = null
  val totalViabilityPercent: Int?
    get() = null
  val cutTestSeedsFilled: Int?
    get() = null
  val cutTestSeedsEmpty: Int?
    get() = null
  val cutTestSeedsCompromised: Int?
    get() = null
  val deviceInfo: AppDeviceFields?
    get() = null
  val seedsRemaining: Int?
    get() = null

  private fun getLatestGerminationTestWithResults(): GerminationTestFields? {
    return germinationTests
        ?.filter { it.calculateLatestRecordingDate() != null && it.seedsSown != null }
        ?.maxByOrNull { it.calculateLatestRecordingDate()!! }
  }

  private fun getCutTestTotal(): Int? =
      if (hasCutTestResults()) {
        (cutTestSeedsCompromised ?: 0) + (cutTestSeedsEmpty ?: 0) + (cutTestSeedsFilled ?: 0)
      } else {
        null
      }

  private fun hasGerminationTestResults(): Boolean =
      germinationTests.orEmpty().any { !it.germinations.isNullOrEmpty() }

  private fun hasCutTestResults(): Boolean =
      cutTestSeedsCompromised != null && cutTestSeedsEmpty != null && cutTestSeedsFilled != null

  private fun hasTestResults(): Boolean = hasCutTestResults() || hasGerminationTestResults()

  fun calculateLatestGerminationRecordingDate(): LocalDate? {
    return getLatestGerminationTestWithResults()?.calculateLatestRecordingDate()
  }

  fun calculateLatestViabilityPercent(): Int? {
    val latestTest = getLatestGerminationTestWithResults()
    val germinated = latestTest?.calculateTotalSeedsGerminated()
    val sown = latestTest?.seedsSown ?: 0
    return if (sown > 0 && germinated != null) {
      germinated * 100 / sown
    } else {
      null
    }
  }

  fun calculateTotalViabilityPercent(): Int? {
    if (!hasTestResults()) {
      return null
    }

    val tests = germinationTests ?: emptyList()

    val totalGerminationTested = tests.mapNotNull { it.seedsSown }.sum()
    val totalGerminated =
        tests.sumOf { test -> test.germinations?.sumOf { it.seedsGerminated } ?: 0 }

    val cutTestFilled = if (hasCutTestResults()) cutTestSeedsFilled!! else 0
    val totalTested = totalGerminationTested + (getCutTestTotal() ?: 0)
    val totalViable = totalGerminated + cutTestFilled

    return if (totalTested > 0) {
      totalViable * 100 / totalTested
    } else {
      null
    }
  }

  fun calculateSeedsRemaining(): Int? {
    val initialCount = seedsCounted ?: calculateEstimatedSeedCount() ?: return null
    val cutTested = getCutTestTotal() ?: 0
    val sown = germinationTests?.mapNotNull { it.seedsSown }?.sum() ?: 0
    val withdrawn = withdrawals?.sumOf { it.computeSeedsWithdrawn(this, true) } ?: 0

    return initialCount - sown - cutTested - withdrawn
  }

  fun calculateEstimatedSeedCount(): Int? {
    return if (seedsCounted == null &&
        subsetCount != null &&
        subsetWeightGrams != null &&
        totalWeightGrams != null) {
      (BigDecimal(subsetCount!!) * totalWeightGrams!! / subsetWeightGrams!!).toInt()
    } else {
      null
    }
  }
}

interface ConcreteAccession : AccessionFields {
  override val accessionNumber: String
  override val state: AccessionState
  override val active: AccessionActive
    get() = state.toActiveEnum()
}

data class AccessionModel(
    val id: Long,
    override val accessionNumber: String,
    override val state: AccessionState,
    override val species: String? = null,
    override val family: String? = null,
    override val numberOfTrees: Int? = null,
    override val founderId: String? = null,
    override val endangered: Boolean? = null,
    override val rare: Boolean? = null,
    override val fieldNotes: String? = null,
    override val collectedDate: LocalDate? = null,
    override val receivedDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val landowner: String? = null,
    override val environmentalNotes: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val seedsCounted: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val totalWeightGrams: BigDecimal? = null,
    override val subsetCount: Int? = null,
    override val estimatedSeedCount: Int? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val dryingStartDate: LocalDate? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val bagNumbers: Set<String>? = null,
    override val storageStartDate: LocalDate? = null,
    override val storagePackets: Int? = null,
    override val storageLocation: String? = null,
    override val storageCondition: StorageCondition? = null,
    override val storageNotes: String? = null,
    override val storageStaffResponsible: String? = null,
    override val photoFilenames: List<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val germinationTests: List<GerminationTestModel>? = null,
    override val withdrawals: List<WithdrawalModel>? = null,
    override val latestGerminationTestDate: LocalDate? = null,
    override val latestViabilityPercent: Int? = null,
    override val totalViabilityPercent: Int? = null,
    override val cutTestSeedsFilled: Int? = null,
    override val cutTestSeedsEmpty: Int? = null,
    override val cutTestSeedsCompromised: Int? = null,
    override val deviceInfo: AppDeviceModel? = null,
    override val seedsRemaining: Int? = null,
) : ConcreteAccession
