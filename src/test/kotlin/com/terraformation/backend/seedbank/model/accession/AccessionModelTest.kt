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

internal abstract class AccessionModelTest {
  protected val today = january(2)
  protected val todayInstant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
  protected val clock: Clock = Clock.fixed(todayInstant, ZoneOffset.UTC)
  protected val tomorrow = today.plusDays(1)
  protected val tomorrowInstant = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant()
  protected val tomorrowClock = Clock.fixed(tomorrowInstant, ZoneOffset.UTC)
  protected val yesterday = today.minusDays(1)
  protected val yesterdayInstant = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant()
  protected val yesterdayClock = Clock.fixed(yesterdayInstant, ZoneOffset.UTC)

  protected var viabilityTestResultId = ViabilityTestResultId(1)
  protected var viabilityTestId = ViabilityTestId(1)
  protected var withdrawalId = WithdrawalId(1)
  protected var defaultState = AccessionState.Processing

  protected fun accession(
      viabilityTests: List<ViabilityTestModel> = emptyList(),
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

  protected fun nextViabilityTestId(): ViabilityTestId {
    val current = viabilityTestId
    viabilityTestId = ViabilityTestId(current.value + 1)
    return current
  }

  protected fun viabilityTest(
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

  protected fun nextViabilityTestResultId(): ViabilityTestResultId {
    val current = viabilityTestResultId
    viabilityTestResultId = ViabilityTestResultId(current.value + 1)
    return current
  }

  protected fun viabilityTestResult(
      recordingDate: LocalDate = january(2),
      seedsGerminated: Int = 1
  ): ViabilityTestResultModel {
    return ViabilityTestResultModel(
        id = nextViabilityTestResultId(),
        recordingDate = recordingDate,
        seedsGerminated = seedsGerminated,
        testId = viabilityTestId)
  }

  protected fun nextWithdrawalId(): WithdrawalId {
    val current = withdrawalId
    withdrawalId = WithdrawalId(current.value + 1)
    return current
  }

  protected fun withdrawal(
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

  protected fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }
}
