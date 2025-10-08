package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class PhotoRepositoryTest : DatabaseTest(), RunsAsUser {
  private lateinit var accessionStore: AccessionStore
  private val eventPublisher = TestEventPublisher()
  private lateinit var fileStore: InMemoryFileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var fileService: FileService
  private val random: Random = mockk()
  private lateinit var repository: PhotoRepository
  private lateinit var thumbnailService: ThumbnailService
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private val accessionNumber = "ZYXWVUTSRQPO"
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = FileMetadata.of(contentType, filename, 1L)
  private val clock = TestClock(uploadedTime)

  private val sixPixelPng: ByteArray by lazy {
    javaClass.getResourceAsStream("/file/sixPixels.png").use { it.readAllBytes() }
  }

  private lateinit var accessionId: AccessionId
  private lateinit var organizationId: OrganizationId
  private lateinit var photoStorageUrl: URI

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
            eventPublisher,
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

    fileService = FileService(dslContext, clock, mockk(), eventPublisher, filesDao, fileStore)
    thumbnailService = ThumbnailService(dslContext, fileService, mockk(), thumbnailStore)
    repository =
        PhotoRepository(
            accessionPhotosDao,
            dslContext,
            eventPublisher,
            fileService,
            ImageUtils(fileStore),
            thumbnailService,
        )

    organizationId = insertOrganization()
    insertFacility()
    accessionId = insertAccession(number = accessionNumber)
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

    assertThrows<AccessDeniedException> {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), metadata)
    }
  }

  @Test
  fun `storePhoto replaces existing photo with same filename`() {
    val oldFileId = repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata)
    every { random.nextLong() } returns 1
    repository.storePhoto(accessionId, sixPixelPng.inputStream(), metadata)

    eventPublisher.assertEventPublished(FileReferenceDeletedEvent(oldFileId))
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
    assertThrows<NoSuchFileException> { repository.readPhoto(accessionId, filename) }
  }

  @Test
  fun `readPhoto throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId) } returns false

    assertThrows<AccessionNotFoundException> { repository.readPhoto(accessionId, filename) }
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

    every { thumbnailStore.canGenerateThumbnails(metadata.contentType) } returns true
    every { thumbnailStore.getThumbnailData(fileId, any(), any()) } returns thumbnailStream

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
  fun `deletePhoto deletes one photo`() {
    every { user.canUpdateAccession(any()) } returns true

    every { random.nextLong() } returns 1L
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "1.jpg"))

    every { random.nextLong() } returns 2L
    repository.storePhoto(accessionId, sixPixelPng.inputStream(), metadata.copy(filename = "6.jpg"))

    val photoRows = filesDao.findAll()

    val onePixelPhotoRow = photoRows.find { it.fileName == "1.jpg" }!!
    val sixPixelPhotoRow = photoRows.find { it.fileName == "6.jpg" }!!

    val onePixelFileId = onePixelPhotoRow.id!!
    val sixPixelFileId = sixPixelPhotoRow.id!!

    repository.deletePhoto(accessionId, "1.jpg")

    eventPublisher.assertExactEventsPublished(setOf(FileReferenceDeletedEvent(onePixelFileId)))
    assertEquals(
        listOf(AccessionPhotosRow(accessionId, sixPixelFileId)),
        accessionPhotosDao.findAll(),
        "Accession photos after deletion",
    )
  }

  @Test
  fun `deletePhoto throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId) } returns false

    assertThrows<AccessionNotFoundException> { repository.deletePhoto(accessionId, filename) }
  }

  @Test
  fun `deleteAllPhotos deletes multiple photos`() {
    every { user.canUpdateAccession(any()) } returns true

    every { random.nextLong() } returns 1L
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "1.jpg"))

    every { random.nextLong() } returns 2L
    repository.storePhoto(accessionId, onePixelPng.inputStream(), metadata.copy(filename = "2.jpg"))

    val photoRows = filesDao.findAll()
    val fileIds = photoRows.mapNotNull { it.id }

    repository.deleteAllPhotos(accessionId)

    eventPublisher.assertEventsPublished(fileIds.map { FileReferenceDeletedEvent(it) }.toSet())

    assertTableEmpty(ACCESSION_PHOTOS)
  }

  @Test
  fun `deleteAllPhotos throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId) } returns false

    assertThrows<AccessionNotFoundException> { repository.deleteAllPhotos(accessionId) }
  }

  @Test
  fun `OrganizationDeletionStartedEvent listener deletes photos from all facilities in organization`() {
    insertFacility()
    val sameOrgAccessionId = insertAccession()
    insertOrganization()
    insertFacility()
    val otherOrgAccessionId = insertAccession()

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
              modifiedTime = uploadedTime,
          )

      filesDao.insert(filesRow)
      accessionPhotosDao.insert(
          AccessionPhotosRow(accessionId = photoAccessionId, fileId = filesRow.id)
      )
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
