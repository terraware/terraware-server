package com.terraformation.backend.seedbank

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.PhotoRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class AccessionServiceTest : RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val accessionStore: AccessionStore = mockk()
  private val photoRepository: PhotoRepository = mockk()

  private val service = AccessionService(accessionStore, photoRepository)

  private val accessionId = AccessionId(1)

  @BeforeEach
  fun setUp() {
    every { accessionStore.delete(any()) } just Runs
    every { photoRepository.deleteAllPhotos(any()) } just Runs
    every { user.canDeleteAccession(any()) } returns true
    every { user.canReadAccession(any()) } returns true
  }

  @Test
  fun `deleteAccession throws exception if user has no permission`() {
    every { user.canDeleteAccession(any()) } returns false

    assertThrows<AccessDeniedException> { service.deleteAccession(accessionId) }
  }

  @Test
  fun `deleteAccession does not try to delete accession if photo deletion fails`() {
    every { photoRepository.deleteAllPhotos(any()) } throws DataAccessException("uh oh")

    assertThrows<DataAccessException> { service.deleteAccession(accessionId) }

    verify(exactly = 0) { accessionStore.delete(accessionId) }
  }

  @Test
  fun `deleteAccession deletes photos and accession data`() {
    service.deleteAccession(accessionId)

    verify { photoRepository.deleteAllPhotos(accessionId) }
    verify { accessionStore.delete(accessionId) }
  }
}
