package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.mercatorPoint
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.LocalFileStore
import com.terraformation.backend.file.PathGenerator
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.model.PhotoMetadata
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.random.Random
import net.postgis.jdbc.geometry.Point
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

class PhotoRepositoryTest : DatabaseTest(), RunsAsUser {
  private lateinit var accessionStore: AccessionStore
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private lateinit var fileStore: FileStore
  private lateinit var pathGenerator: PathGenerator
  private val random: Random = mockk()
  private lateinit var repository: PhotoRepository
  private val thumbnailStore: ThumbnailStore = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoPath: Path
  private lateinit var photoStorageUrl: URI
  private lateinit var tempDir: Path

  private val accessionId = AccessionId(12345)
  private val accessionNumber = "ZYXWVUTSRQPO"
  private val capturedTime = Instant.ofEpochMilli(1000)
  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val facilityId = FacilityId(100)
  private val filename = "test-photo.jpg"
  private val latitude = 23.456
  private val longitude = 76.5432
  private val location = Point(longitude, latitude, 0.0).apply { srid = SRID.LONG_LAT }
  private val accuracy = 50
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = PhotoMetadata(filename, contentType, capturedTime, 1L, location, accuracy)

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
            mockk(),
            clock,
        )

    tempDir = Files.createTempDirectory(javaClass.simpleName)

    every { clock.instant() } returns uploadedTime
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

    repository =
        PhotoRepository(accessionPhotosDao, dslContext, clock, fileStore, photosDao, thumbnailStore)

    insertSiteData()
    accessionsDao.insert(
        AccessionsRow(
            id = accessionId,
            number = accessionNumber,
            facilityId = facilityId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            stateId = AccessionState.Pending))
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storePhoto writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)

    repository.storePhoto(accessionId, photoData.inputStream(), photoData.size.toLong(), metadata)

    val expectedPhoto =
        PhotosRow(
            capturedTime = capturedTime,
            contentType = contentType,
            fileName = filename,
            storageUrl = photoStorageUrl,
            gpsHorizAccuracy = accuracy.toDouble(),
            size = photoData.size.toLong(),
            createdBy = user.userId,
            createdTime = uploadedTime,
            modifiedBy = user.userId,
            modifiedTime = uploadedTime)
    val expectedAccessionPhoto = AccessionPhotosRow(accessionId = accessionId)

    assertTrue(Files.exists(photoPath), "Photo file $photoPath exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    val actualAccessionPhoto = accessionPhotosDao.fetchByAccessionId(accessionId).first()
    assertEquals(expectedAccessionPhoto, actualAccessionPhoto.copy(photoId = null))

    val actualPhoto = photosDao.fetchOneById(actualAccessionPhoto.photoId!!)!!
    assertEquals(expectedPhoto, actualPhoto.copy(id = null, location = null))

    // We'll get the coordinates back in spherical Mercator, not long/lat.
    // select st_asewkt(st_transform(st_setsrid('point(76.5432 23.456 0)'::geometry, 4326), 3857));
    // SRID=3857;POINT(8520750.047687698 2687258.0669861087 0)
    val actualLocation = actualPhoto.location!! as Point
    assertEquals(8520750.047687698, actualLocation.x, 0.00001, "Location X")
    assertEquals(2687258.0669861087, actualLocation.y, 0.00001, "Location Y")
    assertEquals(0.0, actualLocation.z, "Location Z")
  }

  @Test
  fun `storePhoto deletes file if database insert fails`() {
    val photosRow =
        PhotosRow(
            capturedTime = capturedTime,
            contentType = contentType,
            fileName = filename,
            storageUrl = URI("file:///$filename"),
            location = mercatorPoint(1.0, 2.0, 3.0),
            size = 1,
            createdBy = user.userId,
            createdTime = uploadedTime,
            modifiedBy = user.userId,
            modifiedTime = uploadedTime)
    photosDao.insert(photosRow)

    accessionPhotosDao.insert(AccessionPhotosRow(accessionId = accessionId, photoId = photosRow.id))

    // Filename is not required to be unique normally, but it's an easy way to force a failure here.
    dslContext.execute("CREATE UNIQUE INDEX ON photos (file_name)")

    assertThrows(DuplicateKeyException::class.java) {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), 0, metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto deletes file if contents can't be read from input stream`() {
    val badStream =
        object : InputStream() {
          override fun read(): Int {
            throw SocketTimeoutException()
          }
        }

    assertThrows<SocketTimeoutException> {
      repository.storePhoto(accessionId, badStream, 1000, metadata)
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storePhoto throws exception if user does not have permission to update accession`() {
    every { user.canUpdateAccession(accessionId) } returns false

    assertThrows(AccessDeniedException::class.java) {
      repository.storePhoto(accessionId, ByteArray(0).inputStream(), 0, metadata)
    }
  }

  @Test
  fun `storePhoto throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
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
  fun `getPhotoFileSize returns size of existing photo`() {
    val expectedSize = 17
    val photoData = ByteArray(expectedSize)

    repository.storePhoto(accessionId, photoData.inputStream(), expectedSize.toLong(), metadata)

    assertEquals(expectedSize.toLong(), repository.getPhotoFileSize(accessionId, filename))
  }

  @Test
  fun `getPhotoFileSize throws exception on nonexistent file`() {
    assertThrows(NoSuchFileException::class.java) {
      repository.getPhotoFileSize(accessionId, filename)
    }
  }

  @Test
  fun `getPhotoFileSize throws exception if user does not have permission to read accession`() {
    every { user.canReadAccession(accessionId) } returns false

    assertThrows(AccessionNotFoundException::class.java) {
      repository.getPhotoFileSize(accessionId, filename)
    }
  }
}
