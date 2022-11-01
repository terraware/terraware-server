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
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import java.time.ZoneOffset

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
        AccessionState.AwaitingProcessing,
        AccessionState.UsedUp -> false
      }

fun AccessionState.toV1Compatible(): AccessionState =
    when (this) {
      AccessionState.AwaitingCheckIn,
      AccessionState.Pending,
      AccessionState.Processing,
      AccessionState.Processed,
      AccessionState.Drying,
      AccessionState.Dried,
      AccessionState.InStorage,
      AccessionState.Withdrawn,
      AccessionState.Nursery -> this
      AccessionState.AwaitingProcessing -> AccessionState.Pending
      AccessionState.UsedUp -> AccessionState.Withdrawn
    }

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
    val dryingEndDate: LocalDate? = null,
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
    val numberOfTrees: Int? = null,
    val photoFilenames: List<String> = emptyList(),
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
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

  fun getStateTransition(newModel: AccessionModel, clock: Clock): AccessionStateTransition? {
    if (newModel.isManualState) {
      return getManualStateTransition(newModel, clock)
    }

    val seedsRemaining = newModel.calculateRemaining(clock)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO

    fun Instant?.hasArrived() = this != null && this <= clock.instant()

    val markedAsWithdrawn =
        newModel.state == AccessionState.Withdrawn || newModel.state == AccessionState.Nursery
    val checkedIn = newModel.checkedInTime.hasArrived()

    val desiredState: Pair<AccessionState, String> =
        when {
          allSeedsWithdrawn && newModel.state == AccessionState.Nursery ->
              AccessionState.Nursery to "Marked as withdrawn to nursery"
          allSeedsWithdrawn -> AccessionState.Withdrawn to "All seeds marked as withdrawn"
          markedAsWithdrawn -> AccessionState.InStorage to "Accession still has seeds"
          checkedIn &&
              (newModel.state == AccessionState.AwaitingCheckIn ||
                  newModel.state == AccessionState.Pending) ->
              AccessionState.Pending to "Accession has been checked in"
          !checkedIn -> AccessionState.AwaitingCheckIn to "Accession has not been checked in"
          newModel.state == null -> AccessionState.InStorage to "No state has been specified"
          !newModel.state.isV1Compatible ->
              newModel.state.toV1Compatible() to "Reverting to backward-compatible state"
          else -> newModel.state to "No change to existing state"
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
    val seedsRemaining = newModel.calculateRemaining(clock, this)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO
    val oldState = state ?: AccessionState.AwaitingProcessing
    val newState = newModel.state ?: AccessionState.AwaitingProcessing
    val alreadyCheckedIn = oldState != AccessionState.AwaitingCheckIn
    val checkingIn =
        oldState == AccessionState.AwaitingCheckIn && newState == AccessionState.AwaitingProcessing
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
          checkingIn -> newState to "Accession has been checked in"
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
    }
  }

  private fun validateV2() {
    assertRemainingQuantityNotNegative()
    assertRemainingQuantityNotRemoved()
    assertNoQuantityTypeChangeWithoutSubsetInfo()
    assertNoWithdrawalsWithoutQuantity(latestObservedQuantity ?: remaining)
    viabilityTests.forEach { it.validate() }
  }

  private fun assertRemainingQuantityNotNegative() {
    if (remaining != null && remaining.quantity.signum() == -1) {
      throw IllegalArgumentException("Remaining quantity may not be negative")
    }
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
        processingMethod == ProcessingMethod.Weight -> {
          val latestWithdrawalObservationTime =
              withdrawals.maxOfOrNull { withdrawal ->
                // If there are scheduled withdrawals, we want to use their dates as the observation
                // times, with ties broken using the time of day of the creation time.
                val createdTime = withdrawal.createdTime ?: clock.instant()
                val createdTimeOfDay = OffsetTime.ofInstant(createdTime, ZoneOffset.UTC)
                val dateWithCreatedTimeOfDay = withdrawal.date.atTime(createdTimeOfDay).toInstant()

                maxOf(dateWithCreatedTimeOfDay, createdTime)
              }
          latestWithdrawalObservationTime ?: createdTime ?: clock.instant()
        }
        else -> null
      }
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

  /**
   * @return Withdrawals in descending order of seeds remaining; the last item in the list will be
   * the one with the seeds-remaining value for the accession as a whole.
   */
  fun calculateWithdrawals(clock: Clock, existing: AccessionModel = this): List<WithdrawalModel> {
    if (withdrawals.isEmpty() && viabilityTests.isEmpty()) {
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
        viabilityTests.map { test ->
          val existingWithdrawal = test.id?.let { existingTestWithdrawals[it] }
          val withdrawn =
              test.seedsTested?.let { SeedQuantityModel(BigDecimal(it), SeedQuantityUnits.Seeds) }
          val weightDifference =
              if (withdrawn == existingWithdrawal?.withdrawn) {
                existingWithdrawal?.weightDifference
              } else {
                null
              }

          WithdrawalModel(
              createdTime = existingWithdrawal?.createdTime,
              date = test.startDate ?: existingWithdrawal?.date ?: LocalDate.now(clock),
              id = existingWithdrawal?.id,
              purpose = WithdrawalPurpose.ViabilityTesting,
              remaining = null,
              staffResponsible = test.staffResponsible,
              viabilityTest = test,
              viabilityTestId = test.id,
              weightDifference = weightDifference,
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

                  if (currentRemaining.quantity.signum() < 0) {
                    throw IllegalArgumentException(
                        "Withdrawal quantity can't be more than remaining quantity")
                  }

                  withdrawal.copy(remaining = currentRemaining)
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
                    withdrawal.copy(remaining = currentRemaining)
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

  fun withCalculatedValues(clock: Clock, existing: AccessionModel = this): AccessionModel {
    val newRemaining = calculateRemaining(clock, existing)
    val newWithdrawals = calculateWithdrawals(clock, existing)
    val newViabilityTests = newWithdrawals.mapNotNull { it.viabilityTest }
    val newState = existing.getStateTransition(this, clock)?.newState ?: existing.state
    val newEstimatedBaseQuantity = if (isManualState) newRemaining else total

    return copy(
        estimatedSeedCount = calculateEstimatedSeedCount(newEstimatedBaseQuantity),
        estimatedWeight = calculateEstimatedWeight(newEstimatedBaseQuantity),
        latestObservedQuantity = calculateLatestObservedQuantity(clock, existing),
        latestObservedTime = calculateLatestObservedTime(clock, existing),
        latestObservedQuantityCalculated = true,
        remaining = newRemaining,
        state = newState,
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
