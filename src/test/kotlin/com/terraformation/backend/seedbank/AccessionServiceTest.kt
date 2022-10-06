package com.terraformation.backend.seedbank

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.WithdrawalNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.mockUser
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val accessionStore: AccessionStore = mockk()
  private val clock: Clock = mockk()
  private val photoRepository: PhotoRepository = mockk()

  private val service: AccessionService by lazy {
    AccessionService(accessionStore, clock, dslContext, photoRepository)
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
            processingMethod = ProcessingMethod.Count,
            remaining = seeds(10),
            total = seeds(15),
            withdrawals =
                listOf(
                    WithdrawalModel(
                        accessionId = accessionId,
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
      assertTrue(updatedAccession.isManualState, "Accession is converted to v2")
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
}
