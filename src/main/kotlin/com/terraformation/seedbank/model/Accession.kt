package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.SeedQuantityUnits
import com.terraformation.seedbank.db.SourcePlantOrigin
import com.terraformation.seedbank.db.SpeciesEndangeredType
import com.terraformation.seedbank.db.SpeciesRareType
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.WithdrawalPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

enum class AccessionActive {
  Inactive,
  Active
}

fun AccessionState.toActiveEnum() =
    when (this) {
      AccessionState.Withdrawn, AccessionState.Nursery -> AccessionActive.Inactive

      // Don't use "else" here -- we want it to be a compile error if we add a state and forget
      // to specify whether it is active or inactive.
      AccessionState.Pending,
      AccessionState.Processing,
      AccessionState.Processed,
      AccessionState.Drying,
      AccessionState.Dried,
      AccessionState.InStorage -> AccessionActive.Active
    }

enum class AccessionSource {
  Web,
  SeedCollectorApp
}

interface AccessionFields {
  val accessionNumber: String?
    get() = null
  val active: AccessionActive?
    get() = state?.toActiveEnum()
  val bagNumbers: Set<String>?
    get() = null
  val collectedDate: LocalDate?
    get() = null
  val cutTestSeedsCompromised: Int?
    get() = null
  val cutTestSeedsEmpty: Int?
    get() = null
  val cutTestSeedsFilled: Int?
    get() = null
  val deviceInfo: AppDeviceFields?
    get() = null
  val dryingEndDate: LocalDate?
    get() = null
  val dryingMoveDate: LocalDate?
    get() = null
  val dryingStartDate: LocalDate?
    get() = null
  val endangered: SpeciesEndangeredType?
    get() = null
  val environmentalNotes: String?
    get() = null
  val estimatedSeedCount: Int?
    get() = null
  val family: String?
    get() = null
  val fieldNotes: String?
    get() = null
  val founderId: String?
    get() = null
  val geolocations: Set<Geolocation>?
    get() = null
  val germinationTestTypes: Set<GerminationTestType>?
    get() = null
  val germinationTests: List<GerminationTestFields>?
    get() = null
  val landowner: String?
    get() = null
  val latestGerminationTestDate: LocalDate?
    get() = null
  val latestViabilityPercent: Int?
    get() = null
  val numberOfTrees: Int?
    get() = null
  val nurseryStartDate: LocalDate?
    get() = null
  val photoFilenames: List<String>?
    get() = null
  val primaryCollector: String?
    get() = null
  val processingMethod: ProcessingMethod?
    get() = null
  val processingNotes: String?
    get() = null
  val processingStaffResponsible: String?
    get() = null
  val processingStartDate: LocalDate?
    get() = null
  val rare: SpeciesRareType?
    get() = null
  val receivedDate: LocalDate?
    get() = null
  val remaining: SeedQuantityModel?
    get() = null
  val secondaryCollectors: Set<String>?
    get() = null
  val siteLocation: String?
    get() = null
  val source: AccessionSource?
    get() = null
  val sourcePlantOrigin: SourcePlantOrigin?
    get() = null
  val species: String?
    get() = null
  val speciesId: Long?
    get() = null
  val state: AccessionState?
    get() = null
  val storageCondition: StorageCondition?
    get() = null
  val storageLocation: String?
    get() = null
  val storageNotes: String?
    get() = null
  val storagePackets: Int?
    get() = null
  val storageStaffResponsible: String?
    get() = null
  val storageStartDate: LocalDate?
    get() = null
  val subsetCount: Int?
    get() = null
  val subsetWeightQuantity: SeedQuantityModel?
    get() = null
  val targetStorageCondition: StorageCondition?
    get() = null
  val total: SeedQuantityModel?
    get() = null
  val totalViabilityPercent: Int?
    get() = null
  val withdrawals: List<WithdrawalFields>?
    get() = null

