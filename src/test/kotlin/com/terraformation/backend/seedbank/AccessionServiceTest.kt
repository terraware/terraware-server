package com.terraformation.backend.seedbank

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.mockUser
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.nursery.db.CrossOrganizationNurseryTransferNotAllowedException
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
  private val clock: Clock = mockk()
  private val parentStore: ParentStore = mockk()
  private val photoRepository: PhotoRepository = mockk()

  private val service: AccessionService by lazy {
    AccessionService(
        accessionStore,
        batchStore,
        clock,
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
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC
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
            id = accessionId,
            facilityId = FacilityId(1),
            latestObservedQuantity = seeds(15),
            latestObservedTime = Instant.EPOCH,
            processingMethod = ProcessingMethod.Count,
            remaining = seeds(10),
            state = AccessionState.InStorage,
            withdrawals =
                listOf(
                    WithdrawalModel(
                        accessionId = accessionId,
                        createdTime = Instant.ofEpochSecond(1),
                        date = LocalDate.EPOCH,
                        id = withdrawalId,
                        withdrawn = seeds(5))))

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
              accessionId = accessionId, date = LocalDate.EPOCH.plusDays(1), withdrawn = seeds(3))
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
              accessionId = accessionId, date = LocalDate.EPOCH.plusDays(1), withdrawn = seeds(50))
      val exceptionThrown =
          try {
            service.createWithdrawal(withdrawal)
            null
          } catch (e: Exception) {
            e
          }

      assertNotNull(exceptionThrown, "Expected exception to be thrown")
      assertEquals(
          "Withdrawal quantity can't be more than remaining quantity", exceptionThrown?.message)
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
    private val seedBankFacilityId = FacilityId(1)
    private val nurseryFacilityId = FacilityId(2)

    private val accession =
        AccessionModel(
            id = accessionId,
            facilityId = seedBankFacilityId,
            latestObservedQuantity = seeds(10),
            latestObservedTime = Instant.EPOCH,
            remaining = seeds(10),
            speciesId = SpeciesId(1))

    private val accessionSlot: CapturingSlot<AccessionModel> = slot()
    private val batchSlot: CapturingSlot<BatchesRow> = slot()

    @BeforeEach
    fun setUp() {
      every { accessionStore.fetchOneById(accessionId) } returns accession
      every { accessionStore.updateAndFetch(capture(accessionSlot)) } answers
          {
            accessionSlot.captured
          }
      every { batchStore.create(capture(batchSlot)) } answers { batchSlot.captured }
      every { parentStore.getOrganizationId(seedBankFacilityId) } returns organizationId
      every { parentStore.getOrganizationId(nurseryFacilityId) } returns organizationId

      every { user.canCreateBatch(nurseryFacilityId) } returns true
      every { user.canReadFacility(nurseryFacilityId) } returns true
    }

    @Test
    fun `requires nursery facility ID`() {
      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(accessionId, BatchesRow(germinatingQuantity = 1))
      }
    }

    @Test
    fun `adds new withdrawal to accession`() {
      val date = LocalDate.EPOCH.plusDays(1)

      val (updatedAccession, _) =
          service.createNurseryTransfer(
              accessionId,
              BatchesRow(
                  addedDate = date,
                  facilityId = nurseryFacilityId,
                  germinatingQuantity = 1,
                  notes = "Notes",
                  notReadyQuantity = 2,
                  readyQuantity = 3))

      assertEquals(seeds(4), updatedAccession.remaining, "Seeds remaining")
      assertEquals(1, updatedAccession.withdrawals.size, "Number of withdrawals")
      assertEquals(seeds(6), updatedAccession.withdrawals[0].withdrawn, "Size of new withdrawal")
      assertEquals("Notes", updatedAccession.withdrawals[0].notes, "Notes")
      assertEquals(date, updatedAccession.withdrawals[0].date, "Withdrawal date")
      verify { accessionStore.updateAndFetch(any()) }
    }

    @Test
    fun `associates new seedling batch with accession`() {
      val date = LocalDate.EPOCH.plusDays(1)
      val (_, batch) =
          service.createNurseryTransfer(
              accessionId,
              BatchesRow(
                  addedDate = date,
                  facilityId = nurseryFacilityId,
                  germinatingQuantity = 1,
                  notReadyQuantity = 2,
                  readyQuantity = 3))

      assertEquals(accessionId, batch.accessionId)
      verify { batchStore.create(any()) }
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
              BatchesRow(
                  addedDate = LocalDate.EPOCH.plusDays(1),
                  facilityId = nurseryFacilityId,
                  germinatingQuantity = 1,
                  notReadyQuantity = 2,
                  readyQuantity = 3))

      assertEquals(grams(initialGrams - gramsPerSeed * (1 + 2 + 3)), accession.remaining)
    }

    @Test
    fun `throws exception if no permission to create batch in nursery`() {
      every { user.canCreateBatch(nurseryFacilityId) } returns false

      assertThrows<AccessDeniedException> {
        service.createNurseryTransfer(
            accessionId,
            BatchesRow(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                notReadyQuantity = 0,
                readyQuantity = 0))
      }
    }

    @Test
    fun `throws exception if seed bank and nursery are in different organizations`() {
      every { parentStore.getOrganizationId(nurseryFacilityId) } returns OrganizationId(1000)

      assertThrows<CrossOrganizationNurseryTransferNotAllowedException> {
        service.createNurseryTransfer(
            accessionId,
            BatchesRow(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                notReadyQuantity = 0,
                readyQuantity = 0))
      }
    }

    @Test
    fun `throws exception if not enough seeds in accession`() {
      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            BatchesRow(
                addedDate = LocalDate.EPOCH.plusDays(1),
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1000,
                notReadyQuantity = 2000,
                readyQuantity = 3000))
      }
    }

    @Test
    fun `throws exception if accession is weight-based and has no subset data`() {
      every { accessionStore.fetchOneById(accessionId) } returns
          accession.copy(remaining = grams(1000))

      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            BatchesRow(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                notReadyQuantity = 0,
                readyQuantity = 0))
      }
    }

    @Test
    fun `throws exception if accession has no species ID`() {
      every { accessionStore.fetchOneById(accessionId) } returns accession.copy(speciesId = null)

      assertThrows<IllegalArgumentException> {
        service.createNurseryTransfer(
            accessionId,
            BatchesRow(
                addedDate = LocalDate.EPOCH,
                facilityId = nurseryFacilityId,
                germinatingQuantity = 1,
                notReadyQuantity = 0,
                readyQuantity = 0))
      }
    }
  }
}
