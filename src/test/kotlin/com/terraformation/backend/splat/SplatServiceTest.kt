package com.terraformation.backend.splat

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.records.BirdnetResultsRecord
import com.terraformation.backend.db.default_schema.tables.records.SplatAnnotationsRecord
import com.terraformation.backend.db.default_schema.tables.records.SplatsRecord
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.point
import com.terraformation.backend.splat.sqs.SplatterRequestMessage
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SplatServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val fileStore: S3FileStore = mockk()
  private val sqsTemplate: SqsTemplate = mockk()

  private val service: SplatService by lazy {
    SplatService(clock, config, dslContext, fileStore, ParentStore(dslContext), sqsTemplate)
  }

  private lateinit var observationId: ObservationId
  private lateinit var organizationId: OrganizationId
  private lateinit var fileId: FileId

  @BeforeEach
  fun setUp() {
    val splatterConfig =
        TerrawareServerConfig.SplatterConfig(
            enabled = true,
            requestQueueUrl = URI("https://example"),
            responseQueueUrl = URI("https://example"),
        )
    every { config.splatter } returns splatterConfig
    every { config.s3BucketName } returns "bucket"

    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
    insertPlantingSite(x = 0, width = 11, gridOrigin = point(1))
    insertMonitoringPlot()
    observationId = insertObservation(state = ObservationState.InProgress)
    fileId = insertFile()
    insertObservationPlot()
    insertObservationMediaFile()
  }

  @Nested
  inner class ListObservationBirdnetResults {
    private lateinit var fileId1: FileId
    private lateinit var fileId2: FileId

    @BeforeEach
    fun setUp() {
      fileId1 = insertFile()
      fileId2 = insertFile()
      insertObservationMediaFile(fileId = fileId1)
      insertObservationMediaFile(fileId = fileId2)
      insertBirdnetResult(fileId = fileId1, assetStatus = AssetStatus.Ready)
      insertBirdnetResult(fileId = fileId2, assetStatus = AssetStatus.Preparing)
    }

    @Test
    fun `returns BirdNet results for an observation`() {
      val expected =
          listOf(
              ObservationBirdnetResultModel(
                  assetStatus = AssetStatus.Ready,
                  fileId = fileId1,
                  monitoringPlotId = inserted.monitoringPlotId,
                  observationId = observationId,
                  resultsStorageUrl = null,
              ),
              ObservationBirdnetResultModel(
                  assetStatus = AssetStatus.Preparing,
                  fileId = fileId2,
                  monitoringPlotId = inserted.monitoringPlotId,
                  observationId = observationId,
                  resultsStorageUrl = null,
              ),
          )

      assertEquals(expected, service.listObservationBirdnetResults(observationId))
    }

    @Test
    fun `filters results by file ID`() {
      val expected =
          listOf(
              ObservationBirdnetResultModel(
                  assetStatus = AssetStatus.Ready,
                  fileId = fileId1,
                  monitoringPlotId = inserted.monitoringPlotId,
                  observationId = observationId,
                  resultsStorageUrl = null,
              ),
          )

      assertEquals(expected, service.listObservationBirdnetResults(observationId, fileId = fileId1))
    }

    @Test
    fun `returns empty list when no BirdNet results exist`() {
      val otherObservationId = insertObservation(state = ObservationState.InProgress)

      assertEquals(
          emptyList<ObservationBirdnetResultModel>(),
          service.listObservationBirdnetResults(otherObservationId),
      )
    }

    @Test
    fun `throws exception if user does not have permission to read observation`() {
      val otherOrganizationId = insertOrganization()
      val otherPlantingSiteId = insertPlantingSite(organizationId = otherOrganizationId)
      val otherObservationId = insertObservation(plantingSiteId = otherPlantingSiteId)

      assertThrows<ObservationNotFoundException> {
        service.listObservationBirdnetResults(otherObservationId)
      }
    }
  }

  @Nested
  inner class SetObservationSplatAnnotations {

    @BeforeEach
    fun setUp() {
      insertSplat()
    }

    @Test
    fun `creates new annotations when none exist`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val cameraPosition1 = CoordinateModel(4.0, 5.0, 6.0)
      val position2 = CoordinateModel(7.0, 8.0, 9.0)

      val annotations =
          listOf(
              NewSplatAnnotationModel(
                  id = null,
                  title = "New Annotation 1",
                  bodyText = "Description 1",
                  label = "Label 1",
                  position = position1,
                  cameraPosition = cameraPosition1,
                  fileId = fileId,
              ),
              NewSplatAnnotationModel(
                  id = null,
                  title = "New Annotation 2",
                  position = position2,
                  fileId = fileId,
              ),
          )

      service.setObservationSplatAnnotations(observationId, fileId, annotations)

      assertTableEquals(
          listOf(
              SplatAnnotationsRecord(
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "New Annotation 1",
                  bodyText = "Description 1",
                  label = "Label 1",
                  positionX = position1.x,
                  positionY = position1.y,
                  positionZ = position1.z,
                  cameraPositionX = cameraPosition1.x,
                  cameraPositionY = cameraPosition1.y,
                  cameraPositionZ = cameraPosition1.z,
              ),
              SplatAnnotationsRecord(
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "New Annotation 2",
                  positionX = position2.x,
                  positionY = position2.y,
                  positionZ = position2.z,
              ),
          )
      )
    }

    @Test
    fun `updates existing annotations with IDs`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val id1 =
          insertSplatAnnotation(
              title = "Original Title",
              bodyText = "Original Text",
              position = position1,
          )

      val updatedPosition = CoordinateModel(10.0, 20.0, 30.0)
      val updatedCameraPosition = CoordinateModel(40.0, 50.0, 60.0)
      val annotations =
          listOf(
              ExistingSplatAnnotationModel(
                  id = id1,
                  title = "Updated Title",
                  bodyText = "Updated Text",
                  label = "Updated Label",
                  position = updatedPosition,
                  cameraPosition = updatedCameraPosition,
                  fileId = fileId,
              ),
          )

      service.setObservationSplatAnnotations(observationId, fileId, annotations)

      assertTableEquals(
          SplatAnnotationsRecord(
              id = id1,
              fileId = fileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              title = "Updated Title",
              bodyText = "Updated Text",
              label = "Updated Label",
              positionX = updatedPosition.x,
              positionY = updatedPosition.y,
              positionZ = updatedPosition.z,
              cameraPositionX = updatedCameraPosition.x,
              cameraPositionY = updatedCameraPosition.y,
              cameraPositionZ = updatedCameraPosition.z,
          )
      )
    }

    @Test
    fun `deletes annotations not in the request list`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)
      val position3 = CoordinateModel(7.0, 8.0, 9.0)

      val id1 = insertSplatAnnotation(title = "Annotation 1", position = position1)
      val id2 = insertSplatAnnotation(title = "Annotation 2", position = position2)
      insertSplatAnnotation(title = "Annotation 3", position = position3)

      val annotations =
          listOf(
              ExistingSplatAnnotationModel(
                  id = id1,
                  title = "Annotation 1",
                  position = position1,
                  fileId = fileId,
              ),
              ExistingSplatAnnotationModel(
                  id = id2,
                  title = "Annotation 2",
                  position = position2,
                  fileId = fileId,
              ),
          )

      service.setObservationSplatAnnotations(observationId, fileId, annotations)

      assertTableEquals(
          listOf(
              SplatAnnotationsRecord(
                  id = id1,
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Annotation 1",
                  positionX = position1.x,
                  positionY = position1.y,
                  positionZ = position1.z,
              ),
              SplatAnnotationsRecord(
                  id = id2,
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Annotation 2",
                  positionX = position2.x,
                  positionY = position2.y,
                  positionZ = position2.z,
              ),
          )
      )
    }

    @Test
    fun `handles mixed update, insert, and delete operations`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)
      val position3 = CoordinateModel(7.0, 8.0, 9.0)

      val id1 = insertSplatAnnotation(title = "Keep and Update", position = position1)
      insertSplatAnnotation(title = "Delete Me", position = position2)

      val annotations =
          listOf(
              ExistingSplatAnnotationModel(
                  id = id1,
                  title = "Updated Title",
                  position = position1,
                  fileId = fileId,
              ),
              NewSplatAnnotationModel(
                  id = null,
                  title = "New Annotation",
                  position = position3,
                  fileId = fileId,
              ),
          )

      service.setObservationSplatAnnotations(observationId, fileId, annotations)

      assertTableEquals(
          listOf(
              SplatAnnotationsRecord(
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Updated Title",
                  positionX = position1.x,
                  positionY = position1.y,
                  positionZ = position1.z,
              ),
              SplatAnnotationsRecord(
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "New Annotation",
                  positionX = position3.x,
                  positionY = position3.y,
                  positionZ = position3.z,
              ),
          )
      )
    }

    @Test
    fun `deletes all annotations when empty list provided`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)

      insertSplatAnnotation(title = "Annotation 1", position = position1)
      insertSplatAnnotation(title = "Annotation 2", position = position2)

      service.setObservationSplatAnnotations(observationId, fileId, emptyList())

      assertTableEmpty(SPLAT_ANNOTATIONS)
    }

    @Test
    fun `only modifies annotations for the given file`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)

      val id1 = insertSplatAnnotation(title = "Annotation 1", position = position1)

      val otherFileId = insertFile()
      insertObservationMediaFile(fileId = otherFileId)
      insertSplat()
      val id2 = insertSplatAnnotation(title = "Annotation 2", position = position2)

      val annotations =
          listOf(
              ExistingSplatAnnotationModel(
                  id = id1,
                  title = "Updated Annotation 1",
                  position = position1,
                  fileId = fileId,
              ),
              ExistingSplatAnnotationModel(
                  id = id2,
                  title = "Updated Annotation 2",
                  position = position2,
                  fileId = otherFileId,
              ),
          )

      service.setObservationSplatAnnotations(observationId, fileId, annotations)

      assertTableEquals(
          listOf(
              SplatAnnotationsRecord(
                  id = id1,
                  fileId = fileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Updated Annotation 1",
                  positionX = position1.x,
                  positionY = position1.y,
                  positionZ = position1.z,
              ),
              SplatAnnotationsRecord(
                  id = id2,
                  fileId = otherFileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Annotation 2", // not updated
                  positionX = position2.x,
                  positionY = position2.y,
                  positionZ = position2.z,
              ),
          )
      )
    }

    @Test
    fun `throws exception if file is not associated with observation`() {
      val otherFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.setObservationSplatAnnotations(observationId, otherFileId, emptyList())
      }
    }

    @Test
    fun `throws exception if splat does not exist for file`() {
      val fileWithoutSplat = insertFile()
      insertObservationMediaFile(fileId = fileWithoutSplat)

      assertThrows<FileNotFoundException> {
        service.setObservationSplatAnnotations(observationId, fileWithoutSplat, emptyList())
      }
    }
  }

  @Nested
  inner class GetObservationSplatInfo {
    @Test
    fun `returns origin position when it exists`() {
      val originPosition = CoordinateModel(10.0, 20.0, 30.0)
      insertSplat(originPosition = originPosition)

      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val id1 = insertSplatAnnotation(title = "Annotation 1", position = position1)

      val expected =
          SplatInfoModel(
              annotations =
                  listOf(
                      ExistingSplatAnnotationModel(
                          id = id1,
                          title = "Annotation 1",
                          position = position1,
                          fileId = fileId,
                      ),
                  ),
              cameraPosition = null,
              originPosition = originPosition,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns null origin position when record does not exist`() {
      insertSplat()
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val id1 = insertSplatAnnotation(title = "Annotation 1", position = position1)

      val expected =
          SplatInfoModel(
              annotations =
                  listOf(
                      ExistingSplatAnnotationModel(
                          id = id1,
                          title = "Annotation 1",
                          position = position1,
                          fileId = fileId,
                      ),
                  ),
              cameraPosition = null,
              originPosition = null,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns null origin position when all coordinates are null`() {
      insertSplat(originPosition = null)

      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val id1 = insertSplatAnnotation(title = "Annotation 1", position = position1)

      val expected =
          SplatInfoModel(
              annotations =
                  listOf(
                      ExistingSplatAnnotationModel(
                          id = id1,
                          title = "Annotation 1",
                          position = position1,
                          fileId = fileId,
                      ),
                  ),
              cameraPosition = null,
              originPosition = null,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns all annotations with origin position`() {
      val originPosition = CoordinateModel(5.0, 10.0, 15.0)
      insertSplat(originPosition = originPosition)

      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val cameraPosition1 = CoordinateModel(4.0, 5.0, 6.0)
      val id1 =
          insertSplatAnnotation(
              title = "Test Annotation 1",
              bodyText = "Description 1",
              label = "Label 1",
              position = position1,
              cameraPosition = cameraPosition1,
          )

      val position2 = CoordinateModel(7.0, 8.0, 9.0)
      val id2 = insertSplatAnnotation(title = "Test Annotation 2", position = position2)

      val expected =
          SplatInfoModel(
              annotations =
                  listOf(
                      ExistingSplatAnnotationModel(
                          id = id1,
                          title = "Test Annotation 1",
                          bodyText = "Description 1",
                          label = "Label 1",
                          position = position1,
                          cameraPosition = cameraPosition1,
                          fileId = fileId,
                      ),
                      ExistingSplatAnnotationModel(
                          id = id2,
                          title = "Test Annotation 2",
                          position = position2,
                          fileId = fileId,
                      ),
                  ),
              cameraPosition = null,
              originPosition = originPosition,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns empty annotations list when none exist`() {
      val originPosition = CoordinateModel(10.0, 20.0, 30.0)
      insertSplat(originPosition = originPosition)

      val expected =
          SplatInfoModel(
              annotations = emptyList(),
              cameraPosition = null,
              originPosition = originPosition,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns camera position when it exists`() {
      val cameraPosition = CoordinateModel(40.0, 50.0, 60.0)
      insertSplat(cameraPosition = cameraPosition)

      val expected =
          SplatInfoModel(
              annotations = emptyList(),
              cameraPosition = cameraPosition,
              originPosition = null,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `returns both origin and camera positions when they exist`() {
      val originPosition = CoordinateModel(10.0, 20.0, 30.0)
      val cameraPosition = CoordinateModel(40.0, 50.0, 60.0)
      insertSplat(originPosition = originPosition, cameraPosition = cameraPosition)

      val expected =
          SplatInfoModel(
              annotations = emptyList(),
              cameraPosition = cameraPosition,
              originPosition = originPosition,
          )

      assertEquals(expected, service.getObservationSplatInfo(observationId, fileId))
    }

    @Test
    fun `throws exception if file is not associated with observation`() {
      val otherFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.getObservationSplatInfo(observationId, otherFileId)
      }
    }

    @Test
    fun `throws exception if user does not have permission to read observation`() {
      insertSplat()
      deleteOrganizationUser()

      assertThrows<ObservationNotFoundException> {
        service.getObservationSplatInfo(observationId, fileId)
      }
    }

    @Test
    fun `throws exception if splat does not exist for file`() {
      insertSplat()
      val fileWithoutSplat = insertFile()
      insertObservationMediaFile(fileId = fileWithoutSplat)

      assertThrows<FileNotFoundException> {
        service.getObservationSplatInfo(observationId, fileWithoutSplat)
      }
    }
  }

  @Nested
  inner class RecordBirdnetSuccess {
    private lateinit var fileId: FileId

    @BeforeEach
    fun setUp() {
      fileId = insertFile()
      insertBirdnetResult(fileId = fileId, assetStatus = AssetStatus.Preparing)
    }

    @Test
    fun `updates status to Ready and sets completed time`() {
      service.recordBirdnetSuccess(fileId)

      assertTableEquals(
          BirdnetResultsRecord(
              fileId = fileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Ready,
              completedTime = clock.instant(),
          )
      )
    }
  }

  @Nested
  inner class RecordBirdnetError {
    private lateinit var fileId: FileId

    @BeforeEach
    fun setUp() {
      fileId = insertFile()
      insertBirdnetResult(fileId = fileId, assetStatus = AssetStatus.Preparing)
    }

    @Test
    fun `updates status to Errored and sets error message and completed time`() {
      val errorMessage = "Test error message"

      service.recordBirdnetError(fileId, errorMessage)

      assertTableEquals(
          BirdnetResultsRecord(
              fileId = fileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Errored,
              completedTime = clock.instant(),
              errorMessage = errorMessage,
          )
      )
    }
  }

  @Nested
  inner class GenerateObservationSplat {
    @BeforeEach
    fun setUp() {
      every { fileStore.getPath(any()) } answers { java.nio.file.Paths.get("/path/to/video.mp4") }
      every { fileStore.getUrl(any()) } answers
          {
            val path = firstArg<java.nio.file.Path>()
            URI("s3://bucket/${path.fileName}")
          }
      every { sqsTemplate.send(any<String>(), any<SplatterRequestMessage>()) } returns
          mockk(relaxed = true)
    }

    @Test
    fun `associates splat with organization that owns observation`() {
      insertOrganization()

      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          runBirdnet = false,
      )

      assertTableEquals(
          SplatsRecord(
              fileId = fileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Preparing,
              splatStorageUrl = URI("s3://bucket/video.sog"),
              organizationId = organizationId,
          )
      )
    }

    @Test
    fun `creates BirdNet result record when runBirdnet is true`() {
      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          runBirdnet = true,
      )

      assertTableEquals(
          BirdnetResultsRecord(
              fileId = fileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Preparing,
              resultsStorageUrl = URI("s3://bucket/video_birdnet.json"),
          )
      )
    }

    @Test
    fun `does not create BirdNet result record when runBirdnet is false`() {
      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          runBirdnet = false,
      )

      assertTableEmpty(BIRDNET_RESULTS)
    }

    @Test
    fun `updates existing BirdNet result when force is true`() {
      insertBirdnetResult(fileId = fileId, assetStatus = AssetStatus.Errored)

      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          force = true,
          runBirdnet = true,
      )

      assertTableEquals(
          BirdnetResultsRecord(
              fileId = fileId,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              assetStatusId = AssetStatus.Preparing,
          )
      )
    }
  }

  @Nested
  inner class OnFileDeletionStartedEvent {
    @Test
    fun `deletes splat file from file store when media file is deleted`() {
      val url = URI("s3://bucket/file.sog")
      val jobArchiveUrl = URI("s3://bucket/file.sog-job.tar.gz")

      every { fileStore.delete(url) } just Runs
      every { fileStore.delete(jobArchiveUrl) } throws NoSuchFileException("Missing!")

      insertSplat(splatStorageUrl = url)

      val event = FileDeletionStartedEvent(fileId, "video/quicktime")

      service.on(event)

      verify(exactly = 1) { fileStore.delete(url) }
      verify(exactly = 1) { fileStore.delete(jobArchiveUrl) }

      assertTableEmpty(SPLATS)
    }
  }

  @Nested
  inner class GenerateOrganizationMediaSplat {
    private lateinit var orgFileId: FileId

    @BeforeEach
    fun setUp() {
      orgFileId = insertOrganizationMediaFile()

      every { fileStore.getPath(any()) } answers { java.nio.file.Paths.get("/path/to/video.mp4") }
      every { fileStore.getUrl(any()) } answers
          {
            val path = firstArg<java.nio.file.Path>()
            URI("s3://bucket/${path.fileName}")
          }
      every { sqsTemplate.send(any<String>(), any<SplatterRequestMessage>()) } returns
          mockk(relaxed = true)
    }

    @Test
    fun `creates splat record and sends SQS message`() {
      service.generateOrganizationMediaSplat(
          organizationId = organizationId,
          fileId = orgFileId,
          runBirdnet = false,
      )

      assertTableEquals(
          SplatsRecord(
              fileId = orgFileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Preparing,
              splatStorageUrl = URI("s3://bucket/video.sog"),
              organizationId = organizationId,
          )
      )

      verify(exactly = 1) { sqsTemplate.send(any<String>(), any<SplatterRequestMessage>()) }
    }

    @Test
    fun `creates BirdNet result record when runBirdnet is true`() {
      service.generateOrganizationMediaSplat(
          organizationId = organizationId,
          fileId = orgFileId,
          runBirdnet = true,
      )

      assertTableEquals(
          BirdnetResultsRecord(
              fileId = orgFileId,
              createdBy = user.userId,
              createdTime = clock.instant(),
              assetStatusId = AssetStatus.Preparing,
              resultsStorageUrl = URI("s3://bucket/video_birdnet.json"),
          )
      )
    }

    @Test
    fun `does not create BirdNet result when runBirdnet is false`() {
      service.generateOrganizationMediaSplat(
          organizationId = organizationId,
          fileId = orgFileId,
          runBirdnet = false,
      )

      assertTableEmpty(BIRDNET_RESULTS)
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to organization`() {
      val unassociatedFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.generateOrganizationMediaSplat(
            organizationId = organizationId,
            fileId = unassociatedFileId,
        )
      }
    }

    @Test
    fun `throws FileNotFoundException when file belongs to different organization`() {
      val otherOrgId = insertOrganization()
      insertOrganizationUser(organizationId = otherOrgId, role = Role.Admin)
      val otherOrgFileId =
          insertOrganizationMediaFile(fileId = insertFile(), organizationId = otherOrgId)

      assertThrows<FileNotFoundException> {
        service.generateOrganizationMediaSplat(
            organizationId = organizationId,
            fileId = otherOrgFileId,
        )
      }
    }

    @Test
    fun `does not send SQS message when splat already exists and force is false`() {
      insertSplat(fileId = orgFileId, assetStatus = AssetStatus.Ready)

      service.generateOrganizationMediaSplat(
          organizationId = organizationId,
          fileId = orgFileId,
          force = false,
          runBirdnet = false,
      )

      verify(exactly = 0) { sqsTemplate.send(any<String>(), any<SplatterRequestMessage>()) }
    }

    @Test
    fun `sends SQS message when splat already exists and force is true`() {
      insertSplat(fileId = orgFileId, assetStatus = AssetStatus.Ready)

      service.generateOrganizationMediaSplat(
          organizationId = organizationId,
          fileId = orgFileId,
          force = true,
          runBirdnet = false,
      )

      verify(exactly = 1) { sqsTemplate.send(any<String>(), any<SplatterRequestMessage>()) }

      assertTableEquals(
          SplatsRecord(
              fileId = orgFileId,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              assetStatusId = AssetStatus.Preparing,
              splatStorageUrl = URI("s3://bucket/splat"),
              organizationId = organizationId,
          )
      )
    }

    @Test
    fun `throws exception when user is not a member of the organization`() {
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> {
        service.generateOrganizationMediaSplat(
            organizationId = organizationId,
            fileId = orgFileId,
        )
      }
    }
  }

  @Nested
  inner class ReadOrganizationSplat {
    private lateinit var orgFileId: FileId

    @BeforeEach
    fun setUp() {
      orgFileId = insertOrganizationMediaFile()
    }

    @Test
    fun `returns splat data for a valid ready file`() {
      val splatUrl = URI("s3://bucket/splat.sog")
      insertSplat(fileId = orgFileId, assetStatus = AssetStatus.Ready, splatStorageUrl = splatUrl)

      val expectedStream = SizedInputStream(ByteArrayInputStream(ByteArray(10)), 10)
      every { fileStore.read(splatUrl) } returns expectedStream

      val result = service.readOrganizationSplat(organizationId, orgFileId)

      assertEquals(10L, result.size, "Stream size")
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to organization`() {
      val unassociatedFileId = insertFile()
      insertSplat(fileId = unassociatedFileId)

      assertThrows<FileNotFoundException> {
        service.readOrganizationSplat(organizationId, unassociatedFileId)
      }
    }

    @Test
    fun `throws SplatNotReadyException when splat status is Preparing`() {
      insertSplat(fileId = orgFileId, assetStatus = AssetStatus.Preparing)

      assertThrows<SplatNotReadyException> {
        service.readOrganizationSplat(organizationId, orgFileId)
      }
    }

    @Test
    fun `throws SplatGenerationFailedException when splat status is Errored`() {
      insertSplat(fileId = orgFileId, assetStatus = AssetStatus.Errored)

      assertThrows<SplatGenerationFailedException> {
        service.readOrganizationSplat(organizationId, orgFileId)
      }
    }

    @Test
    fun `throws FileNotFoundException when no splat record exists for file`() {
      assertThrows<FileNotFoundException> {
        service.readOrganizationSplat(organizationId, orgFileId)
      }
    }

    @Test
    fun `throws exception when user is not a member of the organization`() {
      insertSplat(fileId = orgFileId)
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> {
        service.readOrganizationSplat(organizationId, orgFileId)
      }
    }
  }

  @Nested
  inner class GetOrganizationSplatInfo {
    private lateinit var orgFileId: FileId

    @BeforeEach
    fun setUp() {
      orgFileId = insertOrganizationMediaFile()
    }

    @Test
    fun `returns annotations and positions`() {
      val originPosition = CoordinateModel(10.0, 20.0, 30.0)
      val cameraPosition = CoordinateModel(40.0, 50.0, 60.0)
      insertSplat(
          fileId = orgFileId,
          originPosition = originPosition,
          cameraPosition = cameraPosition,
      )

      val annotationPosition = CoordinateModel(1.0, 2.0, 3.0)
      val annotationId =
          insertSplatAnnotation(
              fileId = orgFileId,
              title = "Test Annotation",
              bodyText = "Description",
              label = "Label",
              position = annotationPosition,
          )

      val result = service.getOrganizationSplatInfo(organizationId, orgFileId)

      val expected =
          SplatInfoModel(
              annotations =
                  listOf(
                      ExistingSplatAnnotationModel(
                          id = annotationId,
                          title = "Test Annotation",
                          bodyText = "Description",
                          label = "Label",
                          position = annotationPosition,
                          fileId = orgFileId,
                      ),
                  ),
              cameraPosition = cameraPosition,
              originPosition = originPosition,
          )

      assertEquals(expected, result)
    }

    @Test
    fun `returns empty annotations and null positions when none set`() {
      insertSplat(fileId = orgFileId)

      val result = service.getOrganizationSplatInfo(organizationId, orgFileId)

      assertEquals(SplatInfoModel(emptyList(), null, null), result)
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to organization`() {
      val unassociatedFileId = insertFile()
      insertSplat(fileId = unassociatedFileId)

      assertThrows<FileNotFoundException> {
        service.getOrganizationSplatInfo(organizationId, unassociatedFileId)
      }
    }

    @Test
    fun `throws FileNotFoundException when splat does not exist for file`() {
      assertThrows<FileNotFoundException> {
        service.getOrganizationSplatInfo(organizationId, orgFileId)
      }
    }

    @Test
    fun `throws exception when user is not a member of the organization`() {
      insertSplat(fileId = orgFileId)
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> {
        service.getOrganizationSplatInfo(organizationId, orgFileId)
      }
    }
  }

  @Nested
  inner class SetOrganizationSplatAnnotations {
    private lateinit var orgFileId: FileId

    @BeforeEach
    fun setUp() {
      orgFileId = insertOrganizationMediaFile()
      insertSplat(fileId = orgFileId)
    }

    @Test
    fun `creates new annotations`() {
      val position = CoordinateModel(1.0, 2.0, 3.0)
      val cameraPos = CoordinateModel(4.0, 5.0, 6.0)

      val annotations =
          listOf(
              NewSplatAnnotationModel(
                  id = null,
                  title = "Annotation 1",
                  bodyText = "Body",
                  label = "Label",
                  position = position,
                  cameraPosition = cameraPos,
                  fileId = orgFileId,
              ),
          )

      service.setOrganizationSplatAnnotations(organizationId, orgFileId, annotations)

      assertTableEquals(
          listOf(
              SplatAnnotationsRecord(
                  fileId = orgFileId,
                  createdBy = user.userId,
                  createdTime = clock.instant(),
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant(),
                  title = "Annotation 1",
                  bodyText = "Body",
                  label = "Label",
                  positionX = position.x,
                  positionY = position.y,
                  positionZ = position.z,
                  cameraPositionX = cameraPos.x,
                  cameraPositionY = cameraPos.y,
                  cameraPositionZ = cameraPos.z,
              )
          )
      )
    }

    @Test
    fun `deletes all annotations when empty list provided`() {
      val position = CoordinateModel(1.0, 2.0, 3.0)
      insertSplatAnnotation(fileId = orgFileId, title = "Delete Me", position = position)

      service.setOrganizationSplatAnnotations(organizationId, orgFileId, emptyList())

      assertTableEmpty(SPLAT_ANNOTATIONS)
    }

    @Test
    fun `throws FileNotFoundException when file does not belong to organization`() {
      val unassociatedFileId = insertFile()
      insertSplat(fileId = unassociatedFileId)

      assertThrows<FileNotFoundException> {
        service.setOrganizationSplatAnnotations(organizationId, unassociatedFileId, emptyList())
      }
    }

    @Test
    fun `throws FileNotFoundException when splat does not exist for file`() {
      val fileWithoutSplat = insertOrganizationMediaFile(fileId = insertFile())

      assertThrows<FileNotFoundException> {
        service.setOrganizationSplatAnnotations(organizationId, fileWithoutSplat, emptyList())
      }
    }

    @Test
    fun `throws exception when user is not a member of the organization`() {
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> {
        service.setOrganizationSplatAnnotations(organizationId, orgFileId, emptyList())
      }
    }
  }
}
