package com.terraformation.backend.seedbank

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.db.CrossOrganizationNurseryTransferNotAllowedException
import com.terraformation.backend.nursery.model.ExistingBatchModel
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val accessionStore: AccessionStore = mockk()
  private val batchStore: BatchStore = mockk()
  private val clock = TestClock()
  private val parentStore: ParentStore = mockk()
  private val photoRepository: PhotoRepository = mockk()

  private val service: AccessionService by lazy {
    AccessionService(
        accessionStore,
        batchStore,
        dslContext,
        parentStore,
        photoRepository,
        mockk(),
        SearchTables(clock),
    )
  }

  private val accessionId = AccessionId(1)

  @BeforeEach
  fun setUp() {
    every { accessionStore.delete(any()) } just Runs
    every { photoRepository.deleteAllPhotos(any()) } just Runs
    every { user.canDeleteAccession(any()) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canUpdateAccession(any()) } returns true
  }

  @Nested
  inner class DeleteAccession {
    @Test
    fun `throws exception if user has no permission`() {
      every { user.canDeleteAccession(any()) } returns false

      assertThrows<AccessDeniedException> { service.deleteAccession(accessionId) }
    }

    @Test
    fun `does not try to delete accession if photo deletion fails`() {
      every { photoRepository.deleteAllPhotos(any()) } throws DataAccessException("uh oh")

      assertThrows<DataAccessException> { service.deleteAccession(accessionId) }

      verify(exactly = 0) { accessionStore.delete(accessionId) }
    }

    @Test
    fun `deletes photos and accession data`() {
      service.deleteAccession(accessionId)

      verify { photoRepository.deleteAllPhotos(accessionId) }
      verify { accessionStore.delete(accessionId) }
    }
  }

  @Nested
  inner class Withdrawals {
    private val accessionId = AccessionId(1)
    private val withdrawalId = WithdrawalId(1)
    private val accessionWithOneWithdrawal =
        AccessionModel(
            clock = clock,
            id = accessionId,
            facilityId = FacilityId(1),
            latestObservedQuantity = seeds(15),
            latestObservedTime = Instant.EPOCH,
            remaining = seeds(10),
            state = AccessionState.InStorage,
            withdrawals =
                listOf(
                    WithdrawalModel(
                        accessionId = accessionId,
                        createdTime = Instant.ofEpochSecond(1),
                        date = LocalDate.EPOCH,
                        id = withdrawalId,
                        withdrawn = seeds(5),
                    )
                ),
        )

    private val updateSlot: CapturingSlot<AccessionModel> = slot()

    @BeforeEach
    fun setUp() {
      every { accessionStore.fetchOneById(accessionId) } returns accessionWithOneWithdrawal
      every { accessionStore.updateAndFetch(capture(updateSlot)) } answers { updateSlot.captured }
    }

    @Test
    fun `createWithdrawal requires accession ID`() {
      assertThrows<IllegalArgumentException> {
        service.createWithdrawal(WithdrawalModel(date = LocalDate.EPOCH, withdrawn = seeds(1)))
      }
    }

    @Test
    fun `createWithdrawal adds new withdrawal to accession`() {
      val withdrawal =
          WithdrawalModel(
              accessionId = accessionId,
              date = LocalDate.EPOCH.plusDays(1),
              withdrawn = seeds(3),
          )
      val updatedAccession = service.createWithdrawal(withdrawal)

      assertEquals(seeds(7), updatedAccession.remaining, "Seeds remaining")
      assertEquals(2, updatedAccession.withdrawals.size, "Number of withdrawals")
      assertEquals(seeds(3), updatedAccession.withdrawals[1].withdrawn, "Size of new withdrawal")
      verify { accessionStore.updateAndFetch(any()) }
    }

    @Test
    fun `createWithdrawal throws exception with correct error message if quantity too big`() {
      val withdrawal =
          WithdrawalModel(
              accessionId = accessionId,
              date = LocalDate.EPOCH.plusDays(1),
              withdrawn = seeds(50),
          )
      val exceptionThrown =
          try {
            service.createWithdrawal(withdrawal)
            null
          } catch (e: Exception) {
            e
          }

      assertNotNull(exceptionThrown, "Expected exception to be thrown")
      assertEquals(
          "Withdrawal quantity can't be more than remaining quantity",
          exceptionThrown?.message,
      )
    }

    @Test
    fun `createViabilityTest uses facility time zone to determine default withdrawal date`() {
      val earlierZoneThanUtc = ZoneId.of("America/New_York")
      every { accessionStore.fetchOneById(accessionId) } returns
          accessionWithOneWithdrawal.copy(clock = clock.withZone(earlierZoneThanUtc))

      val viabilityTest =
          ViabilityTestModel(accessionId = accessionId, testType = ViabilityTestType.Lab)
      val updatedAccession = service.createViabilityTest(viabilityTest)

      assertEquals(LocalDate.EPOCH.minusDays(1), updatedAccession.withdrawals[0].date)
    }

    @Test
    fun `updateWithdrawal throws exception if withdrawal does not exist`() {
      assertThrows<WithdrawalNotFoundException> {
        service.updateWithdrawal(accessionId, WithdrawalId(1000)) { it }
      }
    }

    @Test
    fun `updateWithdrawal applies changes to withdrawal`() {
      val updatedAccession =
          service.updateWithdrawal(accessionId, withdrawalId) { it.copy(withdrawn = seeds(2)) }

      assertEquals(seeds(13), updatedAccession.remaining, "Seeds remaining")
      assertEquals(1, updatedAccession.withdrawals.size, "Number of withdrawals")
      assertEquals(seeds(2), updatedAccession.withdrawals[0].withdrawn, "Seeds withdrawn")
      verify { accessionStore.updateAndFetch(any()) }
    }

    @Test
    fun `deleteWithdrawal throws exception if withdrawal does not exist`() {
      assertThrows<WithdrawalNotFoundException> {
        service.deleteWithdrawal(accessionId, WithdrawalId(1000))
      }
    }

    @Test
    fun `deleteWithdrawal deletes the withdrawal`() {
      val updatedAccession = service.deleteWithdrawal(accessionId, withdrawalId)

      assertEquals(seeds(15), updatedAccession.remaining, "Seeds remaining")
      assertEquals(0, updatedAccession.withdrawals.size, "Number of withdrawals")
      verify { accessionStore.updateAndFetch(any()) }
    }
  }

  @Nested
  inner class CreateNurseryTransfer {
    private val accessionId = AccessionId(1)
    private val batchId = BatchId(1)
    private val seedBankFacilityId = FacilityId(1)
    private val nurseryFacilityId = FacilityId(2)
    private val organizationId = OrganizationId(1)

    private val accession =
        AccessionModel(
            clock = clock,
            id = accessionId,
            facilityId = seedBankFacilityId,
            latestObservedQuantity = seeds(10),
            latestObservedTime = Instant.EPOCH,
            remaining = seeds(10),
            speciesId = SpeciesId(1),
        )

    private val accessionSlot: CapturingSlot<AccessionModel> = slot()
    private val batchSlot: CapturingSlot<NewBatchModel> = slot()

    @BeforeEach
    fun setUp() {
      every { accessionStore.fetchOneById(accessionId) } returns accession
      every { accessionStore.updateAndFetch(capture(accessionSlot)) } answers
          {
            accessionSlot.captured
          }
      every { batchStore.create(capture(batchSlot)) } answers
          {
            ExistingBatchModel(
                accessionId = batchSlot.captured.accessionId,
                addedDate = batchSlot.captured.addedDate,
                batchNumber = "1",
                facilityId = batchSlot.captured.facilityId,
                germinatingQuantity = batchSlot.captured.germinatingQuantity,
                hardeningOffQuantity = batchSlot.captured.hardeningOffQuantity,
                id = batchId,
                latestObservedGerminatingQuantity = batchSlot.captured.germinatingQuantity,
                latestObservedHardeningOffQuantity = batchSlot.captured.hardeningOffQuantity,
                latestObservedActiveGrowthQuantity = batchSlot.captured.activeGrowthQuantity,
                latestObservedReadyQuantity = batchSlot.captured.readyQuantity,
                latestObservedTime = Instant.EPOCH,
                notes = batchSlot.captured.notes,
                activeGrowthQuantity = batchSlot.captured.activeGrowthQuantity,
                organizationId = organizationId,
                readyByDate = batchSlot.captured.readyByDate,
                readyQuantity = batchSlot.captured.readyQuantity,
                speciesId = batchSlot.captured.speciesId ?: SpeciesId(1),
                totalWithdrawn = 0,
                version = 1,
            )
          }
      every { parentStore.getOrganizationId(seedBankFacilityId) } returns organizationId
      every { parentStore.getOrganizationId(nurseryFacilityId) } returns organizationId

      every { user.canCreateBatch(nurseryFacilityId) } returns true
      every { user.canReadFacility(nurseryFacilityId) } returns true
    }

    @Test
    fun `adds new withdrawal to accession`() {
      val date = LocalDate.EPOCH.plusDays(1)

      val (updatedAccession, _) =
          service.createNurseryTransfer(
              accessionId,
              NewBatchModel(
                  addedDate = date,
                  facilityId = nurseryFacilityId,
                  germinatingQuantity = 1,
                  notes = "Notes",
                  activeGrowthQuantity = 2,
                  readyQuantity = 3,
                  hardeningOffQuantity = 4,
                  speciesId = null,
              ),
          )

      assertEquals(seeds(0), updatedAccession.remaining, "Seeds remaining")
      assertEquals(1, updatedAccession.withdrawals.size, "Number of withdrawals")
      assertEquals(seeds(10), updatedAccession.withdrawals[0].withdrawn, "Size of new withdrawal")
      assertEquals("Notes", updatedAccession.withdrawals[0].notes, "Notes")
      assertEquals(date, updatedAccession.withdrawals[0].date, "Withdrawal date")
      assertEquals(batchId, updatedAccession.withdrawals[0].batchId, "Batch ID")
      verify { accessionStore.updateAndFetch(any()) }
    }

    @Test
    fun `associates new seedling batch with accession`() {
      val date = LocalDate.EPOCH.plusDays(1)

      val newBatch =
          NewBatchModel(
              addedDate = date,
              facilityId = nurseryFacilityId,
              germinatingQuantity = 1,
              activeGrowthQuantity = 2,
              readyQuantity = 3,
              hardeningOffQuantity = 4,
              speciesId = null,
          )

      val (accession, batch) = service.createNurseryTransfer(accessionId, newBatch)

      // Verify that the accession values were propagated correctly
      assertEquals(accessionId, batch.accessionId)
      assertEquals(accession.projectId, batch.projectId)
      assertEquals(accession.speciesId, batch.speciesId)

      val newBatchWithAccessionData =
          newBatch.copy(
              accessionId = accessionId,
              projectId = accession.projectId,
              speciesId = accession.speciesId,
          )

      verify { batchStore.create(newBatchWithAccessionData) }
    }

    @Test
    fun `deducts from remaining weight if accession is weight-based and has subset data`() {
      val initialGrams = 1000
      val gramsPerSeed = 2

      every { accessionStore.fetchOneById(accessionId) } returns
          accession.copy(
              latestObservedQuantity = grams(initialGrams),
              remaining = grams(initialGrams),
              subsetCount = 1,
              subsetWeightQuantity = grams(gramsPerSeed),
          )

      val (accession, _) =
          service.createNurseryTransfer(
              accessionId,
              NewBatchModel(
                  addedDate = LocalDate.EPOCH.plusDays(1),
                  facilityId = nurseryFacilityId,
                  germinatingQuantity = 1,
                  activeGrowthQuantity = 2,
                  readyQuantity = 3,
                  hardeningOffQuantity = 4,
                  speciesId = null,
              ),
          )

      assertEquals(grams(initialGrams - gramsPerSeed * (1 + 2 + 3 + 4)), accession.remaining)
    }

    @Test
    fun `throws exception if no permission to create batch in nursery`() {
      every { user.canCreateBatch(nurseryFacilityId) } returns false

      assertThrows<AccessDeniedException> {
        service.createNurseryTransfer(
            accessionId,
            NewBatchModel(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                activeGrowthQuantity = 0,
                readyQuantity = 0,
                hardeningOffQuantity = 0,
                speciesId = null,
            ),
        )
      }
    }

    @Test
    fun `throws exception if seed bank and nursery are in different organizations`() {
      every { parentStore.getOrganizationId(nurseryFacilityId) } returns OrganizationId(1000)

      assertThrows<CrossOrganizationNurseryTransferNotAllowedException> {
        service.createNurseryTransfer(
            accessionId,
            NewBatchModel(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                activeGrowthQuantity = 0,
                readyQuantity = 0,
                hardeningOffQuantity = 0,
                speciesId = null,
            ),
        )
      }
    }

    @Test
    fun `throws exception if not enough seeds in accession`() {
      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            NewBatchModel(
                addedDate = LocalDate.EPOCH.plusDays(1),
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1000,
                activeGrowthQuantity = 2000,
                readyQuantity = 3000,
                hardeningOffQuantity = 4000,
                speciesId = null,
            ),
        )
      }
    }

    @Test
    fun `throws exception if accession is weight-based and has no subset data`() {
      every { accessionStore.fetchOneById(accessionId) } returns
          accession.copy(remaining = grams(1000))

      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            NewBatchModel(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                activeGrowthQuantity = 0,
                readyQuantity = 0,
                hardeningOffQuantity = 0,
                speciesId = null,
            ),
        )
      }
    }

    @Test
    fun `throws exception if accession has no species ID`() {
      every { accessionStore.fetchOneById(accessionId) } returns accession.copy(speciesId = null)

      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            NewBatchModel(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                activeGrowthQuantity = 0,
                readyQuantity = 0,
                hardeningOffQuantity = 0,
                speciesId = null,
            ),
        )
      }
    }
  }
}
