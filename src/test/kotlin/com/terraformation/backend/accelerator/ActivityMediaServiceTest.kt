package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.VideoStreamNotFoundException
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.file.event.VideoFileDeletedEvent
import com.terraformation.backend.file.event.VideoFileUploadedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.point
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val muxService: MuxService = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(
        dslContext,
        clock,
        config,
        eventPublisher,
        filesDao,
        fileStore,
    )
  }
  private val thumbnailService: ThumbnailService by lazy {
    ThumbnailService(dslContext, fileService, muxService, thumbnailStore)
  }
  private val service: ActivityMediaService by lazy {
    ActivityMediaService(
        ActivityMediaStore(clock, dslContext, eventPublisher),
        clock,
        eventPublisher,
        fileService,
        muxService,
        ParentStore(dslContext),
        thumbnailService,
    )
  }

  private val jpegMetadata = NewFileMetadata("image/jpeg", "test.jpg", null, 1000L, null)
  private val mp4Metadata = NewFileMetadata("video/mp4", "test.mp4", null, 262L, null)
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
    fun `stores iPhone video and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("videoIphone.mov"),
          capturedDate = LocalDate.of(2024, 6, 15),
          geolocation = point(-122.4194, 37.7749),
          type = ActivityMediaType.Video,
      )
    }

    @Test
    fun `stores Android video and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("videoAndroid.mp4"),
          capturedDate = LocalDate.of(2024, 6, 15),
          geolocation = point(-122.4194, 37.7749),
          type = ActivityMediaType.Video,
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
    fun `stores video with no metadata using defaults`() {
      assertMediaFileEquals(
          storeMedia("videoHeaderOnly.mp4", mp4Metadata),
          capturedDate = LocalDate.EPOCH,
          geolocation = null,
          type = ActivityMediaType.Video,
      )
    }

    @Test
    fun `adjusts existing list positions if requested position conflicts with them`() {
      val fileId1 = storeMedia("pixel.jpg")
      val fileId2 = storeMedia("pixel.jpg")
      val fileId3 = storeMedia("pixel.jpg", listPosition = 1)

      assertListPositions(listOf(fileId3, fileId1, fileId2))
    }

    @Test
    fun `puts new file in last position if requested position is greater than list size`() {
      val fileId1 = storeMedia("pixel.jpg")
      val fileId2 = storeMedia("pixel.jpg", listPosition = 5000)

      assertListPositions(listOf(fileId1, fileId2))
    }

    @Test
    fun `puts new file in first position if requested position is less than 1`() {
      val fileId1 = storeMedia("pixel.jpg")
      val fileId2 = storeMedia("pixel.jpg", listPosition = 0)

      assertListPositions(listOf(fileId2, fileId1))
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
    fun `publishes event when video is uploaded`() {
      val fileId = storeMedia("videoAndroid.mp4", mp4Metadata)

      eventPublisher.assertEventPublished(VideoFileUploadedEvent(fileId))
    }

    @Test
    fun `does not publish video uploaded event when photo is uploaded`() {
      storeMedia("pixel.png", pngMetadata)

      eventPublisher.assertEventNotPublished<VideoFileUploadedEvent>()
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
        type: ActivityMediaType = ActivityMediaType.Photo,
    ) {
      val row = activityMediaFilesDao.fetchOneByFileId(fileId)
      assertNotNull(row)

      assertEquals(
          ActivityMediaFilesRow(
              activityId = activityId,
              activityMediaTypeId = type,
              capturedDate = capturedDate,
              fileId = fileId,
              isCoverPhoto = false,
              isHiddenOnMap = false,
              listPosition = 1,
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

  @Nested
  inner class ReadMedia {
    @Test
    fun `returns media data for correct activity`() {
      val testContent = javaClass.getResourceAsStream("/file/pixel.png").use { it.readAllBytes() }
      val fileId = storeMediaBytes(testContent)

      val inputStream = service.readMedia(activityId, fileId)
      assertArrayEquals(testContent, inputStream.readAllBytes(), "File content")
    }

    @Test
    fun `returns thumbnail data when dimensions specified`() {
      val thumbnailContent = Random.nextBytes(25)
      val fileId = storeMedia("pixel.png", pngMetadata)
      val maxWidth = 100
      val maxHeight = 100

      every { thumbnailService.readFile(fileId, maxWidth, maxHeight) } returns
          SizedInputStream(thumbnailContent.inputStream(), 25L)

      val inputStream = service.readMedia(activityId, fileId, maxWidth, maxHeight)
      assertArrayEquals(thumbnailContent, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `throws exception if file not associated with activity`() {
      val otherProjectId = insertProject()
      val otherActivityId = insertActivity(projectId = otherProjectId)
      val fileId = storeMedia("pixel.png", pngMetadata)

      assertThrows<FileNotFoundException> { service.readMedia(otherActivityId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read project`() {
      val fileId = storeMedia("pixel.png", pngMetadata)

      deleteOrganizationUser()

      assertThrows<ActivityNotFoundException> { service.readMedia(activityId, fileId) }
    }
  }

  @Nested
  inner class DeleteMedia {
    @Test
    fun `deletes media file and removes from storage`() {
      val fileId = storeMedia("pixel.png", pngMetadata)
      val storageUrl = filesDao.fetchOneById(fileId)!!.storageUrl!!

      service.deleteMedia(activityId, fileId)

      assertTableEmpty(FILES)
      assertEquals(emptyList<ActivityMediaFilesRow>(), activityMediaFilesDao.findAll())
      fileStore.assertFileWasDeleted(storageUrl)
      eventPublisher.assertEventNotPublished<VideoFileDeletedEvent>()
    }

    @Test
    fun `adjusts list positions of remaining files`() {
      val fileId1 = storeMedia("pixel.jpg")
      val fileId2 = storeMedia("pixel.jpg")
      val fileId3 = storeMedia("pixel.jpg")

      service.deleteMedia(activityId, fileId2)

      assertListPositions(listOf(fileId1, fileId3))
    }

    @Test
    fun `publishes video deleted event if file was a video`() {
      val fileId = storeMedia("videoAndroid.mp4", mp4Metadata)

      service.deleteMedia(activityId, fileId)

      eventPublisher.assertEventPublished(VideoFileDeletedEvent(fileId))
    }

    @Test
    fun `throws exception if file not associated with activity`() {
      val otherProjectId = insertProject()
      val otherActivityId = insertActivity(projectId = otherProjectId)
      val fileId = storeMedia("pixel.png", pngMetadata)

      assertThrows<FileNotFoundException> { service.deleteMedia(otherActivityId, fileId) }
    }

    @Test
    fun `throws exception if no permission to update project`() {
      val fileId = storeMedia("pixel.png", pngMetadata)

      deleteOrganizationUser()

      assertThrows<ActivityNotFoundException> { service.deleteMedia(activityId, fileId) }
    }
  }

  @Nested
  inner class GetMuxStreamInfo {
    @Test
    fun `returns stream info`() {
      val fileId = storeMedia("videoAndroid.mp4", mp4Metadata)

      val muxStream = MuxStreamModel(fileId, "playback", "token")
      every { muxService.getMuxStream(fileId, any()) } returns muxStream

      assertEquals(muxStream, service.getMuxStreamInfo(activityId, fileId))
    }

    @Test
    fun `throws exception if file has no Mux stream`() {
      val fileId = storeMedia("pixel.png", pngMetadata)

      every { muxService.getMuxStream(fileId, any()) } throws VideoStreamNotFoundException(fileId)

      assertThrows<VideoStreamNotFoundException> { service.getMuxStreamInfo(activityId, fileId) }
    }

    @Test
    fun `throws exception if no permission to read activity`() {
      val fileId = storeMedia("videoAndroid.mp4", mp4Metadata)

      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<ActivityNotFoundException> { service.getMuxStreamInfo(activityId, fileId) }
    }
  }

  @Test
  fun `handler for ActivityDeletionStartedEvent deletes media for activity`() {
    val imageFileId = storeMedia("pixel.png", pngMetadata)
    val videoFileId = storeMedia("videoHeaderOnly.mp4", mp4Metadata)

    val activity2Id = insertActivity()
    val activity2FileId = storeMedia("photoWithDate.jpg", jpegMetadata, activity2Id)

    eventPublisher.clear()

    service.on(ActivityDeletionStartedEvent(activityId))

    assertEquals(listOf(activity2FileId), filesDao.findAll().map { it.id }, "Remaining file IDs")
    assertEquals(
        listOf(activity2Id),
        activityMediaFilesDao.findAll().map { it.activityId },
        "Activity IDs of remaining media",
    )

    eventPublisher.assertExactEventsPublished(
        setOf(
            FileDeletionStartedEvent(imageFileId),
            FileDeletionStartedEvent(videoFileId),
            VideoFileDeletedEvent(videoFileId),
        )
    )

    assertIsEventListener<ActivityDeletionStartedEvent>(service)
  }

  @Test
  fun `handler for OrganizationDeletionStartedEvent deletes media for all activities in organization`() {
    val project2Id = insertProject(organizationId)
    val activity2Id = insertActivity(projectId = project2Id)

    val otherOrgId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
    val otherProjectId = insertProject(otherOrgId)
    val otherActivityId = insertActivity(projectId = otherProjectId)

    storeMedia("pixel.png", pngMetadata)
    storeMedia("photoWithDate.jpg", jpegMetadata, activity2Id)
    val otherOrgFileId = storeMedia("photoWithGps.jpg", jpegMetadata, otherActivityId)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    assertEquals(listOf(otherOrgFileId), filesDao.findAll().map { it.id }, "Remaining file IDs")
    assertEquals(
        listOf(otherActivityId),
        activityMediaFilesDao.findAll().map { it.activityId },
        "Activity IDs of remaining media",
    )

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)
  }

  private fun storeMedia(
      filename: String,
      metadata: NewFileMetadata = jpegMetadata,
      activityId: ActivityId = this.activityId,
      listPosition: Int? = null,
  ): FileId {
    return service.storeMedia(
        activityId,
        javaClass.getResourceAsStream("/file/$filename"),
        metadata,
        listPosition,
    )
  }

  private fun storeMediaBytes(
      content: ByteArray,
      metadata: NewFileMetadata = jpegMetadata,
  ): FileId {
    return service.storeMedia(activityId, content.inputStream(), metadata)
  }

  private fun assertListPositions(expected: List<FileId>, message: String? = null) {
    assertEquals(
        expected.mapIndexed { index, fileId -> fileId to index + 1 }.toMap(),
        dslContext
            .select(ACTIVITY_MEDIA_FILES.FILE_ID, ACTIVITY_MEDIA_FILES.LIST_POSITION)
            .from(ACTIVITY_MEDIA_FILES)
            .fetchMap(ACTIVITY_MEDIA_FILES.FILE_ID, ACTIVITY_MEDIA_FILES.LIST_POSITION),
        message,
    )
  }
}
