package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.records.OrganizationMediaFilesRecord
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.net.URI
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.access.AccessDeniedException

internal class OrganizationMediaServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val fileService: FileService by lazy {
    FileService(dslContext, clock, eventPublisher, filesDao, fileStore)
  }
  private val muxService: MuxService = mockk()

  private val service: OrganizationMediaService by lazy {
    OrganizationMediaService(dslContext, eventPublisher, fileService, muxService)
  }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canReadOrganization(any()) } returns true
    every { user.canCreateOrganizationMedia(any()) } returns true
    every { user.canReadOrganizationMedia(any()) } returns true
    every { user.canUpdateOrganizationMedia(any()) } returns true
    every { user.canDeleteOrganizationMedia(any()) } returns true
  }

  @Nested
  inner class Upload {
    @Test
    fun `inserts organization media file row with correct organization and caption`() {
      val multipartFile = MockMultipartFile("file", "photo.jpg", "image/jpeg", ByteArray(1024))

      val insertedFileId = service.upload(organizationId, multipartFile, "Test caption")

      assertTableEquals(
          OrganizationMediaFilesRecord(insertedFileId, organizationId, "Test caption")
      )
    }

    @Test
    fun `throws AccessDeniedException when user cannot create organization media`() {
      every { user.canCreateOrganizationMedia(any()) } returns false

      val multipartFile = MockMultipartFile("file", "photo.jpg", "image/jpeg", ByteArray(0))

      assertThrows<AccessDeniedException> {
        service.upload(organizationId, multipartFile, "caption")
      }
    }
  }

  @Nested
  inner class Read {
    @Test
    fun `returns file contents for a valid file in the organization`() {
      val fileId =
          insertOrganizationMediaFile(fileId = insertFile(), organizationId = organizationId)
      val content = ByteArray(10) { it.toByte() }
      val sizedInputStream = SizedInputStream(ByteArrayInputStream(content), content.size.toLong())

      fileStore.write(URI("http://dummy/1"), sizedInputStream)

      val result = service.read(organizationId, fileId)

      assertArrayEquals(content, result.readAllBytes())
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to the organization`() {
      val otherOrgId = insertOrganization()
      val fileId = insertOrganizationMediaFile(fileId = insertFile(), organizationId = otherOrgId)

      assertThrows<FileNotFoundException> { service.read(organizationId, fileId) }
    }

    @Test
    fun `throws FileNotFoundException when file does not exist`() {
      val bogusFileId = FileId(99999)

      assertThrows<FileNotFoundException> { service.read(organizationId, bogusFileId) }
    }

    @Test
    fun `throws AccessDeniedException when user cannot read organization media`() {
      every { user.canReadOrganizationMedia(any()) } returns false

      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )

      assertThrows<AccessDeniedException> { service.read(organizationId, fileId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates caption`() {
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
              caption = "Original caption",
          )

      service.update(organizationId, fileId, caption = "Updated caption", gpsCoordinates = null)

      assertTableEquals(OrganizationMediaFilesRecord(fileId, organizationId, "Updated caption"))
    }

    @Test
    fun `clears caption`() {
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
              caption = "Original caption",
          )

      service.update(organizationId, fileId, caption = null, gpsCoordinates = null)

      assertTableEquals(OrganizationMediaFilesRecord(fileId, organizationId, null))
    }

    @Test
    fun `updates GPS coordinates in files table when provided`() {
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )
      val geolocation = point(-122.1, 37.7749)

      service.update(organizationId, fileId, caption = null, gpsCoordinates = geolocation)

      assertGeometryEquals(geolocation, filesDao.fetchOneById(fileId)?.geolocation)
    }

    @Test
    fun `does not modify geolocation when gpsCoordinates is null`() {
      val geolocation = point(15, 20)
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(geolocation = geolocation),
              organizationId = organizationId,
          )

      service.update(organizationId, fileId, caption = "test", gpsCoordinates = null)

      assertGeometryEquals(geolocation, filesDao.fetchOneById(fileId)?.geolocation)
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to the organization`() {
      val otherOrgId = insertOrganization()
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = otherOrgId,
          )

      assertThrows<FileNotFoundException> {
        service.update(organizationId, fileId, caption = "test", gpsCoordinates = null)
      }
    }

    @Test
    fun `throws AccessDeniedException when user cannot update organization media`() {
      every { user.canUpdateOrganizationMedia(any()) } returns false

      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )

      assertThrows<AccessDeniedException> {
        service.update(organizationId, fileId, caption = "test", gpsCoordinates = null)
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes database row and fires event`() {
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )

      service.delete(organizationId, fileId)

      assertTableEmpty(ORGANIZATION_MEDIA_FILES)

      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(fileId))
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to the organization`() {
      val otherOrgId = insertOrganization()
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = otherOrgId,
          )

      assertThrows<FileNotFoundException> { service.delete(organizationId, fileId) }
    }

    @Test
    fun `throws AccessDeniedException when user cannot delete organization media`() {
      every { user.canDeleteOrganizationMedia(any()) } returns false

      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )

      assertThrows<AccessDeniedException> { service.delete(organizationId, fileId) }
    }
  }

  @Nested
  inner class GetStream {
    @Test
    fun `returns stream model from Mux`() {
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )
      val expectedStream = MuxStreamModel(fileId, "playback-123", "token-abc")

      every { muxService.getMuxStream(fileId) } returns expectedStream

      val result = service.getStream(organizationId, fileId)

      assertEquals(expectedStream, result, "Should return the MuxStreamModel from MuxService")
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to the organization`() {
      val otherOrgId = insertOrganization()
      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = otherOrgId,
          )

      assertThrows<FileNotFoundException> { service.getStream(organizationId, fileId) }
    }

    @Test
    fun `throws AccessDeniedException when user cannot read organization media`() {
      every { user.canReadOrganizationMedia(any()) } returns false

      val fileId =
          insertOrganizationMediaFile(
              fileId = insertFile(),
              organizationId = organizationId,
          )

      assertThrows<AccessDeniedException> { service.getStream(organizationId, fileId) }
    }
  }
}
