package com.terraformation.backend.splat

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.sqs.SplatterRequestFileLocation
import com.terraformation.backend.splat.sqs.SplatterRequestMessage
import io.awspring.cloud.sqs.operations.SqsTemplate
import jakarta.inject.Named
import java.time.InstantSource
import kotlin.io.path.Path
import kotlin.io.path.pathString
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType

@ConditionalOnProperty("terraware.splatter.enabled")
@Named
class SplatService(
    private val clock: InstantSource,
    config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val fileStore: S3FileStore,
    private val sqsTemplate: SqsTemplate,
) {
  private val log = perClassLogger()

  private val splatFileExtension = ".sog"
  private val splatMimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE

  private val requestQueueUrl = requireNotNull(config.splatter.requestQueueUrl).toString()
  private val responseQueueUrl = requireNotNull(config.splatter.responseQueueUrl)
  private val s3BucketName = requireNotNull(config.s3BucketName)

  fun listObservationSplats(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId? = null,
      fileId: FileId? = null,
  ): List<ObservationSplatModel> {
    requirePermissions { readObservation(observationId) }

    val conditions =
        with(OBSERVATION_MEDIA_FILES) {
          listOfNotNull(
              OBSERVATION_ID.eq(observationId),
              monitoringPlotId?.let { MONITORING_PLOT_ID.eq(monitoringPlotId) },
              fileId?.let { FILE_ID.eq(fileId) },
          )
        }

    return dslContext
        .select(
            OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID,
            OBSERVATION_MEDIA_FILES.OBSERVATION_ID,
            SPLATS.ASSET_STATUS_ID,
            SPLATS.FILE_ID,
        )
        .from(OBSERVATION_MEDIA_FILES)
        .join(SPLATS)
        .on(OBSERVATION_MEDIA_FILES.FILE_ID.eq(SPLATS.FILE_ID))
        .where(conditions)
        .orderBy(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID, OBSERVATION_MEDIA_FILES.FILE_ID)
        .fetch { ObservationSplatModel.of(it) }
  }

  fun readObservationSplat(observationId: ObservationId, fileId: FileId): SizedInputStream {
    ensureObservationFile(observationId, fileId)

    return readSplat(fileId)
  }

  fun generateObservationSplat(
      observationId: ObservationId,
      fileId: FileId,
      force: Boolean = false,
      processScriptArgs: List<String>? = null,
  ) {
    ensureObservationFile(observationId, fileId)

    generateSplat(fileId, force, processScriptArgs)
  }

  fun recordSplatError(fileId: FileId, errorMessage: String) {
    log.error("Splat generation failed for file $fileId: $errorMessage")

    with(SPLATS) {
      dslContext
          .update(SPLATS)
          .set(ASSET_STATUS_ID, AssetStatus.Errored)
          .set(COMPLETED_TIME, clock.instant())
          .set(ERROR_MESSAGE, errorMessage)
          .where(FILE_ID.eq(fileId))
          .execute()
    }
  }

  fun recordSplatSuccess(fileId: FileId) {
    log.info("Splat generation completed for file $fileId")

    with(SPLATS) {
      dslContext
          .update(SPLATS)
          .set(ASSET_STATUS_ID, AssetStatus.Ready)
          .set(COMPLETED_TIME, clock.instant())
          .where(FILE_ID.eq(fileId))
          .execute()
    }
  }

  private fun generateSplat(
      fileId: FileId,
      force: Boolean = false,
      processScriptArgs: List<String>?,
  ) {
    val videoUrl =
        dslContext.fetchValue(FILES.STORAGE_URL, FILES.ID.eq(fileId))
            ?: throw FileNotFoundException(fileId)
    val videoKey = videoUrl.path.trimStart('/')
    val videoPath = fileStore.getPath(videoUrl)
    val splatPath = Path(videoPath.pathString.substringBeforeLast('.') + splatFileExtension)
    val splatUrl = fileStore.getUrl(splatPath)
    val splatKey = splatUrl.path.trimStart('/')

    dslContext.transaction { _ ->
      val rowsInserted =
          with(SPLATS) {
            dslContext
                .insertInto(SPLATS)
                .set(ASSET_STATUS_ID, AssetStatus.Preparing)
                .set(CREATED_BY, currentUser().userId)
                .set(CREATED_TIME, clock.instant())
                .set(FILE_ID, fileId)
                .set(SPLAT_STORAGE_URL, splatUrl)
                .onConflictDoNothing()
                .execute()
          }

      if (rowsInserted == 0 && force) {
        with(SPLATS) {
          dslContext
              .update(SPLATS)
              .set(ASSET_STATUS_ID, AssetStatus.Preparing)
              .where(FILE_ID.eq(fileId))
              .execute()
        }
      }

      if (rowsInserted == 1 || force) {
        val requestMessage =
            SplatterRequestMessage(
                args = processScriptArgs,
                input =
                    SplatterRequestFileLocation(
                        s3BucketName,
                        videoKey,
                    ),
                jobId = fileId.toString(),
                output =
                    SplatterRequestFileLocation(
                        s3BucketName,
                        splatKey,
                    ),
                responseQueueUrl = responseQueueUrl,
            )

        sqsTemplate.send(requestQueueUrl, requestMessage)

        log.info("Requested splat generation for file $fileId")
      } else {
        log.info(
            "Splat record already exists for file $fileId; ignoring additional generation request"
        )
      }
    }
  }

  private fun readSplat(fileId: FileId): SizedInputStream {
    val splatsRecord =
        dslContext.fetchOne(SPLATS, SPLATS.FILE_ID.eq(fileId))
            ?: throw FileNotFoundException(fileId)

    return when (splatsRecord.assetStatusId!!) {
      AssetStatus.Errored -> throw SplatGenerationFailedException(fileId)
      AssetStatus.Preparing -> throw SplatNotReadyException(fileId)
      AssetStatus.Ready ->
          fileStore.read(splatsRecord.splatStorageUrl!!).withContentType(splatMimeType)
    }
  }

  private fun ensureObservationFile(observationId: ObservationId, fileId: FileId) {
    requirePermissions { readObservation(observationId) }

    val associationExists =
        dslContext.fetchExists(
            OBSERVATION_MEDIA_FILES,
            OBSERVATION_MEDIA_FILES.OBSERVATION_ID.eq(observationId)
                .and(OBSERVATION_MEDIA_FILES.FILE_ID.eq(fileId)),
        )

    if (!associationExists) {
      throw FileNotFoundException(fileId)
    }
  }
}
