package com.terraformation.backend.splat.sqs

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.ModelMetadataModel
import com.terraformation.backend.splat.SplatService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXYZM
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPoint
import org.locationtech.jts.geom.PrecisionModel

class SplatsSqsListenerTest {
  private val splatService: SplatService = mockk()
  private val listener = SplatsSqsListener(splatService)

  private val fileId = FileId(123)
  private val geometryFactory = GeometryFactory(PrecisionModel(), 0)

  private fun multiPoint(vararg coords: Coordinate): MultiPoint =
      geometryFactory.createMultiPointFromCoords(coords)

  @BeforeEach
  fun setUp() {
    every { splatService.recordSplatSuccess(any(), any()) } just Runs
    every { splatService.recordSplatError(any(), any()) } just Runs
    every { splatService.recordBirdnetSuccess(any()) } just Runs
    every { splatService.recordBirdnetError(any(), any()) } just Runs
  }

  @Nested
  inner class ReceiveSplatterResponse {
    @Test
    fun `records splat success when success is true`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify(exactly = 0) { splatService.recordSplatError(any(), any()) }
    }

    @Test
    fun `records splat error when success is false`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = "Test error",
              jobId = fileId,
              output = null,
              success = false,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "Test error") }
      verify(exactly = 0) { splatService.recordSplatSuccess(any(), any()) }
    }

    @Test
    fun `uses default error message when none provided`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = null,
              success = false,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "No error message received") }
    }

    @Test
    fun `passes model metadata to recordSplatSuccess when present`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      skyColor = "#AABBCC",
                      groundColor = "#112233",
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify {
        splatService.recordSplatSuccess(
            fileId,
            ModelMetadataModel(skyColor = "#AABBCC", groundColor = "#112233"),
        )
      }
    }

    @Test
    fun `passes scene bounds to recordSplatSuccess when present`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      sceneBounds =
                          GeometryFactory().createPoint(CoordinateXYZM(1.0, 2.0, 3.0, 4.0)),
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify {
        splatService.recordSplatSuccess(
            fileId,
            ModelMetadataModel(
                sceneBounds = CoordinateModel(1.0, 2.0, 3.0, 4.0),
            ),
        )
      }
    }

    @Test
    fun `skips scene bounds when fewer than 4 coordinates`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      sceneBounds = GeometryFactory().createPoint(Coordinate(1.0, 2.0, 3.0)),
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, ModelMetadataModel(sceneBounds = null)) }
    }

    @Test
    fun `passes ground plane to recordSplatSuccess when present`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      groundPlane =
                          multiPoint(
                              Coordinate(1.0, 2.0, 3.0),
                              Coordinate(4.0, 5.0, 6.0),
                              Coordinate(7.0, 8.0, 9.0),
                          ),
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify {
        splatService.recordSplatSuccess(
            fileId,
            ModelMetadataModel(
                groundPlane =
                    listOf(
                        CoordinateModel(1.0, 2.0, 3.0),
                        CoordinateModel(4.0, 5.0, 6.0),
                        CoordinateModel(7.0, 8.0, 9.0),
                    ),
            ),
        )
      }
    }

    @Test
    fun `skips ground plane when fewer than 3 points`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      groundPlane =
                          multiPoint(
                              Coordinate(1.0, 2.0, 3.0),
                              Coordinate(4.0, 5.0, 6.0),
                          ),
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, ModelMetadataModel(groundPlane = null)) }
    }

    @Test
    fun `skips ground plane when a point has fewer than 3 coordinates`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      groundPlane =
                          multiPoint(
                              Coordinate(1.0, 2.0, 3.0),
                              Coordinate(4.0, 5.0),
                              Coordinate(7.0, 8.0, 9.0),
                          ),
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, ModelMetadataModel(groundPlane = null)) }
    }

    @Test
    fun `passes null ground plane when absent from metadata`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, ModelMetadataModel()) }
    }

    @Test
    fun `passes average camera height to recordSplatSuccess when present`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      averageCameraHeight = BigDecimal("12.5"),
                      groundColor = null,
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify {
        splatService.recordSplatSuccess(
            fileId,
            ModelMetadataModel(averageCameraHeight = BigDecimal("12.5")),
        )
      }
    }

    @Test
    fun `passes null average camera height when absent from metadata`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              modelMetadata =
                  SplatterResponseModelMetadataPayload(
                      groundColor = null,
                      skyColor = null,
                  ),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, ModelMetadataModel()) }
    }

    @Test
    fun `passes null metadata to recordSplatSuccess when absent`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId, null) }
    }
  }

  @Nested
  inner class ReceiveBirdnetResponse {
    @Test
    fun `records BirdNet success when birdnetSuccess is true`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = true,
              birdnetOutput = SplatterResponseOutputPayload("bucket", "birdnet_key"),
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify { splatService.recordBirdnetSuccess(fileId) }
      verify(exactly = 0) { splatService.recordBirdnetError(any(), any()) }
    }

    @Test
    fun `records BirdNet error when birdnetSuccess is false`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = false,
              birdnetErrorMessage = "BirdNet test error",
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify { splatService.recordBirdnetError(fileId, "BirdNet test error") }
      verify(exactly = 0) { splatService.recordBirdnetSuccess(any()) }
    }

    @Test
    fun `uses default error message for BirdNet when none provided`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = false,
              birdnetErrorMessage = null,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordBirdnetError(fileId, "No error message received") }
    }

    @Test
    fun `does not process BirdNet when birdnetSuccess is null`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = null,
              jobId = fileId,
              output = SplatterResponseOutputPayload("bucket", "key"),
              success = true,
              birdnetSuccess = null,
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatSuccess(fileId) }
      verify(exactly = 0) { splatService.recordBirdnetSuccess(any()) }
      verify(exactly = 0) { splatService.recordBirdnetError(any(), any()) }
    }

    @Test
    fun `handles both splat and BirdNet failures independently`() {
      val payload =
          SplatterResponsePayload(
              errorMessage = "Splat error",
              jobId = fileId,
              output = null,
              success = false,
              birdnetSuccess = false,
              birdnetErrorMessage = "BirdNet error",
          )

      listener.receiveSplatterResponse(payload)

      verify { splatService.recordSplatError(fileId, "Splat error") }
      verify { splatService.recordBirdnetError(fileId, "BirdNet error") }
    }
  }
}