  fun validate() {
    when (processingMethod) {
      ProcessingMethod.Count -> validateCountBased()
      ProcessingMethod.Weight -> validateWeightBased()
      null -> {
        if (total != null) {
          throw IllegalArgumentException(
              "Cannot set total accession size without selecting a processing method.")
        }
      }
    }

    if (total == null) {
      if (!germinationTests.isNullOrEmpty()) {
        throw IllegalArgumentException(
            "Cannot create germination tests before setting total accession size")
      }
      if (!withdrawals.isNullOrEmpty()) {
        throw IllegalArgumentException(
            "Cannot withdraw from accession before setting total accession size")
      }
    }
  }

  private fun validateCountBased() {
    total?.let { total ->
      if (total.units != SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Total accession size must be a seed count if processing method is Count")
      }
    }

    listOfNotNull(
        withdrawals?.mapNotNull { it.withdrawn },
        withdrawals?.mapNotNull { it.remaining },
        germinationTests?.mapNotNull { it.remaining },
    )
        .flatten()
        .forEach { quantity ->
          if (quantity.units != SeedQuantityUnits.Seeds) {
            throw IllegalArgumentException(
                "Seed quantities can't be specified by weight if processing method is Count")
          }
        }
  }

  private fun validateWeightBased() {
    total?.let { total ->
      if (total.units == SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Total accession size must be a weight measurement if processing method is Weight")
      }
    }

    val germinationTestRemaining = germinationTests.orEmpty().map { it.remaining }
    val withdrawalRemaining = withdrawals.orEmpty().map { it.remaining }
    (germinationTestRemaining + withdrawalRemaining).forEach { quantity ->
      if (quantity == null) {
        throw IllegalArgumentException(
            "Germination tests and withdrawals must include remaining quantity if accession " +
                "processing method is Weight")
      } else if (quantity.units == SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Seeds remaining on germination tests and withdrawals must be weight-based if " +
                "accession processing method is Weight")
      }
    }
  }

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

  fun hasSeedCount(): Boolean =
      total?.units == SeedQuantityUnits.Seeds ||
          (total != null && subsetCount != null && subsetWeightQuantity != null)

  private fun hasTestResults(): Boolean = hasCutTestResults() || hasGerminationTestResults()

  fun calculateLatestGerminationRecordingDate(): LocalDate? {
    return getLatestGerminationTestWithResults()?.calculateLatestRecordingDate()
  }

  fun calculateLatestViabilityPercent(): Int? {
    return getLatestGerminationTestWithResults()?.calculateTotalPercentGerminated()
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

  fun calculateRemaining(clock: Clock): SeedQuantityModel? {
    val remaining = calculateWithdrawals(clock).lastOrNull()?.remaining ?: total
    return remaining?.toUnits(total?.units ?: remaining.units)
  }

  fun calculateEstimatedSeedCount(): Int? {
    val total = this.total ?: return null

    return if (total.units == SeedQuantityUnits.Seeds) {
      total.quantity.toInt()
    } else {
      val subsetCount = this.subsetCount?.let { BigDecimal(it) } ?: return null
      val subsetGrams = this.subsetWeightQuantity?.grams ?: return null
      val totalGrams = total.grams ?: return null
      (subsetCount * totalGrams / subsetGrams).toInt()
    }
  }

  fun calculateProcessingStartDate(clock: Clock): LocalDate? {
    return processingStartDate ?: if (hasSeedCount()) LocalDate.now(clock) else null
  }

  /**
   * @return Withdrawals in descending order of seeds remaining; the last item in the list will be
   * the one with the seeds-remaining value for the accession as a whole.
   */
  fun calculateWithdrawals(
      clock: Clock,
      existingWithdrawals: Collection<WithdrawalFields>? = withdrawals
  ): List<WithdrawalFields> {
    if (withdrawals.isNullOrEmpty() && germinationTests.isNullOrEmpty()) {
      return emptyList()
    }

    var currentRemaining =
        total
            ?: throw IllegalStateException(
                "Cannot withdraw from accession before specifying its total size")

    val existingIds = existingWithdrawals.orEmpty().mapNotNull { it.id }.toSet()
    withdrawals?.mapNotNull { it.id }?.forEach { id ->
      if (id !in existingIds) {
        throw IllegalArgumentException("Cannot update withdrawal with nonexistent ID $id")
      }
    }

    val nonTestWithdrawals =
        withdrawals.orEmpty().filter { it.purpose != WithdrawalPurpose.GerminationTesting }
    val existingTestWithdrawals =
        existingWithdrawals.orEmpty().filter { it.germinationTestId != null }.associateBy {
          it.germinationTestId!!
        }
    val testWithdrawals =
        germinationTests.orEmpty().map { test ->
          val existingWithdrawal = test.id?.let { existingTestWithdrawals[it] }
          val withdrawn =
              test.seedsSown?.let { SeedQuantityModel(BigDecimal(it), SeedQuantityUnits.Seeds) }
          GerminationTestWithdrawal(
              date = test.startDate ?: existingWithdrawal?.date ?: LocalDate.now(clock),
              germinationTest = test,
              germinationTestId = test.id,
              id = existingWithdrawal?.id,
              remaining = test.remaining,
              staffResponsible = test.staffResponsible,
              withdrawn = withdrawn,
          )
        }

    val unsortedWithdrawals = nonTestWithdrawals + testWithdrawals

    return when (processingMethod) {
      ProcessingMethod.Count -> {
        unsortedWithdrawals.sortedWith { a, b -> a.compareByTime(b) }.map { withdrawal ->
          withdrawal.withdrawn?.let { withdrawn -> currentRemaining -= withdrawn }
          withdrawal.withRemaining(currentRemaining)
        }
      }
      ProcessingMethod.Weight -> {
        unsortedWithdrawals.sortedByDescending { it.remaining }.map { withdrawal ->
          val remaining =
              withdrawal.remaining
                  ?: throw IllegalArgumentException(
                      "Withdrawals from weight-based accessions must include seeds remaining")
          val difference = currentRemaining - remaining
          currentRemaining = remaining
          withdrawal.withWeightDifference(difference)
        }
      }
      null -> {
        throw IllegalStateException("Cannot add withdrawals before setting processingMethod")
      }
    }
  }

  fun foldWithdrawalQuantities(
      clock: Clock,
      predicate: (WithdrawalFields) -> Boolean = { true }
  ): SeedQuantityModel? {
    val total = this.total ?: return null
    val withdrawals = calculateWithdrawals(clock).filter(predicate)
    val hasCountBasedQuantities = withdrawals.any { it.withdrawn?.units == SeedQuantityUnits.Seeds }
    val hasNonCountBasedQuantities =
        withdrawals.any { it.withdrawn != null && it.withdrawn?.units != SeedQuantityUnits.Seeds }
    val units =
        if (hasCountBasedQuantities && !hasNonCountBasedQuantities) SeedQuantityUnits.Seeds
        else total.units
    val zero = SeedQuantityModel(BigDecimal.ZERO, units)

    // If all the quantities are count-based, return a count-based total.
    val totalQuantity =
        if (units == SeedQuantityUnits.Seeds) {
          withdrawals.mapNotNull { it.withdrawn }.foldRight(zero) { quantity, acc ->
            acc + quantity
          }
        } else {
          withdrawals.mapNotNull { it.weightDifference }.foldRight(zero) { quantity, acc ->
            acc + quantity
          }
        }

    return if (totalQuantity > zero) totalQuantity else null
  }

  fun calculateTotalScheduledNonTestQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) {
      it.purpose != WithdrawalPurpose.GerminationTesting && it.date > today
    }
  }

  fun calculateTotalScheduledTestQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) {
      it.purpose == WithdrawalPurpose.GerminationTesting && it.date > today
    }
  }

  fun calculateTotalScheduledWithdrawalQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) { it.date > today }
  }

  fun calculateTotalPastWithdrawalQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) { it.date <= today }
  }

  fun calculateTotalWithdrawalQuantity(clock: Clock): SeedQuantityModel? {
    return foldWithdrawalQuantities(clock)
  }
}

