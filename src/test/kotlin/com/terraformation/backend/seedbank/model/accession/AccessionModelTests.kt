package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestResultId
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

abstract class AccessionModelTests {
  val today = january(2)
  val todayInstant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
  val clock: Clock = Clock.fixed(todayInstant, ZoneOffset.UTC)
  val tomorrow = today.plusDays(1)
  val tomorrowInstant = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant()
  val tomorrowClock = Clock.fixed(tomorrowInstant, ZoneOffset.UTC)
  val yesterday = today.minusDays(1)
  val yesterdayInstant = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant()
  val yesterdayClock = Clock.fixed(yesterdayInstant, ZoneOffset.UTC)

  var viabilityTestResultId = ViabilityTestResultId(1)
  var viabilityTestId = ViabilityTestId(1)
  var withdrawalId = WithdrawalId(1)
  var defaultState = AccessionState.Processing

  fun accession(
      viabilityTests: List<ViabilityTestModel> = emptyList(),
      cutTestSeedsCompromised: Int? = null,
      cutTestSeedsEmpty: Int? = null,
      cutTestSeedsFilled: Int? = null,
      dryingEndDate: LocalDate? = null,
      dryingStartDate: LocalDate? = null,
      processingStartDate: LocalDate? = null,
      subsetCount: Int? = null,
      state: AccessionState = defaultState,
      storageLocation: String? = null,
      storagePackets: Int? = null,
      storageStartDate: LocalDate? = null,
      subsetWeight: SeedQuantityModel? = null,
      total: SeedQuantityModel? = null,
      withdrawals: List<WithdrawalModel> = emptyList(),
      processingMethod: ProcessingMethod? =
          total?.let { if (it.grams != null) ProcessingMethod.Weight else ProcessingMethod.Count },
  ): AccessionModel {
    return AccessionModel(
        id = AccessionId(1L),
        accessionNumber = "dummy",
        createdTime = clock.instant(),
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        dryingEndDate = dryingEndDate,
        dryingStartDate = dryingStartDate,
        processingMethod = processingMethod,
        processingStartDate = processingStartDate,
        source = DataSource.Web,
        state = state,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeightQuantity = subsetWeight,
        total = total,
        viabilityTests = viabilityTests,
        withdrawals = withdrawals,
    )
  }

  fun nextViabilityTestId(): ViabilityTestId {
    val current = viabilityTestId
    viabilityTestId = ViabilityTestId(current.value + 1)
    return current
  }

  fun viabilityTest(
      testType: ViabilityTestType = ViabilityTestType.Lab,
      startDate: LocalDate? = january(1),
      seedsTested: Int? = null,
      testResults: List<ViabilityTestResultModel>? = null,
      remaining: SeedQuantityModel? = null,
      substrate: ViabilityTestSubstrate? = null,
      withdrawnByUserId: UserId? = null,
      seedsCompromised: Int? = null,
      seedsEmpty: Int? = null,
      seedsFilled: Int? = null,
  ): ViabilityTestModel {
    return ViabilityTestModel(
        accessionId = AccessionId(1),
        id = nextViabilityTestId(),
        remaining = remaining,
        seedsCompromised = seedsCompromised,
        seedsEmpty = seedsEmpty,
        seedsFilled = seedsFilled,
        seedsTested = seedsTested,
        startDate = startDate,
        substrate = substrate,
        testResults = testResults,
        testType = testType,
        withdrawnByUserId = withdrawnByUserId,
    )
  }

  fun nextViabilityTestResultId(): ViabilityTestResultId {
    val current = viabilityTestResultId
    viabilityTestResultId = ViabilityTestResultId(current.value + 1)
    return current
  }

  fun viabilityTestResult(
      recordingDate: LocalDate = january(2),
      seedsGerminated: Int = 1
  ): ViabilityTestResultModel {
    return ViabilityTestResultModel(
        id = nextViabilityTestResultId(),
        recordingDate = recordingDate,
        seedsGerminated = seedsGerminated,
        testId = viabilityTestId)
  }

  fun nextWithdrawalId(): WithdrawalId {
    val current = withdrawalId
    withdrawalId = WithdrawalId(current.value + 1)
    return current
  }

  fun withdrawal(
      withdrawn: SeedQuantityModel = seeds(1),
      date: LocalDate = january(3),
      viabilityTestId: ViabilityTestId? = null,
      purpose: WithdrawalPurpose =
          if (viabilityTestId != null) WithdrawalPurpose.ViabilityTesting
          else WithdrawalPurpose.Other,
      remaining: SeedQuantityModel =
          if (withdrawn.units == SeedQuantityUnits.Seeds) seeds(10) else grams(10),
      createdTime: Instant = clock.instant(),
      id: WithdrawalId? = nextWithdrawalId(),
      withdrawnByUserId: UserId? = null,
      estimatedCount: Int? =
          if (withdrawn.units == SeedQuantityUnits.Seeds) withdrawn.quantity.toInt() else null,
  ): WithdrawalModel {
    return WithdrawalModel(
        createdTime = createdTime,
        date = date,
        estimatedCount = estimatedCount,
        id = id,
        purpose = purpose,
        remaining = remaining,
        viabilityTestId = viabilityTestId,
        withdrawn = withdrawn,
        withdrawnByUserId = withdrawnByUserId,
    )
  }

  fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }
}
