package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.CollectionSource
import com.terraformation.backend.db.DataSource
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SourcePlantOrigin
import com.terraformation.backend.db.SpeciesEndangeredType
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.WithdrawalPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Enum representation of whether or not an accession is in an active state. We use this rather than
 * a boolean because we want the API to have explicit "inactive" and "active" concepts.
 */
enum class AccessionActive {
  Inactive,
  Active
}

fun AccessionState.toActiveEnum() = if (active) AccessionActive.Active else AccessionActive.Inactive

/**
 * All the accession states that are considered active. This is effectively the backing field for
 * [AccessionState.Companion.activeValues], because extension properties don't have backing fields.
 */
private val activeStates = AccessionState.values().filter { it.active }.toSet()

/** All the accession states that are considered active. */
val AccessionState.Companion.activeValues: Set<AccessionState>
  get() = activeStates

/** True if this accession state is supported in the v1 API. */
val AccessionState.isV1Compatible: Boolean
  get() =
      when (this) {
        AccessionState.AwaitingCheckIn,
        AccessionState.Pending,
        AccessionState.Processing,
        AccessionState.Processed,
        AccessionState.Drying,
        AccessionState.Dried,
        AccessionState.InStorage,
        AccessionState.Withdrawn,
        AccessionState.Nursery -> true
        AccessionState.Cleaning,
        AccessionState.AwaitingProcessing,
        AccessionState.UsedUp -> false
      }

val AccessionState.isV2Compatible: Boolean
  get() =
      when (this) {
        AccessionState.AwaitingCheckIn,
        AccessionState.AwaitingProcessing,
        AccessionState.Cleaning,
        AccessionState.Drying,
        AccessionState.InStorage,
        AccessionState.UsedUp -> true
        AccessionState.Pending,
        AccessionState.Processing,
        AccessionState.Withdrawn,
        AccessionState.Nursery,
        AccessionState.Processed,
        AccessionState.Dried -> false
      }

/** Maps v1-only accession states to the corresponding v2-compatible states. */
fun AccessionState.toV2Compatible(): AccessionState =
    when (this) {
      AccessionState.AwaitingCheckIn,
      AccessionState.AwaitingProcessing,
      AccessionState.Cleaning,
      AccessionState.Drying,
      AccessionState.InStorage,
      AccessionState.UsedUp -> this
      AccessionState.Pending -> AccessionState.AwaitingProcessing
      AccessionState.Processing -> AccessionState.Cleaning
      AccessionState.Processed -> AccessionState.Cleaning
      AccessionState.Dried -> AccessionState.InStorage
      AccessionState.Withdrawn -> AccessionState.UsedUp
      AccessionState.Nursery -> AccessionState.UsedUp
    }

