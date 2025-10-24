package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.ActivityMediaStore
import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.accelerator.event.ActivityDeletionStartedEvent
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.InMemoryFileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.file.VideoStreamNotFoundException
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.point
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
  private val eventPublisher = TestEventPublisher()
  private val fileStore = InMemoryFileStore()
  private val muxService: MuxService = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()
  private val fileService: FileService by lazy {
    FileService(
        dslContext,
        clock,
        eventPublisher,
        filesDao,
        fileStore,
    )
  }
  private val thumbnailService: ThumbnailService by lazy {
    ThumbnailService(dslContext, fileService, listOf(muxService), thumbnailStore)
  }
  private val service: ActivityMediaService by lazy {
    ActivityMediaService(
        ActivityMediaStore(clock, dslContext, eventPublisher),
        clock,
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
  private lateinit var createdBy: UserId
  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    createdBy = insertUser()
    organizationId = insertOrganization()
    projectId = insertProject(organizationId)
    activityId = insertActivity(createdBy = createdBy, projectId = projectId)

    insertOrganizationUser(role = Role.Admin)
  }

  @Nested
  inner class StoreMedia {
    @Test
    fun `updates activity modified by and time`() {
      val activityBefore = dslContext.fetchSingle(ACTIVITIES)

      clock.instant = Instant.ofEpochSecond(500)
      storeMedia("pixel.jpg")

      assertTableEquals(
          activityBefore.also { record ->
            record.modifiedBy = user.userId
            record.modifiedTime = clock.instant
          }
      )
    }

    @Test
    fun `stores photo and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("photoWithDateAndGps.jpg"),
          capturedLocalTime = LocalDateTime.of(2023, 5, 15, 14, 30, 25),
          geolocation = point(-122.4194, 37.7749),
      )
    }

    @Test
    fun `stores iPhone video and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("videoIphone.mov"),
          capturedLocalTime = LocalDateTime.of(2024, 6, 15, 12, 34, 56),
          geolocation = point(-122.4194, 37.7749),
          type = ActivityMediaType.Video,
      )
    }

    @Test
    fun `stores Android video and extracts date and GPS metadata`() {
      assertMediaFileEquals(
          storeMedia("videoAndroid.mp4"),
          capturedLocalTime = LocalDateTime.of(2024, 6, 15, 12, 34, 56),
          geolocation = point(-122.4194, 37.7749),
          type = ActivityMediaType.Video,
      )
    }

    @Test
    fun `stores photo and extracts date only when no GPS present`() {
      assertMediaFileEquals(
          storeMedia("photoWithDate.jpg"),
          capturedLocalTime = LocalDateTime.of(2022, 12, 25, 9, 15, 0),
          geolocation = null,
      )
    }

    @Test
    fun `stores photo and extracts GPS only when no date present`() {
      assertMediaFileEquals(
          storeMedia("photoWithGps.jpg"),
          capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
          geolocation = point(-74.006, 40.7128),
      )
    }

    @Test
    fun `stores photo with no metadata using defaults`() {
      assertMediaFileEquals(
          storeMedia("pixel.png", pngMetadata),
          capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
          geolocation = null,
      )
    }

    @Test
    fun `stores video with no metadata using defaults`() {
      assertMediaFileEquals(
          storeMedia("videoHeaderOnly.mp4", mp4Metadata),
          capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
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
          capturedLocalTime = LocalDate.EPOCH.atStartOfDay(),
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
        capturedLocalTime: LocalDateTime,
        geolocation: Point? = null,
        type: ActivityMediaType = ActivityMediaType.Photo,
    ) {
      val row = activityMediaFilesDao.fetchOneByFileId(fileId)
      assertNotNull(row)

      assertEquals(
          ActivityMediaFilesRow(
              activityId = activityId,
              activityMediaTypeId = type,
              fileId = fileId,
              isCoverPhoto = false,
              isHiddenOnMap = false,
              listPosition = 1,
          ),
          row,
      )

      val filesRow = filesDao.fetchOneById(fileId)!!
      assertEquals(filesRow.capturedLocalTime, capturedLocalTime, "Captured date")

      if (geolocation != null) {
        assertGeometryEquals(geolocation, filesRow.geolocation)
      } else {
        assertNull(filesRow.geolocation, "Geolocation")
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

      every { thumbnailStore.canGenerateThumbnails("image/png") } returns true
      every { thumbnailStore.getThumbnailData(fileId, maxWidth, maxHeight) } returns
          SizedInputStream(thumbnailContent.inputStream(), 25L)

      val inputStream = service.readMedia(activityId, fileId, maxWidth, maxHeight)
      assertArrayEquals(thumbnailContent, inputStream.readAllBytes(), "Thumbnail content")
    }

    @Test
    fun `returns raw file when requested`() {
      val testContent =
          javaClass.getResourceAsStream("/file/videoAndroid.mp4")!!.use { it.readAllBytes() }
      val fileId = storeMediaBytes(testContent, mp4Metadata)

      val inputStream = service.readMedia(activityId, fileId, raw = true)
      assertArrayEquals(testContent, inputStream.readAllBytes(), "Raw content")
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
    fun `deletes media file`() {
      val fileId = storeMedia("pixel.png", pngMetadata)

      service.deleteMedia(activityId, fileId)

      assertTableEmpty(ACTIVITY_MEDIA_FILES)
    }

    @Test
    fun `updates activity modified by and time`() {
      val fileId = storeMedia("pixel.jpg")
      val activityBefore = dslContext.fetchSingle(ACTIVITIES)

      clock.instant = Instant.ofEpochSecond(500)

      service.deleteMedia(activityId, fileId)

      assertTableEquals(
          activityBefore.also { record ->
            record.modifiedBy = user.userId
            record.modifiedTime = clock.instant
          }
      )
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
    fun `publishes reference deleted event`() {
      val fileId = storeMedia("videoAndroid.mp4", mp4Metadata)

      service.deleteMedia(activityId, fileId)

      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(fileId))
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
  inner class DeletionEventHandlers {
    private lateinit var activityFileId: FileId
    private lateinit var otherActivityFileId: FileId
    private lateinit var otherProjectActivityFileId: FileId
    private lateinit var otherOrganizationActivityFileId: FileId

    @BeforeEach
    fun setUp() {
      activityFileId = storeMedia(activityId = activityId)
      otherActivityFileId = storeMedia(activityId = insertActivity())
      insertProject()
      otherProjectActivityFileId = storeMedia(activityId = insertActivity())
      insertOrganization()
      insertOrganizationUser(role = Role.Admin)
      insertProject()
      otherOrganizationActivityFileId = storeMedia(activityId = insertActivity())
    }

    @Test
    fun activityDeletionStartedEvent() {
      service.on(ActivityDeletionStartedEvent(activityId))

      eventPublisher.assertEventPublished(FileReferenceDeletedEvent(activityFileId))

      assertRemainingFileIds(
          setOf(otherActivityFileId, otherProjectActivityFileId, otherOrganizationActivityFileId)
      )

      assertIsEventListener<ActivityDeletionStartedEvent>(service)
    }

    @Test
    fun projectDeletionStartedEvent() {
      service.on(ProjectDeletionStartedEvent(projectId))

      eventPublisher.assertEventsPublished(
          setOf(
              FileReferenceDeletedEvent(activityFileId),
              FileReferenceDeletedEvent(otherActivityFileId),
          )
      )

      assertRemainingFileIds(setOf(otherProjectActivityFileId, otherOrganizationActivityFileId))

      assertIsEventListener<ProjectDeletionStartedEvent>(service)
    }

    @Test
    fun organizationDeletionStartedEvent() {
      service.on(OrganizationDeletionStartedEvent(organizationId))

      eventPublisher.assertEventsPublished(
          setOf(
              FileReferenceDeletedEvent(activityFileId),
              FileReferenceDeletedEvent(otherActivityFileId),
              FileReferenceDeletedEvent(otherProjectActivityFileId),
          )
      )

      assertRemainingFileIds(setOf(otherOrganizationActivityFileId))

      assertIsEventListener<OrganizationDeletionStartedEvent>(service)
    }

    private fun assertRemainingFileIds(expected: Set<FileId>) {
      val actual = activityMediaFilesDao.findAll().map { it.fileId!! }.toSet()

      assertSetEquals(expected, actual, "Remaining file IDs")
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
    storeMedia("photoWithDate.jpg", jpegMetadata, activity2Id)

    eventPublisher.clear()

    service.on(ActivityDeletionStartedEvent(activityId))

    assertEquals(
        listOf(activity2Id),
        activityMediaFilesDao.findAll().map { it.activityId },
        "Activity IDs of remaining media",
    )

    eventPublisher.assertExactEventsPublished(
        setOf(
            FileReferenceDeletedEvent(imageFileId),
            FileReferenceDeletedEvent(videoFileId),
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

    val fileId1 = storeMedia("pixel.png", pngMetadata)
    val fileId2 = storeMedia("photoWithDate.jpg", jpegMetadata, activity2Id)
    storeMedia("photoWithGps.jpg", jpegMetadata, otherActivityId)

    service.on(OrganizationDeletionStartedEvent(organizationId))

    eventPublisher.assertExactEventsPublished(
        setOf(
            FileReferenceDeletedEvent(fileId1),
            FileReferenceDeletedEvent(fileId2),
        )
    )

    assertEquals(
        listOf(otherActivityId),
        activityMediaFilesDao.findAll().map { it.activityId },
        "Activity IDs of remaining media",
    )

    assertIsEventListener<OrganizationDeletionStartedEvent>(service)
  }

  private fun storeMedia(
      filename: String = "pixel.jpg",
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