data class AccessionModel(
    val id: Long,
    override val accessionNumber: String,
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val cutTestSeedsCompromised: Int? = null,
    override val cutTestSeedsEmpty: Int? = null,
    override val cutTestSeedsFilled: Int? = null,
    override val deviceInfo: AppDeviceModel? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val dryingStartDate: LocalDate? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val estimatedSeedCount: Int? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val germinationTests: List<GerminationTestModel>? = null,
    override val landowner: String? = null,
    override val latestGerminationTestDate: LocalDate? = null,
    override val latestViabilityPercent: Int? = null,
    override val numberOfTrees: Int? = null,
    override val nurseryStartDate: LocalDate? = null,
    override val photoFilenames: List<String>? = null,
    override val primaryCollector: String? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val remaining: SeedQuantityModel? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val source: AccessionSource,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
    override val speciesId: Long? = null,
    override val state: AccessionState,
    override val storageCondition: StorageCondition? = null,
    override val storageLocation: String? = null,
    override val storageNotes: String? = null,
    override val storagePackets: Int? = null,
    override val storageStaffResponsible: String? = null,
    override val storageStartDate: LocalDate? = null,
    override val subsetCount: Int? = null,
    override val subsetWeightQuantity: SeedQuantityModel? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val total: SeedQuantityModel? = null,
    override val totalViabilityPercent: Int? = null,
    override val withdrawals: List<WithdrawalModel>? = null,
) : AccessionFields {
  init {
    validate()
  }

  override val active: AccessionActive
    get() = state.toActiveEnum()

  fun getStateTransition(newFields: AccessionFields, clock: Clock): AccessionStateTransition? {
    val seedsRemaining = newFields.calculateRemaining(clock)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO
    val today = LocalDate.now(clock)

    fun LocalDate?.hasArrived(daysAgo: Long = 0) = this != null && this <= today.minusDays(daysAgo)

    val seedCountPresent = newFields.total != null
    val processingForTwoWeeks = newFields.processingStartDate.hasArrived(daysAgo = 14)
    val dryingStarted = newFields.dryingStartDate.hasArrived()
    val dryingEnded = newFields.dryingEndDate.hasArrived()
    val storageStarted = newFields.storageStartDate.hasArrived()
    val storageDetailsEntered =
        newFields.storagePackets != null || newFields.storageLocation != null
    val nurseryStarted = newFields.nurseryStartDate.hasArrived()

    val desiredState: Pair<AccessionState, String> =
        when {
          nurseryStarted -> AccessionState.Nursery to "Nursery start date has arrived"
          allSeedsWithdrawn -> AccessionState.Withdrawn to "All seeds marked as withdrawn"
          storageDetailsEntered ->
              AccessionState.InStorage to "Number of packets or location has been entered"
          storageStarted -> AccessionState.InStorage to "Storage start date has arrived"
          dryingEnded -> AccessionState.Dried to "Drying end date has arrived"
          dryingStarted -> AccessionState.Drying to "Drying start date has arrived"
          processingForTwoWeeks ->
              AccessionState.Processed to "2 weeks have passed since processing start date"
          seedCountPresent -> AccessionState.Processing to "Seed count/weight has been entered"
          else -> AccessionState.Pending to "No state conditions have been met"
        }

    return if (desiredState.first != state) {
      AccessionStateTransition(desiredState.first, desiredState.second)
    } else {
      null
    }
  }
}

data class AccessionStateTransition(
    val newState: AccessionState,
    val reason: String,
)
