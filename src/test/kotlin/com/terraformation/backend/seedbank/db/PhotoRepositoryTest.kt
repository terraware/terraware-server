package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.onePixelPng
import com.terraformation.backend.util.ImageUtils
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class PhotoRepositoryTest : DatabaseTest(), RunsAsUser {
  private lateinit var accessionStore: AccessionStore
  private lateinit var fileStore: InMemoryFileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var fileService: FileService
  private val random: Random = mockk()
  private lateinit var repository: PhotoRepository
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoStorageUrl: URI

  private val accessionId = AccessionId(12345)
  private val accessionNumber = "ZYXWVUTSRQPO"
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = FileMetadata.of(contentType, filename, 1L)
  private val clock = TestClock(uploadedTime)

  private val sixPixelPng: ByteArray by lazy {
    javaClass.getResourceAsStream("/file/sixPixels.png").use { it.readAllBytes() }
  }

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
            mockk(),
            clock,
            TestEventPublisher(),
            mockk(),
            mockk(),
        )

    every { random.nextLong() } returns 0x0123456789abcdef
    pathGenerator = PathGenerator(random)
    fileStore = InMemoryFileStore(pathGenerator)

    val relativePath = Path("2021", "02", "03", "accession", "040506-0123456789ABCDEF.jpg")

    photoStorageUrl = URI("file:///${relativePath.invariantSeparatorsPathString}")

    every { user.canReadAccession(any()) } returns true
    every { user.canUploadPhoto(any()) } returns true

    fileService = FileService(dslContext, clock, filesDao, fileStore, thumbnailStore)
    repository = PhotoRepository(accessionPhotosDao, dslContext, fileService, ImageUtils(fileStore))

    insertSiteData()
    insertAccession(id = accessionId, number = accessionNumber)
  }

  @Test
  fun `storePhoto writes file and database row`() {
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata)
    fileStore.assertFileExists(photoStorageUrl)

    val actualPhotoData = fileStore.read(photoStorageUrl)
    assertArrayEquals(onePixelPng, actualPhotoData.readAllBytes(), "File contents")

    val expectedAccessionPhoto = AccessionPhotosRow(accessionId = accessionId)
    val actualAccessionPhoto = accessionPhotosDao.fetchByAccessionId(accessionId).first()
    assertEquals(expectedAccessionPhoto, actualAccessionPhoto.copy(fileId = null))
  }

  @Test
  fun `storePhoto throws exception if user does not have permission to upload photos`() {
    every { user.canUploadPhoto(accessionId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), metadata)
    }
  }

  @Test
  fun `storePhoto replaces existing photo with same filename`() {
    every { thumbnailStore.deleteThumbnails(any()) } just Runs

    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata)
    every { random.nextLong() } returns 1
    repository.storePhoto(accessionId, sixPixelPng.inputStream(), metadata)

    fileStore.assertFileNotExists(photoStorageUrl, "Earlier photo file should have been deleted")
    assertEquals(1, accessionPhotosDao.fetchByAccessionId(accessionId).size, "Number of photos")

    val stream = repository.readPhoto(accessionId, filename)

    assertArrayEquals(sixPixelPng, stream.readAllBytes())
  }

  @Test
  fun `readPhoto reads newest existing photo file`() {
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata)
    every { random.nextLong() } returns 1
    repository.storePhoto(accessionId, sixPixelPng.inputStream(), metadata.copy(filename = "dupe"))

    filesDao.update(filesDao.findAll().map { it.copy(fileName = filename) })

    val stream = repository.readPhoto(accessionId, filename)

    assertArrayEquals(sixPixelPng, stream.readAllBytes())
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
    val thumbnailData = Random.nextBytes(10)
    val thumbnailStream =
        SizedInputStream(ByteArrayInputStream(thumbnailData), thumbnailData.size.toLong())
    val width = 123
    val height = 456

    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata)
    val fileId = filesDao.findAll().first().id!!

    every { thumbnailStore.getThumbnailData(any(), any(), any()) } returns thumbnailStream

    val stream = repository.readPhoto(accessionId, filename, width, height)

    verify { thumbnailStore.getThumbnailData(fileId, width, height) }

    assertArrayEquals(thumbnailData, stream.readAllBytes())
  }

  @Test
  fun `listPhotos does not return duplicate filenames`() {
    every { random.nextLong() } returns 1
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "1"))
    every { random.nextLong() } returns 2
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "2"))
    every { random.nextLong() } returns 3
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "3"))

    filesDao.update(filesDao.findAll().map { it.copy(fileName = "1") })

    assertEquals(1, repository.listPhotos(accessionId).size, "Number of photos")
  }

  @Test
  fun `deleteAllPhotos deletes multiple photos`() {
    every { thumbnailStore.deleteThumbnails(any()) } just Runs
    every { user.canUpdateAccession(any()) } returns true

    every { random.nextLong() } returns 1L
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "1.jpg"))

    every { random.nextLong() } returns 2L
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "2.jpg"))

    val photoRows = filesDao.findAll()
    val fileIds = photoRows.mapNotNull { it.id }
    val photoUrls = photoRows.mapNotNull { it.storageUrl }

    repository.deleteAllPhotos(accessionId)

    fileIds.forEach { verify { thumbnailStore.deleteThumbnails(it) } }
    photoUrls.forEach { url ->
      assertThrows<NoSuchFileException>("$url should be deleted") { fileStore.size(url) }
    }

    assertEquals(emptyList<AccessionPhotosRow>(), accessionPhotosDao.findAll(), "Accession photos")
    assertEquals(emptyList<FilesRow>(), filesDao.findAll(), "Photos")
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
      val filesRow =
          FilesRow(
              contentType = contentType,
              fileName = "$photoAccessionId",
              storageUrl = URI("file:///$photoAccessionId"),
              size = 1,
              createdBy = user.userId,
              createdTime = uploadedTime,
              modifiedBy = user.userId,
              modifiedTime = uploadedTime)

      filesDao.insert(filesRow)
      accessionPhotosDao.insert(
          AccessionPhotosRow(accessionId = photoAccessionId, fileId = filesRow.id))
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
