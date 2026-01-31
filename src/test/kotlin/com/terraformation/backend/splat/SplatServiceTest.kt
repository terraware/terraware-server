package com.terraformation.backend.splat

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.point
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
  }

  @Nested
  inner class ListObservationSplatAnnotations {
    private lateinit var fileId: FileId

    @BeforeEach
    fun setUp() {
      fileId = insertFile()
      insertObservationPlot()
      insertObservationMediaFile()
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
  inner class SetSplatAnnotations {
    private lateinit var fileId: FileId

    @BeforeEach
    fun setUp() {
      fileId = insertFile()
      insertObservationPlot()
      insertObservationMediaFile()
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

      service.setSplatAnnotations(observationId, fileId, annotations)

      assertEquals(
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
          ),
          service.listObservationSplatAnnotations(observationId, fileId).map {
            // do this to ignore the ids in the test
            NewSplatAnnotationModel(
                id = null,
                title = it.title,
                bodyText = it.bodyText,
                label = it.label,
                position = it.position,
                cameraPosition = it.cameraPosition,
                fileId = it.fileId,
            )
          },
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

      service.setSplatAnnotations(observationId, fileId, annotations)

      assertEquals(
          listOf(
              ExistingSplatAnnotationModel(
                  id = id1,
                  title = "Updated Title",
                  bodyText = "Updated Text",
                  label = "Updated Label",
                  position = updatedPosition,
                  cameraPosition = updatedCameraPosition,
                  fileId = fileId,
              )
          ),
          service.listObservationSplatAnnotations(observationId, fileId),
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

      service.setSplatAnnotations(observationId, fileId, annotations)

      assertEquals(
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
          ),
          service.listObservationSplatAnnotations(observationId, fileId),
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

      service.setSplatAnnotations(observationId, fileId, annotations)

      assertEquals(
          listOf(
              NewSplatAnnotationModel(
                  id = null,
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
          ),
          service.listObservationSplatAnnotations(observationId, fileId).map {
            // do this to ignore the ids in the test
            NewSplatAnnotationModel(
                id = null,
                title = it.title,
                bodyText = it.bodyText,
                label = it.label,
                position = it.position,
                cameraPosition = it.cameraPosition,
                fileId = it.fileId,
            )
          },
      )
    }

    @Test
    fun `deletes all annotations when empty list provided`() {
      val position1 = CoordinateModel(1.0, 2.0, 3.0)
      val position2 = CoordinateModel(4.0, 5.0, 6.0)

      insertSplatAnnotation(title = "Annotation 1", position = position1)
      insertSplatAnnotation(title = "Annotation 2", position = position2)

      service.setSplatAnnotations(observationId, fileId, emptyList())

      assertEquals(
          emptyList<ExistingSplatAnnotationModel>(),
          service.listObservationSplatAnnotations(observationId, fileId),
      )
    }

    @Test
    fun `throws exception if file is not associated with observation`() {
      val otherFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.setSplatAnnotations(observationId, otherFileId, emptyList())
      }
    }

    @Test
    fun `throws exception if splat does not exist for file`() {
      val fileWithoutSplat = insertFile()
      insertObservationMediaFile(fileId = fileWithoutSplat)

      assertThrows<FileNotFoundException> {
        service.setSplatAnnotations(observationId, fileWithoutSplat, emptyList())
      }
    }
  }
}
