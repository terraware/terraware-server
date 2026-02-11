package com.terraformation.backend.splat

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.records.SplatAnnotationsRecord
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.point
import com.terraformation.backend.splat.sqs.SplatterRequestMessage
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.mockk.every
import io.mockk.mockk
import java.net.URI
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
    SplatService(clock, config, dslContext, fileStore, sqsTemplate)
  }

  private lateinit var observationId: ObservationId
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

    insertOrganization()
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
      val results = service.listObservationBirdnetResults(observationId)

      assertEquals(2, results.size, "Number of results")
      assertEquals(fileId1, results[0].fileId, "First result file ID")
      assertEquals(fileId2, results[1].fileId, "Second result file ID")
    }

    @Test
    fun `filters results by file ID`() {
      val results = service.listObservationBirdnetResults(observationId, fileId = fileId1)

      assertEquals(1, results.size, "Number of results")
      assertEquals(fileId1, results[0].fileId, "Result file ID")
      assertEquals(AssetStatus.Ready, results[0].assetStatus, "Asset status")
    }

    @Test
    fun `returns empty list when no BirdNet results exist`() {
      val otherObservationId = insertObservation(state = ObservationState.InProgress)

      val results = service.listObservationBirdnetResults(otherObservationId)

      assertEquals(0, results.size, "Number of results")
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
  inner class ListObservationSplatAnnotations {

    @BeforeEach
    fun setUp() {
      insertSplat()
    }

    @Test
    fun `returns annotations for a given observation and file`() {
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

      assertSetEquals(
          setOf(
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
          service.listObservationSplatAnnotations(observationId, fileId).toSet(),
      )
    }

    @Test
    fun `returns empty list when no annotations exist`() {
      val result = service.listObservationSplatAnnotations(observationId, fileId)

      assertEquals(0, result.size, "Number of annotations")
    }

    @Test
    fun `throws exception if file is not associated with observation`() {
      val otherFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.listObservationSplatAnnotations(observationId, otherFileId)
      }
    }

    @Test
    fun `throws exception if user does not have permission to read observation`() {
      val otherOrganizationId = insertOrganization()
      val otherPlantingSiteId = insertPlantingSite(organizationId = otherOrganizationId)
      val otherMonitoringPlotId = insertMonitoringPlot(plantingSiteId = otherPlantingSiteId)
      val otherObservationId = insertObservation(plantingSiteId = otherPlantingSiteId)
      val otherFileId = insertFile()
      insertObservationPlot(
          observationId = otherObservationId,
          monitoringPlotId = otherMonitoringPlotId,
      )
      insertObservationMediaFile(
          fileId = otherFileId,
          observationId = otherObservationId,
          monitoringPlotId = otherMonitoringPlotId,
      )

      assertThrows<ObservationNotFoundException> {
        service.listObservationSplatAnnotations(otherObservationId, otherFileId)
      }
    }

    @Test
    fun `throws exception if splat does not exist for file`() {
      val fileWithoutSplat = insertFile()
      insertObservationMediaFile(fileId = fileWithoutSplat)

      assertThrows<FileNotFoundException> {
        service.listObservationSplatAnnotations(observationId, fileWithoutSplat)
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
          ),
          where = SPLAT_ANNOTATIONS.FILE_ID.eq(fileId),
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
          ),
          where = SPLAT_ANNOTATIONS.FILE_ID.eq(fileId),
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
          ),
          where = SPLAT_ANNOTATIONS.FILE_ID.eq(fileId),
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
          ),
          where = SPLAT_ANNOTATIONS.FILE_ID.eq(fileId),
      )
    }

    @Test
    fun `deletes all annotations when empty list provided`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)

      insertSplatAnnotation(title = "Annotation 1", position = position1)
      insertSplatAnnotation(title = "Annotation 2", position = position2)

      service.setObservationSplatAnnotations(observationId, fileId, emptyList())

      assertTableEmpty(SPLAT_ANNOTATIONS, where = SPLAT_ANNOTATIONS.FILE_ID.eq(fileId))
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

      val result = dslContext.fetchOne(BIRDNET_RESULTS, BIRDNET_RESULTS.FILE_ID.eq(fileId))

      assertEquals(AssetStatus.Ready, result?.assetStatusId, "Asset status")
      assertEquals(clock.instant(), result?.completedTime, "Completed time")
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

      val result = dslContext.fetchOne(BIRDNET_RESULTS, BIRDNET_RESULTS.FILE_ID.eq(fileId))

      assertEquals(AssetStatus.Errored, result?.assetStatusId, "Asset status")
      assertEquals(errorMessage, result?.errorMessage, "Error message")
      assertEquals(clock.instant(), result?.completedTime, "Completed time")
    }
  }

  @Nested
  inner class GenerateObservationSplatWithBirdnet {
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
    fun `creates BirdNet result record when runBirdnet is true`() {
      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          runBirdnet = true,
      )

      val result = dslContext.fetchOne(BIRDNET_RESULTS, BIRDNET_RESULTS.FILE_ID.eq(fileId))

      assertEquals(fileId, result?.fileId, "File ID")
      assertEquals(AssetStatus.Preparing, result?.assetStatusId, "Asset status")
      assertEquals(user.userId, result?.createdBy, "Created by")
      assertEquals(clock.instant(), result?.createdTime, "Created time")
      assertEquals(
          URI("s3://bucket/video_birdnet.json"),
          result?.resultsStorageUrl,
          "Results storage URL",
      )
    }

    @Test
    fun `does not create BirdNet result record when runBirdnet is false`() {
      service.generateObservationSplat(
          observationId = observationId,
          fileId = fileId,
          runBirdnet = false,
      )

      val result = dslContext.fetchOne(BIRDNET_RESULTS, BIRDNET_RESULTS.FILE_ID.eq(fileId))

      assertEquals(null, result, "BirdNet result should not exist")
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

      val result = dslContext.fetchOne(BIRDNET_RESULTS, BIRDNET_RESULTS.FILE_ID.eq(fileId))

      assertEquals(
          AssetStatus.Preparing,
          result?.assetStatusId,
          "Asset status should be reset to Preparing",
      )
    }
  }
}
