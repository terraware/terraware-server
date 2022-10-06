package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestTreatment
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.seedbank.api.ViabilityTestPayload
import com.terraformation.backend.seedbank.api.ViabilityTestResultPayload
import com.terraformation.backend.seedbank.api.ViabilityTestTypeV1
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreViabilityTest : AccessionStoreTest() {
  @Test
  fun `viability tests are inserted by update`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val startDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val withTest =
        initial
            .toUpdatePayload()
            .copy(
                viabilityTests =
                    listOf(
                        ViabilityTestPayload(
                            testType = ViabilityTestTypeV1.Lab, startDate = startDate)),
                processingMethod = ProcessingMethod.Count,
                initialQuantity = seeds(100))
    store.update(withTest.toModel(id = initial.id!!))

    val updatedTests = viabilityTestsDao.fetchByAccessionId(AccessionId(1))
    Assertions.assertEquals(
        listOf(
            ViabilityTestsRow(
                accessionId = AccessionId(1),
                id = ViabilityTestId(1),
                remainingQuantity = BigDecimal(100),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                startDate = startDate,
                testType = ViabilityTestType.Lab,
            )),
        updatedTests)

    val updatedRow = accessionsDao.fetchOneById(AccessionId(1))
    Assertions.assertNull(updatedRow?.totalViabilityPercent, "totalViabilityPercent")
    Assertions.assertNull(updatedRow?.latestViabilityPercent, "latestViabilityPercent")
    Assertions.assertNull(
        updatedRow?.latestGerminationRecordingDate, "latestGerminationRecordingDate")

    val updatedAccession = store.fetchOneById(AccessionId(1))
    Assertions.assertNull(
        updatedAccession.viabilityTests.first().testResults,
        "Empty list of viability test results should be null in model")
  }

  @Test
  fun `existing viability tests are updated`() {
    val initial = createAndUpdate {
      it.copy(
          viabilityTests = listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }

    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        id = initial.viabilityTests[0].id,
                        notes = "notes",
                        seedsTested = 5,
                        seedType = ViabilityTestSeedType.Fresh,
                        substrate = ViabilityTestSubstrate.Paper,
                        testType = ViabilityTestType.Lab,
                        treatment = ViabilityTestTreatment.Scarify)))
    store.update(desired)

    val updatedTests = viabilityTestsDao.fetchByAccessionId(AccessionId(1))
    Assertions.assertEquals(
        listOf(
            ViabilityTestsRow(
                id = ViabilityTestId(1),
                accessionId = AccessionId(1),
                testType = ViabilityTestType.Lab,
                seedTypeId = ViabilityTestSeedType.Fresh,
                treatmentId = ViabilityTestTreatment.Scarify,
                substrateId = ViabilityTestSubstrate.Paper,
                notes = "notes",
                seedsSown = 5,
                remainingQuantity = BigDecimal(95),
                remainingUnitsId = SeedQuantityUnits.Seeds)),
        updatedTests)
  }

  @Test
  fun `change to viability test weight remaining is propagated to withdrawal and accession`() {
    val initial = createAndUpdate {
      it.copy(
          viabilityTests =
              listOf(
                  ViabilityTestPayload(
                      testType = ViabilityTestTypeV1.Lab, remainingQuantity = grams(75))),
          initialQuantity = grams(100),
          processingMethod = ProcessingMethod.Weight,
      )
    }

    Assertions.assertEquals(
        grams<SeedQuantityModel>(75),
        initial.remaining,
        "Accession remaining quantity before update")
    Assertions.assertEquals(
        grams<SeedQuantityModel>(75),
        initial.withdrawals[0].remaining,
        "Withdrawal quantities remaining before update")
    Assertions.assertEquals(
        grams<SeedQuantityModel>(75),
        initial.viabilityTests[0].remaining,
        "Test remaining quantity before update")

    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    initial.viabilityTests[0].copy(remaining = grams(60)),
                ),
        )
    val updated = store.updateAndFetch(desired)

    Assertions.assertEquals(
        grams<SeedQuantityModel>(60),
        updated.remaining,
        "Accession remaining quantity after update")
    Assertions.assertEquals(
        grams<SeedQuantityModel>(60),
        updated.withdrawals[0].remaining,
        "Withdrawal quantities remaining after update")
    Assertions.assertEquals(
        grams<SeedQuantityModel>(60),
        updated.viabilityTests[0].remaining,
        "Test remaining quantity after update")
  }

  @Test
  fun `cannot update viability test from a different accession`() {
    val other = createAndUpdate {
      it.copy(
          viabilityTests = listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Nursery)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }
    val initial = createAndUpdate {
      it.copy(
          viabilityTests = listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }
    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        id = other.viabilityTests[0].id,
                        notes = "notes",
                        seedsTested = 5,
                        seedType = ViabilityTestSeedType.Fresh,
                        substrate = ViabilityTestSubstrate.Paper,
                        testType = ViabilityTestType.Lab,
                        treatment = ViabilityTestTreatment.Scarify)))

    assertThrows<IllegalArgumentException> { store.update(desired) }
  }

  @Test
  fun `viability test results are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial = createAndUpdate {
      it.copy(
          viabilityTests = listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(200))
    }
    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    ViabilityTestModel(
                        id = initial.viabilityTests[0].id,
                        seedsTested = 200,
                        testResults =
                            listOf(
                                ViabilityTestResultModel(
                                    recordingDate = localDate, seedsGerminated = 75)),
                        testType = ViabilityTestType.Lab)))
    store.update(desired)

    val viabilityTests = viabilityTestsDao.fetchByAccessionId(AccessionId(1))
    Assertions.assertEquals(1, viabilityTests.size, "Number of viability tests after update")
    Assertions.assertEquals(37, viabilityTests[0].totalPercentGerminated, "totalPercentGerminated")
    Assertions.assertEquals(75, viabilityTests[0].totalSeedsGerminated, "totalSeedsGerminated")

    val testResults = viabilityTestResultsDao.fetchByTestId(ViabilityTestId(1))
    Assertions.assertEquals(1, testResults.size, "Number of test results after update")
    Assertions.assertTrue(
        testResults.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First test result preserved")

    val updatedAccession = accessionsDao.fetchOneById(AccessionId(1))
    Assertions.assertEquals(37, updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    Assertions.assertEquals(37, updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    Assertions.assertEquals(
        localDate,
        updatedAccession?.latestGerminationRecordingDate,
        "latestGerminationRecordingDate")
  }

  @Test
  fun `viability test results are deleted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(2000),
          viabilityTests =
              listOf(
                  ViabilityTestPayload(
                      testType = ViabilityTestTypeV1.Lab,
                      seedsSown = 1000,
                      testResults =
                          listOf(
                              ViabilityTestResultPayload(
                                  recordingDate = localDate, seedsGerminated = 75),
                              ViabilityTestResultPayload(
                                  recordingDate = localDate.plusDays(1), seedsGerminated = 456)))))
    }

    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    initial.viabilityTests[0].copy(
                        testResults =
                            listOf(
                                ViabilityTestResultModel(
                                    recordingDate = localDate, seedsGerminated = 75)))))
    store.update(desired)
    val testResults = viabilityTestResultsDao.fetchByTestId(ViabilityTestId(1))

    Assertions.assertEquals(1, testResults.size, "Number of test results after update")
    Assertions.assertTrue(
        testResults.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First test result preserved")

    val updatedViabilityTest = viabilityTestsDao.fetchOneById(ViabilityTestId(1))!!
    Assertions.assertEquals(
        7, updatedViabilityTest.totalPercentGerminated, "totalPercentGerminated")
    Assertions.assertEquals(75, updatedViabilityTest.totalSeedsGerminated, "totalSeedsGerminated")

    val updatedAccession = accessionsDao.fetchOneById(AccessionId(1))
    Assertions.assertEquals(7, updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    Assertions.assertEquals(7, updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    Assertions.assertEquals(
        localDate,
        updatedAccession?.latestGerminationRecordingDate,
        "latestGerminationRecordingDate")
  }

  @Test
  fun `update generates withdrawals for new viability tests`() {
    val accession = createAccessionWithViabilityTest()
    val test = accession.viabilityTests[0]

    Assertions.assertEquals(
        listOf(
            WithdrawalModel(
                accessionId = accession.id,
                createdTime = clock.instant(),
                date = test.startDate!!,
                estimatedCount = 5,
                id = WithdrawalId(1),
                purpose = WithdrawalPurpose.ViabilityTesting,
                remaining = seeds(5),
                viabilityTestId = test.id,
                withdrawn = SeedQuantityModel(BigDecimal(5), SeedQuantityUnits.Seeds),
                withdrawnByName = user.fullName,
                withdrawnByUserId = user.userId,
            )),
        accession.withdrawals)
  }

  @Test
  fun `update correctly deducts from seed count for viability tests`() {
    val accession = createAccessionWithViabilityTest()

    Assertions.assertEquals(
        seeds<SeedQuantityModel>(5), accession.remaining, "Seeds remaining after test creation")

    val updated = store.updateAndFetch(accession)
    Assertions.assertEquals(
        seeds<SeedQuantityModel>(5), updated.remaining, "Seeds remaining after test update")
  }

  @Test
  fun `update modifies withdrawals when their viability tests are modified`() {
    val initial = createAccessionWithViabilityTest()
    val initialTest = initial.viabilityTests[0]
    val initialWithdrawal = initial.withdrawals[0]

    val modifiedStartDate = initialTest.startDate!!.plusDays(10)
    val modifiedTest = initialTest.copy(startDate = modifiedStartDate, seedsTested = 6)
    val modifiedWithdrawal =
        initialWithdrawal.copy(
            date = modifiedTest.startDate!!,
            estimatedCount = 6,
            withdrawn = seeds(modifiedTest.seedsTested!!),
            remaining = seeds(4))

    val afterTestModified =
        store.updateAndFetch(initial.copy(viabilityTests = listOf(modifiedTest)))

    Assertions.assertEquals(listOf(modifiedWithdrawal), afterTestModified.withdrawals)
  }

  @Test
  fun `update does not modify withdrawals when their viability tests are not modified`() {
    val initial = createAccessionWithViabilityTest()
    val updated = store.updateAndFetch(initial.copy(receivedDate = LocalDate.now()))

    Assertions.assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update removes withdrawals when viability tests are removed`() {
    val initial = createAccessionWithViabilityTest()
    val updated = store.updateAndFetch(initial.copy(viabilityTests = emptyList()))

    Assertions.assertEquals(emptyList<WithdrawalModel>(), updated.withdrawals)
  }

  @Test
  fun `update ignores viability test withdrawals in accession object`() {
    val initial = createAccessionWithViabilityTest()
    val initialWithdrawal = initial.withdrawals[0]

    val modifiedInitialWithdrawal =
        initialWithdrawal.copy(date = initialWithdrawal.date.plusDays(1), withdrawn = seeds(100))
    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.ViabilityTesting,
            viabilityTestId = initialWithdrawal.viabilityTestId,
            withdrawn = seeds(1))

    val updated =
        store.updateAndFetch(
            initial.copy(withdrawals = listOf(modifiedInitialWithdrawal, newWithdrawal)))

    Assertions.assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update rejects viability tests without remaining quantity for weight-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(100),
              viabilityTests = listOf(ViabilityTestModel(testType = ViabilityTestType.Lab))))
    }
  }

  @Test
  fun `update computes remaining quantity on viability tests for count-based accessions`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100),
          viabilityTests =
              listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Lab, seedsSown = 10)))
    }

    Assertions.assertEquals(
        seeds<SeedQuantityModel>(90),
        initial.viabilityTests[0].remaining,
        "Quantity remaining on test")
    Assertions.assertEquals(
        seeds<SeedQuantityModel>(90), initial.remaining, "Quantity remaining on accession")
  }

  @Test
  fun `update does not allow processing method to change if viability test exists`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(10),
          viabilityTests = listOf(ViabilityTestPayload(testType = ViabilityTestTypeV1.Lab)))
    }

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(processingMethod = ProcessingMethod.Weight, total = grams(5)))
    }
  }
}
