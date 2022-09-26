package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.SourcePlantOrigin
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.util.orNull
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

val AccessionState.isV2Compatible: Boolean
  get() =
      when (this) {
        AccessionState.AwaitingCheckIn,
        AccessionState.AwaitingProcessing,
        AccessionState.Processing,
        AccessionState.Drying,
        AccessionState.InStorage,
        AccessionState.UsedUp -> true
        AccessionState.Pending,
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
      AccessionState.Processing,
      AccessionState.Drying,
      AccessionState.InStorage,
      AccessionState.UsedUp -> this
      AccessionState.Pending -> AccessionState.AwaitingProcessing
      AccessionState.Processed -> AccessionState.Drying
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
    val createdTime: Instant? = null,
    val cutTestSeedsCompromised: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsFilled: Int? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val dryingStartDate: LocalDate? = null,
    val estimatedSeedCount: Int? = null,
    val estimatedWeight: SeedQuantityModel? = null,
    val facilityId: FacilityId? = null,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation> = emptySet(),
    val isManualState: Boolean = false,
    val latestObservedQuantity: SeedQuantityModel? = null,
    /**
     * If true, [latestObservedQuantity] already reflects the client-supplied value and shouldn't be
     * recalculated. This is internal state, not persisted to the database or exposed to clients,
     * but needs to be preserved across [copy] calls.
     */
    private val latestObservedQuantityCalculated: Boolean = false,
    val latestObservedTime: Instant? = null,
    val latestViabilityPercent: Int? = null,
    val latestViabilityTestDate: LocalDate? = null,
    val numberOfTrees: Int? = null,
    val nurseryStartDate: LocalDate? = null,
    val photoFilenames: List<String> = emptyList(),
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val processingStartDate: LocalDate? = null,
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
    /** The initial quantity entered by the user. */
    val total: SeedQuantityModel? = null,
    /**
     * The accession's viability. This is calculated as an aggregate of the results of all tests in
     * v1 and is a user-editable value in v2 (exposed in the v2 API as `viabilityPercent`).
     */
    val totalViabilityPercent: Int? = null,
    val viabilityTests: List<ViabilityTestModel> = emptyList(),
    val withdrawals: List<WithdrawalModel> = emptyList(),
) {
  init {
    validate()
  }

  val active: AccessionActive?
    get() = state?.toActiveEnum()

  fun toV1Compatible(clock: Clock): AccessionModel {
    return if (!isManualState) {
      this
    } else {
      // v1 API doesn't allow count-based accessions to have weight-based withdrawals.
      val withdrawalsWithCorrectUnits =
          withdrawals.map { withdrawal ->
            withdrawal.toV1Compatible(remaining, subsetWeightQuantity, subsetCount)
          }

      val effectiveProcessingMethod =
          when (remaining?.units) {
            SeedQuantityUnits.Seeds -> ProcessingMethod.Count
            null -> null
            else -> ProcessingMethod.Weight
          }

      // For count-based accessions, v1 recomputes the remaining quantity as the total (initial)
      // quantity minus the sum of the withdrawal amounts. Make the numbers line up by overriding
      // the total quantity.
      val newTotal =
          if (remaining?.units == SeedQuantityUnits.Seeds) {
            withdrawalsWithCorrectUnits
                .filter { it.withdrawn != null }
                .fold(remaining) { runningTotal, withdrawal ->
                  runningTotal + withdrawal.withdrawn!!
                }
          } else {
            total
          }

      copy(
              isManualState = false,
              processingMethod = effectiveProcessingMethod,
              total = newTotal,
              viabilityTests = viabilityTests.map { it.toV1Compatible() },
              withdrawals = withdrawalsWithCorrectUnits,
          )
          .withCalculatedValues(clock)
    }
  }

  fun toV2Compatible(clock: Clock): AccessionModel {
    return if (isManualState) {
      this
    } else {
      // The accession might be missing some values that we now calculate on both v1- and v2-
      // style accessions but that weren't calculated at the time it was written to the database.
      // First backfill those values using the v1 logic, then switch to v2, then calculate any
      // v2-specific values.
      val v1WithCalculatedValues = withCalculatedValues(clock)
      v1WithCalculatedValues
          .copy(
              isManualState = true,
              state = state?.toV2Compatible(),
              viabilityTests = v1WithCalculatedValues.viabilityTests.map { it.toV2Compatible() },
              withdrawals = v1WithCalculatedValues.withdrawals.map { it.toV2Compatible() },
          )
          .withCalculatedValues(clock)
    }
  }

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
    if (isManualState) {
      validateV2()
    } else {
      validateV1()
    }
  }

  private fun validateV1() {
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

    assertNoWithdrawalsWithoutQuantity(total)

    viabilityTests.forEach { it.validateV1() }
  }

  private fun validateV2() {
    assertRemainingQuantityNotRemoved()
    assertNoQuantityTypeChangeWithoutSubsetInfo()
    assertNoWithdrawalsWithoutQuantity(latestObservedQuantity ?: remaining)
    viabilityTests.forEach { it.validateV2() }
  }

  private fun assertRemainingQuantityNotRemoved() {
    if (latestObservedQuantity != null && remaining == null) {
      throw IllegalArgumentException("Cannot remove remaining quantity once it has been set")
    }
  }

  private fun assertNoWithdrawalsWithoutQuantity(quantity: SeedQuantityModel?) {
    if (quantity == null) {
      if (viabilityTests.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot create viability tests before setting total accession size")
      }
      if (withdrawals.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot withdraw from accession before setting total accession size")
      }
    }
  }

  private fun assertNoQuantityTypeChangeWithoutSubsetInfo() {
    if (latestObservedQuantity != null &&
        (viabilityTests.isNotEmpty() || withdrawals.isNotEmpty()) &&
        (subsetWeightQuantity == null || subsetCount == null)) {
      if (latestObservedQuantity.units == SeedQuantityUnits.Seeds &&
          remaining?.units != SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Cannot change remaining quantity from seeds to weight without subset weight and count")
      }
      if (latestObservedQuantity.units != SeedQuantityUnits.Seeds &&
          remaining?.units == SeedQuantityUnits.Seeds) {
        throw IllegalArgumentException(
            "Cannot change remaining quantity from weight to seeds without subset weight and count")
      }
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
                viabilityTests.mapNotNull { it.seedsTested?.toBigDecimal() })
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

    if (!hasSeedCount() &&
        (cutTestSeedsCompromised != null ||
            cutTestSeedsEmpty != null ||
            cutTestSeedsFilled != null)) {
      throw IllegalArgumentException("Cannot record cut test results when seed count is unknown")
    }
  }

  private fun getLatestViabilityTestWithResults(): ViabilityTestModel? {
    return viabilityTests
        .filter { it.calculateLatestRecordingDate() != null && it.seedsTested != null }
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

  fun calculateLatestObservedQuantity(
      clock: Clock,
      existing: AccessionModel = this
  ): SeedQuantityModel? {
    return if (latestObservedQuantityCalculated) {
      latestObservedQuantity
    } else if (isManualState) {
      if (existing.remaining != remaining || existing.latestObservedQuantity == null) {
        remaining
      } else {
        existing.latestObservedQuantity
      }
    } else {
      when (processingMethod) {
        ProcessingMethod.Count -> total
        ProcessingMethod.Weight -> calculateRemaining(clock)
        null -> null
      }
    }
  }

  fun calculateLatestObservedTime(clock: Clock, existing: AccessionModel = this): Instant? {
    return if (latestObservedQuantityCalculated) {
      latestObservedTime
    } else if (isManualState) {
      if (remaining != null &&
          (existing.remaining != remaining || existing.latestObservedQuantity == null)) {
        clock.instant()
      } else {
        existing.latestObservedTime
      }
    } else {
      when {
        total == null -> null
        processingMethod == ProcessingMethod.Count -> createdTime ?: clock.instant()
        processingMethod == ProcessingMethod.Weight ->
            withdrawals.mapNotNull { it.createdTime }.maxOrNull() ?: createdTime ?: clock.instant()
        else -> null
      }
    }
  }

  fun calculateLatestViabilityRecordingDate(): LocalDate? {
    return getLatestViabilityTestWithResults()?.calculateLatestRecordingDate()
  }

  fun calculateLatestViabilityPercent(): Int? {
    return getLatestViabilityTestWithResults()?.calculateViabilityPercent()
  }

  fun calculateTotalViabilityPercent(): Int? {
    if (!hasTestResults()) {
      return null
    }

    val tests = viabilityTests

    val totalViabilityTested = tests.mapNotNull { it.seedsTested }.sum()
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

  fun calculateRemaining(clock: Clock, existing: AccessionModel = this): SeedQuantityModel? {
    val newWithdrawals = calculateWithdrawals(clock, existing)
    val newRemaining =
        if (isManualState) {
          if (latestObservedQuantity == null ||
              latestObservedTime == null ||
              remaining != existing.remaining) {
            remaining
          } else {
            newWithdrawals
                .filter { it.isAfter(latestObservedTime) }
                .fold(latestObservedQuantity) { runningRemaining, withdrawal ->
                  val withdrawn = withdrawal.weightDifference ?: withdrawal.withdrawn
                  if (withdrawn != null) {
                    runningRemaining -
                        withdrawn.toUnits(runningRemaining.units, subsetWeightQuantity, subsetCount)
                  } else {
                    runningRemaining
                  }
                }
          }
        } else {
          val mostRecentRemaining = newWithdrawals.lastOrNull()?.remaining ?: total
          mostRecentRemaining?.toUnits(total?.units ?: mostRecentRemaining.units)
        }

    if (newRemaining != null && newRemaining.quantity.signum() < 0) {
      throw IllegalArgumentException("Cannot withdraw more seeds than remain in the accession")
    }

    return newRemaining
  }

  fun calculateEstimatedSeedCount(baseQuantity: SeedQuantityModel?): Int? {
    return baseQuantity
        ?.toUnitsOrNull(SeedQuantityUnits.Seeds, subsetWeightQuantity, subsetCount)
        ?.quantity
        ?.toInt()
  }

  private fun calculateEstimatedWeight(baseQuantity: SeedQuantityModel?): SeedQuantityModel? {
    return when {
      baseQuantity == null -> null
      baseQuantity.units != SeedQuantityUnits.Seeds -> baseQuantity
      subsetCount == null || subsetWeightQuantity == null -> null
      else -> baseQuantity.toUnits(subsetWeightQuantity.units, subsetWeightQuantity, subsetCount)
    }
  }

  fun calculateProcessingStartDate(clock: Clock): LocalDate? {
    return processingStartDate ?: if (hasSeedCount()) LocalDate.now(clock) else null
  }

  /**
   * @return Withdrawals in descending order of seeds remaining; the last item in the list will be
   * the one with the seeds-remaining value for the accession as a whole.
   */
  fun calculateWithdrawals(clock: Clock, existing: AccessionModel = this): List<WithdrawalModel> {
    val viabilityTestsWithV1CutTest: List<ViabilityTestModel> = convertV1CutTestToV2(existing)

    if (withdrawals.isEmpty() && viabilityTestsWithV1CutTest.isEmpty()) {
      return emptyList()
    }

    val existingIds = existing.withdrawals.mapNotNull { it.id }.toSet()
    withdrawals
        .mapNotNull { it.id }
        .forEach { id ->
          if (id !in existingIds) {
            throw IllegalArgumentException("Cannot update withdrawal with nonexistent ID $id")
          }
        }

    val nonTestWithdrawals = withdrawals.filter { it.purpose != WithdrawalPurpose.ViabilityTesting }
    val existingTestWithdrawals =
        existing.withdrawals
            .filter { it.viabilityTestId != null }
            .associateBy { it.viabilityTestId!! }
    val testWithdrawals =
        viabilityTestsWithV1CutTest.map { test ->
          val existingWithdrawal = test.id?.let { existingTestWithdrawals[it] }
          val withdrawn =
              test.seedsTested?.let { SeedQuantityModel(BigDecimal(it), SeedQuantityUnits.Seeds) }

          WithdrawalModel(
              createdTime = existingWithdrawal?.createdTime,
              date = test.startDate ?: existingWithdrawal?.date ?: LocalDate.now(clock),
              id = existingWithdrawal?.id,
              purpose = WithdrawalPurpose.ViabilityTesting,
              remaining = test.remaining,
              staffResponsible = test.staffResponsible,
              viabilityTest = test,
              viabilityTestId = test.id,
              weightDifference = existingWithdrawal?.weightDifference,
              withdrawn = withdrawn,
              withdrawnByUserId = test.withdrawnByUserId ?: existingWithdrawal?.withdrawnByUserId,
          )
        }

    val unsortedWithdrawals = nonTestWithdrawals + testWithdrawals

    val sortedWithdrawals =
        if (isManualState) {
          // V1 COMPATIBILITY: Need to track per-withdrawal remaining quantity.
          var currentRemaining =
              latestObservedQuantity
                  ?: remaining
                      ?: throw IllegalStateException(
                      "Cannot withdraw from accession before specifying a quantity")

          unsortedWithdrawals
              .sortedWith { a, b -> a.compareByTime(b) }
              .map { withdrawal ->
                // V1 COMPATIBILITY: Need to track per-withdrawal remaining quantity.
                if (withdrawal.remaining != null &&
                    withdrawal.remaining.units != SeedQuantityUnits.Seeds &&
                    withdrawal.weightDifference == null &&
                    withdrawal.remaining != currentRemaining) {
                  val weightDifference = currentRemaining - withdrawal.remaining
                  currentRemaining = withdrawal.remaining
                  withdrawal.copy(weightDifference = weightDifference)
                } else if (withdrawal.remaining == null && withdrawal.withdrawn != null) {
                  if (latestObservedTime != null && withdrawal.isAfter(latestObservedTime)) {
                    currentRemaining -=
                        withdrawal.withdrawn.toUnits(
                            currentRemaining.units, subsetWeightQuantity, subsetCount)
                  }
                  withdrawal.copy(
                      remaining = currentRemaining,
                      viabilityTest = withdrawal.viabilityTest?.copy(remaining = currentRemaining))
                } else {
                  if (withdrawal.remaining != null) {
                    currentRemaining = withdrawal.remaining
                  }
                  withdrawal
                }
              }
        } else {
          var currentRemaining =
              total
                  ?: throw IllegalStateException(
                      "Cannot withdraw from accession before specifying its total size")

          when (processingMethod) {
            ProcessingMethod.Count -> {
              unsortedWithdrawals
                  .sortedWith { a, b -> a.compareByTime(b) }
                  .map { withdrawal ->
                    withdrawal.withdrawn?.let { withdrawn -> currentRemaining -= withdrawn }
                    withdrawal.copy(
                        remaining = currentRemaining,
                        viabilityTest =
                            withdrawal.viabilityTest?.copy(remaining = currentRemaining))
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

    // V1 COMPATIBILITY: Remaining quantity+units are non-null in the database, so sanity check
    // that we populated them.
    if (sortedWithdrawals.any { it.remaining == null }) {
      throw IllegalStateException("BUG! Failed to generate remaining quantity for withdrawal.")
    }

    return sortedWithdrawals.map { withdrawal ->
      withdrawal.copy(
          estimatedCount = withdrawal.calculateEstimatedCount(subsetWeightQuantity, subsetCount),
          estimatedWeight = withdrawal.weightDifference
                  ?: withdrawal.calculateEstimatedWeight(
                      subsetWeightQuantity, subsetCount, remaining?.units),
      )
    }
  }

  /**
   * V1 COMPATIBILITY: Convert v1-style accession-level cut test fields to an entry in the list of
   * viability tests. If the user has entered cut test results for the first time, they will need to
   * be represented as a viability test. If they've updated existing cut test results, the new
   * results will need to be copied to the existing viability test.
   */
  private fun convertV1CutTestToV2(existing: AccessionModel): List<ViabilityTestModel> {
    val accessionFieldsNotEdited =
        cutTestSeedsCompromised == existing.cutTestSeedsCompromised &&
            cutTestSeedsEmpty == existing.cutTestSeedsEmpty &&
            cutTestSeedsFilled == existing.cutTestSeedsFilled
    val accessionFieldsNotSet =
        cutTestSeedsCompromised == null && cutTestSeedsEmpty == null && cutTestSeedsFilled == null
    val existingCutTest =
        existing.viabilityTests.firstOrNull { it.testType == ViabilityTestType.Cut }

    return when {
      isManualState -> {
        viabilityTests
      }
      accessionFieldsNotEdited -> {
        // User hasn't changed the cut test results, but they won't be included in viabilityTests
        // on a v1 PUT request, so need to pull them back in.
        if (existingCutTest != null) {
          viabilityTests.filter { it.testType != ViabilityTestType.Cut } + existingCutTest
        } else {
          viabilityTests
        }
      }
      accessionFieldsNotSet -> {
        // User has removed previously-existing cut test results.
        viabilityTests.filter { it.testType != ViabilityTestType.Cut }
      }
      existingCutTest == null -> {
        // User has entered cut test results for the first time.
        viabilityTests +
            ViabilityTestModel(
                remaining = remaining ?: total,
                seedsCompromised = cutTestSeedsCompromised,
                seedsEmpty = cutTestSeedsEmpty,
                seedsFilled = cutTestSeedsFilled,
                seedsTested = (cutTestSeedsCompromised
                        ?: 0) + (cutTestSeedsEmpty ?: 0) + (cutTestSeedsFilled ?: 0),
                testType = ViabilityTestType.Cut,
            )
      }
      else -> {
        // User has updated existing cut test results.
        viabilityTests.filter { it.testType != ViabilityTestType.Cut } +
            existingCutTest.copy(
                seedsCompromised = cutTestSeedsCompromised,
                seedsEmpty = cutTestSeedsEmpty,
                seedsFilled = cutTestSeedsFilled,
                seedsTested = (cutTestSeedsCompromised
                        ?: 0) + (cutTestSeedsEmpty ?: 0) + (cutTestSeedsFilled ?: 0),
            )
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
    val newRemaining = calculateRemaining(clock, existing)
    val newWithdrawals = calculateWithdrawals(clock, existing)
    val newViabilityPercent =
        if (isManualState) totalViabilityPercent else calculateTotalViabilityPercent()
    val newViabilityTests = newWithdrawals.mapNotNull { it.viabilityTest }
    val newState = existing.getStateTransition(this, clock)?.newState ?: existing.state
    val newEstimatedBaseQuantity = if (isManualState) newRemaining else total

    // V1 COMPATIBILITY: Total up the results of cut tests to populate the accession-level cut test
    // fields, and reflect changes to the accession-level fields in the viability test list.
    val cutTests = newViabilityTests.filter { it.testType == ViabilityTestType.Cut }

    return copy(
        collectedDate = newCollectedDate,
        cutTestSeedsCompromised = cutTests.mapNotNull { it.seedsCompromised }.orNull()?.sum(),
        cutTestSeedsEmpty = cutTests.mapNotNull { it.seedsEmpty }.orNull()?.sum(),
        cutTestSeedsFilled = cutTests.mapNotNull { it.seedsFilled }.orNull()?.sum(),
        estimatedSeedCount = calculateEstimatedSeedCount(newEstimatedBaseQuantity),
        estimatedWeight = calculateEstimatedWeight(newEstimatedBaseQuantity),
        latestObservedQuantity = calculateLatestObservedQuantity(clock, existing),
        latestObservedTime = calculateLatestObservedTime(clock, existing),
        latestViabilityPercent = calculateLatestViabilityPercent(),
        latestViabilityTestDate = calculateLatestViabilityRecordingDate(),
        latestObservedQuantityCalculated = true,
        processingStartDate = newProcessingStartDate,
        receivedDate = newReceivedDate,
        remaining = newRemaining,
        state = newState,
        totalViabilityPercent = newViabilityPercent,
        total = total ?: newRemaining,
        viabilityTests = newViabilityTests,
        withdrawals = newWithdrawals)
  }

  fun addWithdrawal(withdrawal: WithdrawalModel, clock: Clock): AccessionModel {
    return copy(withdrawals = withdrawals + withdrawal).withCalculatedValues(clock, this)
  }

  fun updateWithdrawal(
      withdrawalId: WithdrawalId,
      clock: Clock,
      edit: (WithdrawalModel) -> WithdrawalModel
  ): AccessionModel {
    if (withdrawals.none { it.id == withdrawalId }) {
      throw WithdrawalNotFoundException(withdrawalId)
    }

    val newWithdrawals = withdrawals.map { if (it.id == withdrawalId) edit(it) else it }

    return copy(withdrawals = newWithdrawals).withCalculatedValues(clock, this)
  }

  fun deleteWithdrawal(withdrawalId: WithdrawalId, clock: Clock): AccessionModel {
    val newWithdrawals = withdrawals.filterNot { it.id == withdrawalId }
    if (newWithdrawals.size == withdrawals.size) {
      throw WithdrawalNotFoundException(withdrawalId)
    }

    return copy(withdrawals = newWithdrawals).withCalculatedValues(clock, this)
  }

  fun addViabilityTest(viabilityTest: ViabilityTestModel, clock: Clock): AccessionModel {
    return copy(viabilityTests = viabilityTests + viabilityTest).withCalculatedValues(clock, this)
  }

  fun updateViabilityTest(
      viabilityTestId: ViabilityTestId,
      clock: Clock,
      edit: (ViabilityTestModel) -> ViabilityTestModel
  ): AccessionModel {
    if (viabilityTests.none { it.id == viabilityTestId }) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    val newViabilityTests = viabilityTests.map { if (it.id == viabilityTestId) edit(it) else it }

    return copy(viabilityTests = newViabilityTests).withCalculatedValues(clock, this)
  }

  fun deleteViabilityTest(viabilityTestId: ViabilityTestId, clock: Clock): AccessionModel {
    val newViabilityTests = viabilityTests.filterNot { it.id == viabilityTestId }
    val newWithdrawals = withdrawals.filterNot { it.viabilityTestId == viabilityTestId }

    if (newViabilityTests.size == viabilityTests.size) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    return copy(viabilityTests = newViabilityTests, withdrawals = newWithdrawals)
        .withCalculatedValues(clock, this)
  }
}

data class AccessionStateTransition(
    val newState: AccessionState,
    val reason: String,
)
