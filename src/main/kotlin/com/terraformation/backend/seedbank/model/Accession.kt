package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
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
  Active,
}

fun AccessionState.toActiveEnum() = if (active) AccessionActive.Active else AccessionActive.Inactive

/**
 * All the accession states that are considered active. This is effectively the backing field for
 * [AccessionState.Companion.activeValues], because extension properties don't have backing fields.
 */
private val activeStates = AccessionState.entries.filter { it.active }.toSet()

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

data class AccessionModel(
    val id: AccessionId? = null,
    val accessionNumber: String? = null,
    val bagNumbers: Set<String> = emptySet(),
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
    val founderId: String? = null,
    val geolocations: Set<Geolocation> = emptySet(),
    /** If true, seedlings from this accession have been delivered to planting sites. */
    val hasDeliveries: Boolean = false,
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
    val processingNotes: String? = null,
    val projectId: ProjectId? = null,
    val receivedDate: LocalDate? = null,
    val remaining: SeedQuantityModel? = null,
    val source: DataSource? = null,
    val species: String? = null,
    val speciesCommonName: String? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionState = AccessionState.AwaitingCheckIn,
    val subLocation: String? = null,
    val subsetCount: Int? = null,
    val subsetWeightQuantity: SeedQuantityModel? = null,
    val totalViabilityPercent: Int? = null,
    val totalWithdrawnCount: Int? = null,
    val totalWithdrawnWeight: SeedQuantityModel? = null,
    val viabilityTests: List<ViabilityTestModel> = emptyList(),
    val withdrawals: List<WithdrawalModel> = emptyList(),
    /** Clock to use for time-aware calculations. This must be in the seed bank's time zone. */
    private val clock: Clock,
) {
  init {
    validate()
  }

  val active: AccessionActive
    get() = state.toActiveEnum()

  fun getStateTransition(newModel: AccessionModel): AccessionStateTransition? {
    val seedsRemaining = newModel.calculateRemaining(this)
    val allSeedsWithdrawn = seedsRemaining != null && seedsRemaining.quantity <= BigDecimal.ZERO
    val oldState = state
    val newState = newModel.state
    val alreadyCheckedIn = oldState != AccessionState.AwaitingCheckIn
    val checkingIn =
        oldState == AccessionState.AwaitingCheckIn && newState == AccessionState.AwaitingProcessing
    val revertingToAwaitingCheckIn = alreadyCheckedIn && newState == AccessionState.AwaitingCheckIn
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
            "Cannot create viability tests before setting total accession size"
        )
      }
      if (withdrawals.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot withdraw from accession before setting total accession size"
        )
      }
    }
  }

  private fun assertNoQuantityTypeChangeWithoutSubsetInfo() {
    if (
        latestObservedQuantity != null &&
            (viabilityTests.isNotEmpty() || withdrawals.isNotEmpty()) &&
            (subsetWeightQuantity == null || subsetCount == null)
    ) {
      if (
          latestObservedQuantity.units == SeedQuantityUnits.Seeds &&
              remaining?.units != SeedQuantityUnits.Seeds
      ) {
        throw IllegalArgumentException(
            "Cannot change remaining quantity from seeds to weight without subset weight and count"
        )
      }
      if (
          latestObservedQuantity.units != SeedQuantityUnits.Seeds &&
              remaining?.units == SeedQuantityUnits.Seeds
      ) {
        throw IllegalArgumentException(
            "Cannot change remaining quantity from weight to seeds without subset weight and count"
        )
      }
    }
  }

  fun calculateLatestObservedQuantity(existing: AccessionModel = this): SeedQuantityModel? {
    return if (latestObservedQuantityCalculated) {
      latestObservedQuantity
    } else {
      if (existing.remaining != remaining || existing.latestObservedQuantity == null) {
        remaining
      } else {
        existing.latestObservedQuantity
      }
    }
  }

  fun calculateLatestObservedTime(existing: AccessionModel = this): Instant? {
    return if (latestObservedQuantityCalculated) {
      latestObservedTime
    } else {
      if (
          remaining != null &&
              (existing.remaining != remaining || existing.latestObservedQuantity == null)
      ) {
        clock.instant()
      } else {
        existing.latestObservedTime
      }
    }
  }

  fun calculateRemaining(existing: AccessionModel = this): SeedQuantityModel? {
    val newRemaining =
        if (
            latestObservedQuantity == null ||
                latestObservedTime == null ||
                remaining != existing.remaining
        ) {
          remaining
        } else {
          val newWithdrawals =
              calculateWithdrawals(existing).filter { it.isAfter(latestObservedTime, clock.zone) }
          val mostRecentWithdrawal = newWithdrawals.lastOrNull()

          // If the user withdrew all the seeds from a weight-based accession by entering the
          // estimated seed count (which will be the case in, e.g., a nursery transfer which is
          // always measured in seeds), we can't rely on the seeds-to-weight calculation precisely
          // matching the remaining weight quantity because weights have limited precision. Force
          // the remaining quantity to zero in that case.
          if (
              latestObservedQuantity.units != SeedQuantityUnits.Seeds &&
                  mostRecentWithdrawal?.withdrawn?.units == SeedQuantityUnits.Seeds &&
                  mostRecentWithdrawal.withdrawn.quantity.toInt() == existing.estimatedSeedCount
          ) {
            SeedQuantityModel.of(BigDecimal.ZERO, latestObservedQuantity.units)
          } else {
            newWithdrawals.fold(latestObservedQuantity) { runningRemaining, withdrawal ->
              val withdrawn = withdrawal.withdrawn
              if (withdrawn != null) {
                runningRemaining -
                    withdrawn.toUnits(runningRemaining.units, subsetWeightQuantity, subsetCount)
              } else {
                runningRemaining
              }
            }
          }
        }

    if (newRemaining != null && newRemaining.quantity.signum() < 0) {
      throw IllegalArgumentException("Withdrawal quantity can't be more than remaining quantity")
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

  fun calculateWithdrawals(existing: AccessionModel = this): List<WithdrawalModel> {
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

          WithdrawalModel(
              createdTime = existingWithdrawal?.createdTime,
              date = test.startDate ?: existingWithdrawal?.date ?: LocalDate.now(clock),
              id = existingWithdrawal?.id,
              purpose = WithdrawalPurpose.ViabilityTesting,
              staffResponsible = test.staffResponsible,
              viabilityTest = test,
              viabilityTestId = test.id,
              withdrawn = withdrawn,
              withdrawnByUserId = test.withdrawnByUserId ?: existingWithdrawal?.withdrawnByUserId,
          )
        }

    val unsortedWithdrawals = nonTestWithdrawals + testWithdrawals

    val sortedWithdrawals = unsortedWithdrawals.sortedWith { a, b -> a.compareByTime(b) }

    return sortedWithdrawals.map { withdrawal ->
      withdrawal.copy(
          estimatedCount = withdrawal.calculateEstimatedCount(subsetWeightQuantity, subsetCount),
          estimatedWeight =
              withdrawal.calculateEstimatedWeight(
                  subsetWeightQuantity,
                  subsetCount,
                  remaining?.units,
              ),
      )
    }
  }

  private fun calculateTotalWithdrawnWeight(
      newWithdrawals: List<WithdrawalModel>
  ): SeedQuantityModel? {
    return newWithdrawals.mapNotNull { it.estimatedWeight }.reduceOrNull { a, b -> a + b }
  }

  private fun calculateTotalWithdrawnCount(newWithdrawals: List<WithdrawalModel>): Int? {
    return newWithdrawals.mapNotNull { it.estimatedCount }.reduceOrNull { a, b -> a + b }
  }

  fun withCalculatedValues(existing: AccessionModel = this): AccessionModel {
    val newRemaining = calculateRemaining(existing)
    val newWithdrawals = calculateWithdrawals(existing)
    val newViabilityTests = newWithdrawals.mapNotNull { it.viabilityTest }
    val newState = existing.getStateTransition(this)?.newState ?: existing.state

    return copy(
        estimatedSeedCount = calculateEstimatedSeedCount(newRemaining),
        estimatedWeight = calculateEstimatedWeight(newRemaining),
        latestObservedQuantity = calculateLatestObservedQuantity(existing),
        latestObservedTime = calculateLatestObservedTime(existing),
        latestObservedQuantityCalculated = true,
        remaining = newRemaining,
        state = newState,
        totalWithdrawnCount = calculateTotalWithdrawnCount(newWithdrawals),
        totalWithdrawnWeight = calculateTotalWithdrawnWeight(newWithdrawals),
        viabilityTests = newViabilityTests,
        withdrawals = newWithdrawals,
    )
  }

  fun addWithdrawal(withdrawal: WithdrawalModel): AccessionModel {
    return copy(withdrawals = withdrawals + withdrawal).withCalculatedValues(this)
  }

  fun updateWithdrawal(
      withdrawalId: WithdrawalId,
      edit: (WithdrawalModel) -> WithdrawalModel,
  ): AccessionModel {
    if (withdrawals.none { it.id == withdrawalId }) {
      throw WithdrawalNotFoundException(withdrawalId)
    }

    val newWithdrawals = withdrawals.map { if (it.id == withdrawalId) edit(it) else it }

    return copy(withdrawals = newWithdrawals).withCalculatedValues(this)
  }

  fun deleteWithdrawal(withdrawalId: WithdrawalId): AccessionModel {
    val newWithdrawals = withdrawals.filterNot { it.id == withdrawalId }
    if (newWithdrawals.size == withdrawals.size) {
      throw WithdrawalNotFoundException(withdrawalId)
    }

    return copy(withdrawals = newWithdrawals).withCalculatedValues(this)
  }

  fun addViabilityTest(viabilityTest: ViabilityTestModel): AccessionModel {
    return copy(viabilityTests = viabilityTests + viabilityTest).withCalculatedValues(this)
  }

  fun updateViabilityTest(
      viabilityTestId: ViabilityTestId,
      edit: (ViabilityTestModel) -> ViabilityTestModel,
  ): AccessionModel {
    if (viabilityTests.none { it.id == viabilityTestId }) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    val newViabilityTests = viabilityTests.map { if (it.id == viabilityTestId) edit(it) else it }

    return copy(viabilityTests = newViabilityTests).withCalculatedValues(this)
  }

  fun deleteViabilityTest(viabilityTestId: ViabilityTestId): AccessionModel {
    val newViabilityTests = viabilityTests.filterNot { it.id == viabilityTestId }
    val newWithdrawals = withdrawals.filterNot { it.viabilityTestId == viabilityTestId }

    if (newViabilityTests.size == viabilityTests.size) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    return copy(viabilityTests = newViabilityTests, withdrawals = newWithdrawals)
        .withCalculatedValues(this)
  }
}

data class AccessionStateTransition(
    val newState: AccessionState,
    val reason: String,
)

data class AccessionUpdateContext(
    val remainingQuantityNotes: String?,
)