data class AccessionModel(
    val id: AccessionId? = null,
    val accessionNumber: String? = null,
    val bagNumbers: Set<String> = emptySet(),
    val checkedInTime: Instant? = null,
    val collectedDate: LocalDate? = null,
    val collectionSiteCity: String? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    val collectors: List<String> = emptyList(),
    val cutTestSeedsCompromised: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsFilled: Int? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val dryingStartDate: LocalDate? = null,
    val endangered: SpeciesEndangeredType? = null,
    val estimatedSeedCount: Int? = null,
    val facilityId: FacilityId? = null,
    val family: String? = null,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation> = emptySet(),
    val isManualState: Boolean = false,
    val latestViabilityPercent: Int? = null,
    val latestViabilityTestDate: LocalDate? = null,
    val numberOfTrees: Int? = null,
    val nurseryStartDate: LocalDate? = null,
    val photoFilenames: List<String> = emptyList(),
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val processingStartDate: LocalDate? = null,
    val rare: RareType? = null,
    val receivedDate: LocalDate? = null,
    val remaining: SeedQuantityModel? = null,
    val source: DataSource? = null,
    val sourcePlantOrigin: SourcePlantOrigin? = null,
    val species: String? = null,
    val speciesCommonName: String? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionState? = null,
    val storageCondition: StorageCondition? = null,
    val storageLocation: String? = null,
    val storageNotes: String? = null,
    val storagePackets: Int? = null,
    val storageStaffResponsible: String? = null,
    val storageStartDate: LocalDate? = null,
    val subsetCount: Int? = null,
    val subsetWeightQuantity: SeedQuantityModel? = null,
    val targetStorageCondition: StorageCondition? = null,
    val total: SeedQuantityModel? = null,
    val totalViabilityPercent: Int? = null,
    val viabilityTests: List<ViabilityTestModel> = emptyList(),
    val withdrawals: List<WithdrawalModel> = emptyList(),
) {
  init {
    validate()
  }

  val active: AccessionActive?
    get() = state?.toActiveEnum()

  fun getStateTransition(newModel: AccessionModel, clock: Clock): AccessionStateTransition? {
    if (newModel.isManualState) {
      return getManualStateTransition(newModel, clock)
    }

    val seedsRemaining = newModel.calculateRemaining(clock)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO
    val today = LocalDate.now(clock)

    fun LocalDate?.hasArrived(daysAgo: Long = 0) = this != null && this <= today.minusDays(daysAgo)
    fun Instant?.hasArrived() = this != null && this <= clock.instant()

    val seedCountPresent = newModel.total != null
    val processingForTwoWeeks = newModel.processingStartDate.hasArrived(daysAgo = 14)
    val dryingStarted = newModel.dryingStartDate.hasArrived()
    val dryingEnded = newModel.dryingEndDate.hasArrived()
    val storageStarted = newModel.storageStartDate.hasArrived()
    val storageDetailsEntered = newModel.storagePackets != null || newModel.storageLocation != null
    val nurseryStarted = newModel.nurseryStartDate.hasArrived()
    val checkedIn = newModel.checkedInTime.hasArrived()

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
          checkedIn -> AccessionState.Pending to "Accession has been checked in"
          else -> AccessionState.AwaitingCheckIn to "No state conditions have been met"
        }

    return if (desiredState.first != state) {
      AccessionStateTransition(desiredState.first, desiredState.second)
    } else {
      null
    }
  }

  private fun getManualStateTransition(
      newModel: AccessionModel,
      clock: Clock
  ): AccessionStateTransition? {
    val seedsRemaining = newModel.calculateRemaining(clock)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO
    val oldState = state ?: AccessionState.AwaitingProcessing
    val newState = newModel.state ?: AccessionState.AwaitingProcessing
    val alreadyCheckedIn = oldState != AccessionState.AwaitingCheckIn
    val revertingToAwaitingCheckIn =
        alreadyCheckedIn && newModel.state == AccessionState.AwaitingCheckIn
    val addingSeedsWhenUsedUp =
        oldState == AccessionState.UsedUp && newState == AccessionState.UsedUp && !allSeedsWithdrawn
    val changingToUsedUpWithoutWithdrawingAllSeeds =
        newState == AccessionState.UsedUp && !allSeedsWithdrawn

    val desiredState: Pair<AccessionState, String> =
        when {
          allSeedsWithdrawn -> AccessionState.UsedUp to "All seeds marked as withdrawn"
          addingSeedsWhenUsedUp -> AccessionState.InStorage to "Accession is not used up"
          revertingToAwaitingCheckIn -> oldState to "Cannot revert to Awaiting Check-In"
          changingToUsedUpWithoutWithdrawingAllSeeds ->
              oldState to "Cannot change to Used Up before withdrawing all seeds"
          else -> newState to "Accession has been edited"
        }

    return if (desiredState.first != state) {
      AccessionStateTransition(desiredState.first, desiredState.second)
    } else {
      null
    }
  }

  private fun validate() {
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
      if (viabilityTests.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot create viability tests before setting total accession size")
      }
      if (withdrawals.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot withdraw from accession before setting total accession size")
      }
    }

    if (viabilityTests.count { it.selected } > 1) {
      throw IllegalArgumentException("Cannot select multiple viability tests")
    }
  }

  private fun validateCountBased() {
    total?.let { total ->
      if (total.units != SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Total accession size must be a seed count if processing method is Count")
      }
      if (total.quantity.signum() <= 0) {
        throw IllegalArgumentException("Total accession size must be greater than 0")
      }
    }

    listOfNotNull(
            withdrawals.mapNotNull { it.withdrawn },
            withdrawals.mapNotNull { it.remaining },
            viabilityTests.mapNotNull { it.remaining },
        )
        .flatten()
        .forEach { quantity ->
          if (quantity.units != SeedQuantityUnits.Seeds) {
            throw IllegalArgumentException(
                "Seed quantities can't be specified by weight if processing method is Count")
          }

          if (quantity.quantity.signum() < 0) {
            throw IllegalArgumentException("Cannot withdraw more seeds than are in the accession")
          }
        }

    val totalWithdrawn =
        listOfNotNull(
                withdrawals
                    .filter { it.purpose != WithdrawalPurpose.ViabilityTesting }
                    .mapNotNull { it.withdrawn?.quantity },
                viabilityTests.mapNotNull { it.seedsSown?.toBigDecimal() })
            .flatten()
            .sumOf { it }
    if (total != null && totalWithdrawn > total.quantity) {
      throw IllegalArgumentException("Cannot withdraw more seeds than are in the accession")
    }
  }

  private fun validateWeightBased() {
    total?.let { total ->
      if (total.units == SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Total accession size must be a weight measurement if processing method is Weight")
      }
      if (total.quantity.signum() <= 0) {
        throw IllegalArgumentException("Total accession size must be greater than 0")
      }
    }

    val viabilityTestRemaining = viabilityTests.map { it.remaining }
    val withdrawalRemaining = withdrawals.map { it.remaining }
    (viabilityTestRemaining + withdrawalRemaining).forEach { quantity ->
      if (quantity == null) {
        throw IllegalArgumentException(
            "Viability tests and withdrawals must include remaining quantity if accession " +
                "processing method is Weight")
      } else if (quantity.units == SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Seeds remaining on viability tests and withdrawals must be weight-based if " +
                "accession processing method is Weight")
      } else if (quantity.quantity.signum() < 0) {
        throw IllegalArgumentException(
            "Seeds remaining on viability tests and withdrawals cannot be negative")
      }
    }
  }

  private fun getLatestViabilityTestWithResults(): ViabilityTestModel? {
    return viabilityTests
        .filter { it.calculateLatestRecordingDate() != null && it.seedsSown != null }
        .maxByOrNull { it.calculateLatestRecordingDate()!! }
  }

  private fun getCutTestTotal(): Int? =
      if (hasCutTestResults()) {
        (cutTestSeedsCompromised ?: 0) + (cutTestSeedsEmpty ?: 0) + (cutTestSeedsFilled ?: 0)
      } else {
        null
      }

  private fun hasViabilityTestResults(): Boolean =
      viabilityTests.any { !it.testResults.isNullOrEmpty() }

  private fun hasCutTestResults(): Boolean =
      cutTestSeedsCompromised != null && cutTestSeedsEmpty != null && cutTestSeedsFilled != null

  private fun hasSeedCount(): Boolean =
      total?.units == SeedQuantityUnits.Seeds ||
          (total != null && subsetCount != null && subsetWeightQuantity != null)

  private fun hasTestResults(): Boolean = hasCutTestResults() || hasViabilityTestResults()

  fun calculateLatestViabilityRecordingDate(): LocalDate? {
    return getLatestViabilityTestWithResults()?.calculateLatestRecordingDate()
  }

  fun calculateLatestViabilityPercent(): Int? {
    return getLatestViabilityTestWithResults()?.calculateTotalPercentGerminated()
  }

  fun calculateTotalViabilityPercent(): Int? {
    if (!hasTestResults()) {
      return null
    }

    val tests = viabilityTests

    val totalViabilityTested = tests.mapNotNull { it.seedsSown }.sum()
    val totalGerminated =
        tests.sumOf { test -> test.testResults?.sumOf { it.seedsGerminated } ?: 0 }

    val cutTestFilled = if (hasCutTestResults()) cutTestSeedsFilled!! else 0
    val totalTested = totalViabilityTested + (getCutTestTotal() ?: 0)
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
      existingWithdrawals: Collection<WithdrawalModel> = withdrawals
  ): List<WithdrawalModel> {
    if (withdrawals.isEmpty() && viabilityTests.isEmpty()) {
      return emptyList()
    }

    var currentRemaining =
        total
            ?: throw IllegalStateException(
                "Cannot withdraw from accession before specifying its total size")

    val existingIds = existingWithdrawals.mapNotNull { it.id }.toSet()
    withdrawals
        .mapNotNull { it.id }
        .forEach { id ->
          if (id !in existingIds) {
            throw IllegalArgumentException("Cannot update withdrawal with nonexistent ID $id")
          }
        }

    val nonTestWithdrawals = withdrawals.filter { it.purpose != WithdrawalPurpose.ViabilityTesting }
    val existingTestWithdrawals =
        existingWithdrawals
            .filter { it.viabilityTestId != null }
            .associateBy { it.viabilityTestId!! }
    val testWithdrawals =
        viabilityTests.map { test ->
          val existingWithdrawal = test.id?.let { existingTestWithdrawals[it] }
          val withdrawn =
              test.seedsSown?.let { SeedQuantityModel(BigDecimal(it), SeedQuantityUnits.Seeds) }
          WithdrawalModel(
              date = test.startDate ?: existingWithdrawal?.date ?: LocalDate.now(clock),
              viabilityTest = test,
              viabilityTestId = test.id,
              id = existingWithdrawal?.id,
              purpose = WithdrawalPurpose.ViabilityTesting,
              remaining = test.remaining,
              staffResponsible = test.staffResponsible,
              withdrawn = withdrawn,
          )
        }

    val unsortedWithdrawals = nonTestWithdrawals + testWithdrawals

    return when (processingMethod) {
      ProcessingMethod.Count -> {
        unsortedWithdrawals
            .sortedWith { a, b -> a.compareByTime(b) }
            .map { withdrawal ->
              withdrawal.withdrawn?.let { withdrawn -> currentRemaining -= withdrawn }
              withdrawal.copy(
                  remaining = currentRemaining,
                  viabilityTest = withdrawal.viabilityTest?.copy(remaining = currentRemaining))
            }
      }
      ProcessingMethod.Weight -> {
        unsortedWithdrawals
            .sortedByDescending { it.remaining }
            .map { withdrawal ->
              val remaining =
                  withdrawal.remaining
                      ?: throw IllegalArgumentException(
                          "Withdrawals from weight-based accessions must include seeds remaining")
              val difference = currentRemaining - remaining
              currentRemaining = remaining
              withdrawal.copy(weightDifference = difference)
            }
      }
      null -> {
        throw IllegalStateException("Cannot add withdrawals before setting processingMethod")
      }
    }
  }

  private fun foldWithdrawalQuantities(
      clock: Clock,
      predicate: (WithdrawalModel) -> Boolean = { true }
  ): SeedQuantityModel? {
    val total = this.total ?: return null
    val withdrawals = calculateWithdrawals(clock).filter(predicate)
    val hasCountBasedQuantities = withdrawals.any { it.withdrawn?.units == SeedQuantityUnits.Seeds }
    val hasNonCountBasedQuantities =
        withdrawals.any { it.withdrawn != null && it.withdrawn.units != SeedQuantityUnits.Seeds }
    val units =
        if (hasCountBasedQuantities && !hasNonCountBasedQuantities) SeedQuantityUnits.Seeds
        else total.units
    val zero = SeedQuantityModel(BigDecimal.ZERO, units)

    // If all the quantities are count-based, return a count-based total.
    val totalQuantity =
        if (units == SeedQuantityUnits.Seeds) {
          withdrawals
              .mapNotNull { it.withdrawn }
              .foldRight(zero) { quantity, acc -> acc + quantity }
        } else {
          withdrawals
              .mapNotNull { it.weightDifference }
              .foldRight(zero) { quantity, acc -> acc + quantity }
        }

    return if (totalQuantity > zero) totalQuantity else null
  }

  fun calculateTotalScheduledNonTestQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) {
      it.purpose != WithdrawalPurpose.ViabilityTesting && it.date > today
    }
  }

  fun calculateTotalScheduledTestQuantity(clock: Clock): SeedQuantityModel? {
    val today = LocalDate.now(clock)
    return foldWithdrawalQuantities(clock) {
      it.purpose == WithdrawalPurpose.ViabilityTesting && it.date > today
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

  fun withCalculatedValues(clock: Clock, existing: AccessionModel = this): AccessionModel {
    val newProcessingStartDate =
        processingStartDate ?: existing.processingStartDate ?: calculateProcessingStartDate(clock)
    val newCollectedDate =
        if (existing.source == DataSource.Web) collectedDate else existing.collectedDate
    val newReceivedDate =
        if (existing.source == DataSource.Web) receivedDate else existing.receivedDate
    val newRemaining = calculateRemaining(clock)
    val newWithdrawals = calculateWithdrawals(clock, existing.withdrawals)
    val newViabilityTests = newWithdrawals.mapNotNull { it.viabilityTest }
    val newState = existing.getStateTransition(this, clock)?.newState ?: existing.state

    return copy(
        collectedDate = newCollectedDate,
        estimatedSeedCount = calculateEstimatedSeedCount(),
        latestViabilityPercent = calculateLatestViabilityPercent(),
        latestViabilityTestDate = calculateLatestViabilityRecordingDate(),
        processingStartDate = newProcessingStartDate,
        receivedDate = newReceivedDate,
        remaining = newRemaining,
        state = newState,
        totalViabilityPercent = calculateTotalViabilityPercent(),
        viabilityTests = newViabilityTests,
        withdrawals = newWithdrawals)
  }
}

data class AccessionStateTransition(
    val newState: AccessionState,
    val reason: String,
)
