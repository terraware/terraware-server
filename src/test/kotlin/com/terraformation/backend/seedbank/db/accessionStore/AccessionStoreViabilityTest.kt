package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionStoreViabilityTest : AccessionStoreTest() {
  @Test
  fun `viability tests are inserted by update`() {
    val startDate = LocalDate.EPOCH

    val initial =
        create()
            .andUpdate { it.copy(remaining = seeds(101)) }
            .andUpdate {
              it.addViabilityTest(
                  ViabilityTestModel(
                      seedsTested = 1,
                      startDate = startDate,
                      testType = ViabilityTestType.Lab,
                  )
              )
            }

    val updatedTests = viabilityTestsDao.fetchByAccessionId(initial.id!!)
    assertEquals(
        listOf(
            ViabilityTestsRow(
                accessionId = initial.id,
                seedsSown = 1,
                startDate = startDate,
                testType = ViabilityTestType.Lab,
            )
        ),
        updatedTests.map { it.copy(id = null) },
    )

    val updatedAccession = store.fetchOneById(initial.id!!)
    assertNull(updatedAccession.totalViabilityPercent, "Total viability percent should not be set")
    assertNull(
        updatedAccession.viabilityTests.first().testResults,
        "Empty list of viability test results should be null in model",
    )
  }

  @Test
  fun `existing viability tests are updated`() {
    val initial =
        create()
            .andUpdate { it.copy(remaining = seeds(100)) }
            .andAdvanceClock(Duration.ofSeconds(1))
            .andUpdate {
              it.addViabilityTest(
                  ViabilityTestModel(seedsTested = 1, testType = ViabilityTestType.Lab)
              )
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
                        treatment = SeedTreatment.Scarify,
                    )
                )
        )
    store.update(desired)

    val updatedTests = viabilityTestsDao.fetchByAccessionId(initial.id!!)
    assertEquals(
        listOf(
            ViabilityTestsRow(
                id = initial.viabilityTests[0].id,
                accessionId = initial.id,
                testType = ViabilityTestType.Lab,
                seedTypeId = ViabilityTestSeedType.Fresh,
                treatmentId = SeedTreatment.Scarify,
                substrateId = ViabilityTestSubstrate.Paper,
                notes = "notes",
                seedsSown = 5,
            )
        ),
        updatedTests,
    )
  }

  @Test
  fun `change to viability test quantity is propagated to withdrawal and accession when weight-based`() {
    val initial =
        create()
            .andUpdate {
              it.copy(remaining = grams(100), subsetCount = 2, subsetWeightQuantity = grams(1))
            }
            .andAdvanceClock(Duration.ofSeconds(1))
            .andUpdate {
              it.addViabilityTest(
                  ViabilityTestModel(seedsTested = 50, testType = ViabilityTestType.Lab)
              )
            }

    assertEquals(Instant.EPOCH, initial.latestObservedTime, "Latest observed time")
    assertEquals(
        grams<SeedQuantityModel>(75),
        initial.remaining,
        "Accession remaining quantity before update",
    )

    val desired =
        initial.updateViabilityTest(initial.viabilityTests[0].id!!) { it.copy(seedsTested = 80) }
    val updated = store.updateAndFetch(desired)

    assertEquals(
        grams<SeedQuantityModel>(60),
        updated.remaining,
        "Accession remaining quantity after update",
    )
  }

  @Test
  fun `cannot update viability test from a different accession`() {
    val other =
        create().andUpdate {
          it.copy(
              viabilityTests =
                  listOf(ViabilityTestModel(seedsTested = 1, testType = ViabilityTestType.Nursery)),
              remaining = seeds(100),
          )
        }
    val initial =
        create().andUpdate {
          it.copy(
              viabilityTests =
                  listOf(ViabilityTestModel(seedsTested = 2, testType = ViabilityTestType.Lab)),
              remaining = seeds(100),
          )
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
                        treatment = SeedTreatment.Scarify,
                    )
                )
        )

    assertThrows<IllegalArgumentException> { store.update(desired) }
  }

  @Test
  fun `viability test results are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial =
        create()
            .andUpdate { it.copy(remaining = seeds(200)) }
            .andUpdate {
              it.copy(
                  viabilityTests =
                      listOf(
                          ViabilityTestModel(seedsTested = 200, testType = ViabilityTestType.Lab)
                      ),
              )
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
                                    recordingDate = localDate,
                                    seedsGerminated = 75,
                                )
                            ),
                        testType = ViabilityTestType.Lab,
                    )
                )
        )
    store.update(desired)

    val viabilityTests = viabilityTestsDao.fetchByAccessionId(initial.id!!)
    assertEquals(1, viabilityTests.size, "Number of viability tests after update")
    assertEquals(37, viabilityTests[0].totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, viabilityTests[0].totalSeedsGerminated, "totalSeedsGerminated")

    val testResults = viabilityTestResultsDao.fetchByTestId(initial.viabilityTests[0].id!!)
    assertEquals(1, testResults.size, "Number of test results after update")
    assertTrue(
        testResults.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First test result preserved",
    )
  }

  @Test
  fun `viability test results are deleted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial =
        create().andUpdate {
          it.copy(
              remaining = seeds(2000),
              viabilityTests =
                  listOf(
                      ViabilityTestModel(
                          testType = ViabilityTestType.Lab,
                          seedsTested = 1000,
                          testResults =
                              listOf(
                                  ViabilityTestResultModel(
                                      recordingDate = localDate,
                                      seedsGerminated = 75,
                                  ),
                                  ViabilityTestResultModel(
                                      recordingDate = localDate.plusDays(1),
                                      seedsGerminated = 456,
                                  ),
                              ),
                      )
                  ),
          )
        }

    val desired =
        initial.copy(
            viabilityTests =
                listOf(
                    initial.viabilityTests[0].copy(
                        testResults =
                            listOf(
                                ViabilityTestResultModel(
                                    recordingDate = localDate,
                                    seedsGerminated = 75,
                                )
                            )
                    )
                )
        )
    store.update(desired)
    val testResults = viabilityTestResultsDao.fetchByTestId(initial.viabilityTests[0].id!!)

    assertEquals(1, testResults.size, "Number of test results after update")
    assertTrue(
        testResults.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First test result preserved",
    )

    val updatedViabilityTest = viabilityTestsDao.fetchOneById(initial.viabilityTests[0].id!!)!!
    assertEquals(7, updatedViabilityTest.totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, updatedViabilityTest.totalSeedsGerminated, "totalSeedsGerminated")
  }

  @Test
  fun `update generates withdrawals for new viability tests`() {
    val accession = createAccessionWithViabilityTest()
    val test = accession.viabilityTests[0]

    assertEquals(
        listOf(
            WithdrawalModel(
                accessionId = accession.id,
                createdTime = clock.instant(),
                date = test.startDate!!,
                estimatedCount = 5,
                purpose = WithdrawalPurpose.ViabilityTesting,
                viabilityTestId = test.id,
                withdrawn = SeedQuantityModel(BigDecimal(5), SeedQuantityUnits.Seeds),
                withdrawnByName = "First Last",
                withdrawnByUserId = user.userId,
            )
        ),
        accession.withdrawals.map { it.copy(id = null) },
    )
  }

  @Test
  fun `update correctly deducts from seed count for viability tests`() {
    val accession = createAccessionWithViabilityTest()

    assertEquals(
        seeds<SeedQuantityModel>(5),
        accession.remaining,
        "Seeds remaining after test creation",
    )

    val updated = store.updateAndFetch(accession)
    assertEquals(
        seeds<SeedQuantityModel>(5),
        updated.remaining,
        "Seeds remaining after test update",
    )
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
        )

    val afterTestModified =
        store.updateAndFetch(initial.copy(viabilityTests = listOf(modifiedTest)))

    assertEquals(listOf(modifiedWithdrawal), afterTestModified.withdrawals)
  }

  @Test
  fun `update does not modify withdrawals when their viability tests are not modified`() {
    val initial = createAccessionWithViabilityTest()
    val updated = store.updateAndFetch(initial.copy(receivedDate = LocalDate.now()))

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update removes withdrawals when viability tests are removed`() {
    val initial = createAccessionWithViabilityTest()
    val updated = store.updateAndFetch(initial.copy(viabilityTests = emptyList()))

    assertEquals(emptyList<WithdrawalModel>(), updated.withdrawals)
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
            withdrawn = seeds(1),
        )

    val updated =
        store.updateAndFetch(
            initial.copy(withdrawals = listOf(modifiedInitialWithdrawal, newWithdrawal))
        )

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update computes remaining quantity for count-based accessions`() {
    val accession =
        create()
            .andUpdate { it.copy(remaining = seeds(100)) }
            .andUpdate {
              it.copy(
                  viabilityTests =
                      listOf(ViabilityTestModel(testType = ViabilityTestType.Lab, seedsTested = 10))
              )
            }

    assertEquals(
        seeds<SeedQuantityModel>(90),
        accession.remaining,
        "Quantity remaining on accession",
    )
  }
}
