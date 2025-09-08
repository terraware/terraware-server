package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.point
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Point

internal class ActivityMediaServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val fileStore = InMemoryFileStore()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(
        dslContext,
        clock,
        config,
        filesDao,
        fileStore,
        thumbnailStore,
    )
  }
  private val service: ActivityMediaService by lazy {
    ActivityMediaService(clock, dslContext, fileService, ParentStore(dslContext))
  }

  private val jpegMetadata = NewFileMetadata("image/jpeg", "test.jpg", null, 1000L, null)
  private val pngMetadata = NewFileMetadata("image/png", "test.png", null, 95L, null)

  private lateinit var activityId: ActivityId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    val projectId = insertProject(organizationId)
    activityId = insertActivity(projectId = projectId)

    insertOrganizationUser(role = Role.Admin)

    every { config.keepInvalidUploads } returns false
  }

  @Nested
  inner class StoreMedia {
    @Test
    fun `stores photo and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("photoWithDateAndGps.jpg"),
          capturedDate = LocalDate.of(2023, 5, 15),
          geolocation = point(-122.4194, 37.7749),
      )
    }

    @Test
    fun `stores photo and extracts date only when no GPS present`() {
      assertMediaFileEquals(
          storeMedia("photoWithDate.jpg"),
          capturedDate = LocalDate.of(2022, 12, 25),
          geolocation = null,
      )
    }

    @Test
    fun `stores photo and extracts GPS only when no date present`() {
      assertMediaFileEquals(
          storeMedia("photoWithGps.jpg"),
          capturedDate = LocalDate.EPOCH,
          geolocation = point(-74.006, 40.7128),
      )
    }

    @Test
    fun `stores photo with no metadata using defaults`() {

      assertMediaFileEquals(
          storeMedia("pixel.png", pngMetadata),
          capturedDate = LocalDate.EPOCH,
          geolocation = null,
      )
    }

    @Test
    fun `uses default captured date for corrupted image file`() {
      val corruptedData =
          byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // Incomplete JPEG header

      assertMediaFileEquals(
          storeMediaBytes(corruptedData),
          capturedDate = LocalDate.EPOCH,
          geolocation = null,
      )
    }

    @Test
    fun `throws exception if no permission to update project`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<ActivityNotFoundException> { storeMedia("pixel.png", pngMetadata) }
    }

    private fun assertMediaFileEquals(
        fileId: FileId,
        capturedDate: LocalDate,
        geolocation: Point? = null,
    ) {
      val row = activityMediaFilesDao.fetchOneByFileId(fileId)
      assertNotNull(row)

      assertEquals(
          ActivityMediaFilesRow(
              activityId = activityId,
              activityMediaTypeId = ActivityMediaType.Photo,
              fileId = fileId,
              isCoverPhoto = false,
              capturedDate = capturedDate,
          ),
          row.copy(geolocation = null),
      )

      if (geolocation != null) {
        assertGeometryEquals(geolocation, row.geolocation)
      } else {
        assertNull(row.geolocation, "Geolocation")
      }
    }
  }

  private fun storeMedia(
      filename: String,
      metadata: NewFileMetadata = jpegMetadata,
      activityId: ActivityId = this.activityId,
  ): FileId {
    return service.storeMedia(
        activityId,
        javaClass.getResourceAsStream("/file/$filename"),
        metadata,
    )
  }

  private fun storeMediaBytes(
      content: ByteArray,
      metadata: NewFileMetadata = jpegMetadata,
  ): FileId {
    return service.storeMedia(activityId, content.inputStream(), metadata)
  }
}
