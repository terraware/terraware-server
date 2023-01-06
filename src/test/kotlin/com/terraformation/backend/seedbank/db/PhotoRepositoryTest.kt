package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.PhotosRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.LocalFileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.file.PhotoService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class PhotoRepositoryTest : DatabaseTest(), RunsAsUser {
  private lateinit var accessionStore: AccessionStore
  private val config: TerrawareServerConfig = mockk()
  private lateinit var fileStore: FileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var photoService: PhotoService
  private val random: Random = mockk()
  private lateinit var repository: PhotoRepository
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoPath: Path
  private lateinit var photoStorageUrl: URI
  private lateinit var tempDir: Path

  private val accessionId = AccessionId(12345)
  private val accessionNumber = "ZYXWVUTSRQPO"
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = PhotoMetadata(filename, contentType, 1L)
  private val clock = TestClock(uploadedTime)

  @BeforeEach
  fun setUp() {
    accessionStore =
        AccessionStore(
            dslContext,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            clock,
            mockk(),
            mockk(),
        )

    tempDir = Files.createTempDirectory(javaClass.simpleName)

    every { config.photoDir } returns tempDir
    every { config.photoIntermediateDepth } returns 3

    every { random.nextLong() } returns 0x0123456789abcdef
    pathGenerator = PathGenerator(random)
    fileStore = LocalFileStore(config, pathGenerator)

    val relativePath = Path("2021", "02", "03", "accession", "040506-0123456789ABCDEF.jpg")

    photoPath = tempDir.resolve(relativePath)
    photoStorageUrl = URI("file:///${relativePath.invariantSeparatorsPathString}")

    every { user.canReadAccession(any()) } returns true
    every { user.canUpdateAccession(any()) } returns true

    photoService = PhotoService(dslContext, clock, fileStore, photosDao, thumbnailStore)
    repository = PhotoRepository(accessionPhotosDao, dslContext, photoService)

    insertSiteData()
    insertAccession(id = accessionId, number = accessionNumber)
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storePhoto writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)

    repository.storePhoto(accessionId, photoData.inputStream(), photoData.size.toLong(), metadata)

    val expectedAccessionPhoto = AccessionPhotosRow(accessionId = accessionId)

    assertTrue(Files.exists(photoPath), "Photo file $photoPath exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    val actualAccessionPhoto = accessionPhotosDao.fetchByAccessionId(accessionId).first()
    assertEquals(expectedAccessionPhoto, actualAccessionPhoto.copy(photoId = null))
  }

  @Test
  fun `storePhoto throws exception if user does not have permission to update accession`() {
    every { user.canUpdateAccession(accessionId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), 0, metadata)
    }
  }

  @Test
  fun `storePhoto does not insert database row if file exists`() {
    Files.createDirectories(photoPath.parent)
    Files.createFile(photoPath)

    assertThrows(FileAlreadyExistsException::class.java) {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), 0, metadata)
    }

    val photosWritten = accessionPhotosDao.findAll()
    assertEquals(0, photosWritten.size, "Should be 0 photos in database")

    assertTrue(Files.exists(photoPath), "Existing file should not be removed")
  }

  @Test
  fun `readPhoto reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    repository.storePhoto(accessionId, photoData.inputStream(), photoData.size.toLong(), metadata)

    val stream = repository.readPhoto(accessionId, filename)

    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readPhoto throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) { repository.readPhoto(accessionId, filename) }
  }

  @Test
  fun `readPhoto throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId) } returns false

    assertThrows(AccessionNotFoundException::class.java) {
      repository.readPhoto(accessionId, filename)
    }
  }

  @Test
  fun `readPhoto returns thumbnail if photo dimensions are specified`() {
    val photoData = Random.nextBytes(10)
    val thumbnailData = Random.nextBytes(10)
    val thumbnailStream =
        SizedInputStream(ByteArrayInputStream(thumbnailData), thumbnailData.size.toLong())
    val width = 123
    val height = 456

    repository.storePhoto(accessionId, photoData.inputStream(), photoData.size.toLong(), metadata)
    val photoId = photosDao.findAll().first().id!!

    every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

    val stream = repository.readPhoto(accessionId, filename, width, height)

    verify { thumbnailStore.getThumbnailData(photoId, width, height) }

    assertArrayEquals(thumbnailData, stream.readAllBytes())
  }

  @Test
  fun `deleteAllPhotos deletes multiple photos`() {
    val photoData = Random.nextBytes(10)

    every { thumbnailStore.deleteThumbnails(any()) } just Runs

    every { random.nextLong() } returns 1L
    repository.storePhoto(
        accessionId,
        photoData.inputStream(),
        photoData.size.toLong(),
        metadata.copy(filename = "1.jpg"))

    every { random.nextLong() } returns 2L
    repository.storePhoto(
        accessionId,
        photoData.inputStream(),
        photoData.size.toLong(),
        metadata.copy(filename = "2.jpg"))

    val photoRows = photosDao.findAll()
    val photoIds = photoRows.mapNotNull { it.id }
    val photoUrls = photoRows.mapNotNull { it.storageUrl }

    repository.deleteAllPhotos(accessionId)

    photoIds.forEach { verify { thumbnailStore.deleteThumbnails(it) } }
    photoUrls.forEach { url ->
      assertThrows<NoSuchFileException>("$url should be deleted") { fileStore.size(url) }
    }

    assertEquals(emptyList<AccessionPhotosRow>(), accessionPhotosDao.findAll(), "Accession photos")
    assertEquals(emptyList<PhotosRow>(), photosDao.findAll(), "Photos")
  }

  @Test
  fun `OrganizationDeletionStartedEvent listener deletes photos from all facilities in organization`() {
    val sameOrgFacilityId = FacilityId(2)
    val sameOrgAccessionId = AccessionId(2)
    val otherOrganizationId = OrganizationId(2)
    val otherOrgFacilityId = FacilityId(3)
    val otherOrgAccessionId = AccessionId(3)

    insertOrganization(otherOrganizationId)
    insertFacility(sameOrgFacilityId)
    insertFacility(otherOrgFacilityId, otherOrganizationId)
    insertAccession(id = sameOrgAccessionId, facilityId = sameOrgFacilityId)
    insertAccession(id = otherOrgAccessionId, facilityId = otherOrgFacilityId)

    listOf(accessionId, sameOrgAccessionId, otherOrgAccessionId).forEach { photoAccessionId ->
      val photosRow =
          PhotosRow(
              contentType = contentType,
              fileName = "$photoAccessionId",
              storageUrl = URI("file:///$photoAccessionId"),
              size = 1,
              createdBy = user.userId,
              createdTime = uploadedTime,
              modifiedBy = user.userId,
              modifiedTime = uploadedTime)

      photosDao.insert(photosRow)
      accessionPhotosDao.insert(
          AccessionPhotosRow(accessionId = photoAccessionId, photoId = photosRow.id))
    }

    // deleteAllPhotos is tested separately; we just care that it's called for each accession.
    val spyRepository = spyk(repository)
    every { spyRepository.deleteAllPhotos(any()) } just Runs

    spyRepository.on(OrganizationDeletionStartedEvent(organizationId))

    assertIsEventListener<OrganizationDeletionStartedEvent>(repository)

    verify { spyRepository.deleteAllPhotos(accessionId) }
    verify { spyRepository.deleteAllPhotos(sameOrgAccessionId) }
    verify(exactly = 0) { spyRepository.deleteAllPhotos(otherOrgAccessionId) }
  }
}
