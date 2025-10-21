package com.terraformation.backend.file

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.TokenNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.records.FileAccessTokensRecord
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.FILE_ACCESS_TOKENS
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.UnsupportedMediaTypeException

class FileServiceTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val eventPublisher = TestEventPublisher()
  private lateinit var fileStore: FileStore
  private lateinit var pathGenerator: PathGenerator
  private lateinit var fileService: FileService
  private val random: Random = mockk()

  override val user: TerrawareUser = mockUser()

  private lateinit var photoPath: Path
  private lateinit var photoStorageUrl: URI
  private lateinit var tempDir: Path

  private val contentType = MediaType.IMAGE_JPEG_VALUE
  private val filename = "test-photo.jpg"
  private val uploadedTime = ZonedDateTime.of(2021, 2, 3, 4, 5, 6, 0, ZoneOffset.UTC).toInstant()
  private val metadata = FileMetadata.of(contentType, filename, 1L)

  @BeforeEach
  fun setUp() {
    tempDir = Files.createTempDirectory(javaClass.simpleName)

    clock.instant = uploadedTime
    every { config.photoDir } returns tempDir
    every { config.keepInvalidUploads } returns false

    every { random.nextLong() } returns 0x0123456789abcdef
    pathGenerator = PathGenerator(random)
    fileStore = LocalFileStore(config, pathGenerator)

    val relativePath = Path("2021", "02", "03", "category", "040506-0123456789ABCDEF.jpg")

    photoPath = tempDir.resolve(relativePath)
    photoStorageUrl = URI("file:///${relativePath.invariantSeparatorsPathString}")

    fileService = FileService(dslContext, clock, config, eventPublisher, filesDao, fileStore)
  }

  @AfterEach
  fun deleteTemporaryDirectory() {
    assertTrue(tempDir.toFile().deleteRecursively(), "Deleting temporary directory")
  }

  @Test
  fun `storeFile writes file and database row`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(10)
    val size = photoData.size.toLong()
    val geolocation = point(1)
    var insertedChildRow = false

    val fileId =
        fileService.storeFile(
            "category",
            photoData.inputStream(),
            metadata.copy(geolocation = geolocation, size = size),
        ) {
          insertedChildRow = true
        }

    val expectedPhoto =
        FilesRow(
            contentType = contentType,
            fileName = filename,
            geolocation = geolocation,
            id = fileId,
            storageUrl = photoStorageUrl,
            size = size,
            createdBy = user.userId,
            createdTime = uploadedTime,
            modifiedBy = user.userId,
            modifiedTime = uploadedTime,
        )

    assertTrue(Files.exists(photoPath), "Photo file $photoPath exists")
    assertArrayEquals(photoData, Files.readAllBytes(photoPath), "File contents")

    assertTrue(insertedChildRow, "Called function to insert child row")

    val actualPhoto = filesDao.fetchOneById(fileId)!!
    assertEquals(expectedPhoto, actualPhoto)
  }

  @Test
  fun `storeFile deletes file if database insert fails`() {
    assertThrows(DuplicateKeyException::class.java) {
      fileService.storeFile("category", ByteArray(0).inputStream(), metadata) {
        throw DuplicateKeyException("something failed, oh no")
      }
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile deletes file if contents can't be read from input stream`() {
    val badStream =
        object : InputStream() {
          override fun read(): Int {
            throw SocketTimeoutException()
          }
        }

    assertThrows<SocketTimeoutException> {
      fileService.storeFile("category", badStream, metadata) {}
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile deletes file if validation fails`() {
    assertThrows<UnsupportedMediaTypeException> {
      fileService.storeFile(
          "category",
          ByteArray(0).inputStream(),
          metadata,
          { throw UnsupportedMediaTypeException("validation failed") },
      ) {}
    }

    assertFalse(Files.exists(photoPath), "File should not exist")
  }

  @Test
  fun `storeFile keeps file if validation fails and keepInvalidUploads config is set`() {
    every { config.keepInvalidUploads } returns true

    assertThrows<UnsupportedMediaTypeException> {
      fileService.storeFile(
          "category",
          ByteArray(0).inputStream(),
          metadata,
          { throw UnsupportedMediaTypeException("validation failed") },
      ) {}
    }

    assertTrue(Files.exists(photoPath), "File should still exist")
  }

  @Test
  fun `storeFile throws exception if directory cannot be created`() {
    // Directory creation will fail if a path element already exists and is not a directory.
    Files.createDirectories(photoPath.parent.parent)
    Files.createFile(photoPath.parent)

    assertThrows(IOException::class.java) {
      fileService.storeFile("category", ByteArray(0).inputStream(), metadata) {}
    }
  }

  @Test
  fun `readFile reads existing photo file`() {
    val photoData = Random(System.currentTimeMillis()).nextBytes(1000)

    val fileId = fileService.storeFile("category", photoData.inputStream(), metadata) {}

    val stream = fileService.readFile(fileId)

    assertEquals(MediaType.parseMediaType(metadata.contentType), stream.contentType, "Content type")
    assertArrayEquals(photoData, stream.readAllBytes())
  }

  @Test
  fun `readFile throws exception on nonexistent file`() {
    assertThrows(FileNotFoundException::class.java) { fileService.readFile(FileId(123)) }
  }

  @Nested
  inner class FileReferenceDeletedEventHandler {
    @Test
    fun `deletes file from file store and publishes event to trigger thumbnail deletion`() {
      val photoData = Random.nextBytes(10)

      fileService.storeFile(
          "category",
          photoData.inputStream(),
          metadata.copy(filename = "1.jpg"),
      ) {}

      val expectedPhotos = filesDao.findAll()

      every { random.nextLong() } returns 2L
      val fileIdToDelete =
          fileService.storeFile(
              "category",
              photoData.inputStream(),
              metadata.copy(filename = "2.jpg"),
          ) {}

      val photoUrlToDelete = filesDao.fetchOneById(fileIdToDelete)!!.storageUrl!!

      fileService.on(FileReferenceDeletedEvent(fileIdToDelete))

      eventPublisher.assertEventPublished(FileDeletionStartedEvent(fileIdToDelete, "image/jpeg"))

      assertThrows<NoSuchFileException>("$photoUrlToDelete should be deleted") {
        fileStore.size(photoUrlToDelete)
      }

      assertEquals(expectedPhotos, filesDao.findAll(), "Photos")
    }

    @Test
    fun `does not delete file from file store if there are still references`() {
      insertOrganization()
      insertProject()
      insertActivity()
      val fileId =
          fileService.storeFile("category", Random.nextBytes(1).inputStream(), metadata) {
            insertActivityMediaFile(fileId = it)
          }
      val filesTableBefore = dslContext.fetch(FILES)
      val storageUrl = filesTableBefore.first().storageUrl!!

      fileService.on(FileReferenceDeletedEvent(fileId))

      eventPublisher.assertEventNotPublished<FileDeletionStartedEvent>()
      assertEquals(1L, fileStore.size(storageUrl))
      assertTableEquals(filesTableBefore)
    }

    @Test
    fun `ignores references that should not prevent file deletion`() {
      val photoData = Random.nextBytes(10)

      every { random.nextLong() } returns 2L
      val fileIdToDelete =
          fileService.storeFile(
              "category",
              photoData.inputStream(),
              metadata.copy(filename = "2.jpg"),
          ) {}

      dslContext.batchInsert(
          FileAccessTokensRecord("token", fileIdToDelete, user.userId, Instant.EPOCH, Instant.EPOCH)
      )

      val urlToDelete = filesDao.fetchOneById(fileIdToDelete)!!.storageUrl!!

      fileService.on(FileReferenceDeletedEvent(fileIdToDelete))

      eventPublisher.assertEventPublished(FileDeletionStartedEvent(fileIdToDelete, "image/jpeg"))

      assertThrows<NoSuchFileException>("$urlToDelete should be deleted") {
        fileStore.size(urlToDelete)
      }

      assertTableEmpty(FILES)
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<FileReferenceDeletedEvent>(fileService)
    }
  }

  @Nested
  inner class CreateToken {
    @Test
    fun `inserts expected values`() {
      clock.instant = Instant.ofEpochSecond(100000)
      val expiration = Duration.ofSeconds(321)

      val fileId = insertFile()

      val token = fileService.createToken(fileId, expiration)

      assertTableEquals(
          FileAccessTokensRecord(
              createdBy = currentUser().userId,
              createdTime = clock.instant,
              expiresTime = clock.instant + expiration,
              fileId = fileId,
              token = token,
          )
      )
    }

    @Test
    fun `throws exception if file does not exist`() {
      assertThrows<FileNotFoundException> {
        fileService.createToken(FileId(-1), Duration.ofMinutes(1))
      }
    }
  }

  @Nested
  inner class ReadFileForToken {
    @Test
    fun `returns contents for valid token`() {
      val fileData = Random.nextBytes(10)

      val fileId = fileService.storeFile("category", fileData.inputStream(), metadata) {}
      val token = fileService.createToken(fileId, Duration.ofMinutes(1))

      val contentsFromToken = fileService.readFileForToken(token).use { it.readAllBytes() }

      assertArrayEquals(fileData, contentsFromToken)
    }

    @Test
    fun `throws exception if token has expired`() {
      val fileId = insertFile()
      val token = fileService.createToken(fileId, Duration.ofMinutes(30))

      clock.instant += Duration.ofMinutes(30)

      assertThrows<TokenNotFoundException> { fileService.readFileForToken(token) }
    }
  }

  @Test
  fun `handler for DailyTaskTimeArrivedEvent prunes expired tokens`() {
    val fileId = insertFile()
    fileService.createToken(fileId, Duration.ofMinutes(10))
    fileService.createToken(fileId, Duration.ofMinutes(20))
    val token3 = fileService.createToken(fileId, Duration.ofMinutes(30))
    val token4 = fileService.createToken(fileId, Duration.ofMinutes(40))

    clock.instant += Duration.ofMinutes(20)

    fileService.on(DailyTaskTimeArrivedEvent())

    assertSetEquals(
        setOf(token3, token4),
        dslContext.fetch(FILE_ACCESS_TOKENS).map { it.token }.toSet(),
        "Remaining tokens",
    )

    assertIsEventListener<DailyTaskTimeArrivedEvent>(fileService)
  }
}
