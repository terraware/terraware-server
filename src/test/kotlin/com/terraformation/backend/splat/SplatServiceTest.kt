package com.terraformation.backend.splat

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Coordinate

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
  inner class ListSplatAnnotations {
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
      val position1 = Coordinate(1.0, 2.0, 3.0)
      val cameraPosition1 = Coordinate(4.0, 5.0, 6.0)
      insertSplatAnnotation(
          title = "Test Annotation 1",
          text = "Description 1",
          label = "Label 1",
          position = position1,
          cameraPosition = cameraPosition1,
      )

      val position2 = Coordinate(7.0, 8.0, 9.0)
      insertSplatAnnotation(title = "Test Annotation 2", position = position2)

      val result = service.listSplatAnnotations(observationId, fileId)

      assertEquals(2, result.size, "Number of annotations")
      assertEquals("Test Annotation 1", result[0].title)
      assertEquals("Description 1", result[0].text)
      assertEquals("Label 1", result[0].label)
      assertEquals(position1, result[0].position.coordinate)
      assertEquals(cameraPosition1, result[0].cameraPosition?.coordinate)

      assertEquals("Test Annotation 2", result[1].title)
      assertNull(result[1].text)
      assertNull(result[1].label)
      assertEquals(position2, result[1].position.coordinate)
      assertNull(result[1].cameraPosition)
    }

    @Test
    fun `returns empty list when no annotations exist`() {
      val result = service.listSplatAnnotations(observationId, fileId)

      assertEquals(0, result.size, "Number of annotations")
    }

    @Test
    fun `throws exception if file is not associated with observation`() {
      val otherFileId = insertFile()

      assertThrows<FileNotFoundException> {
        service.listSplatAnnotations(observationId, otherFileId)
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
        service.listSplatAnnotations(otherObservationId, otherFileId)
      }
    }

    @Test
    fun `throws exception if splat does not exist for file`() {
      val fileWithoutSplat = insertFile()
      insertObservationMediaFile(fileId = fileWithoutSplat)

      assertThrows<FileNotFoundException> {
        service.listSplatAnnotations(observationId, fileWithoutSplat)
      }
    }
  }
}
