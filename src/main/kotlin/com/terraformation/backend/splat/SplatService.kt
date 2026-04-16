package com.terraformation.backend.splat

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationVideoUploadedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.event.SplatMarkedNeedsAttentionEvent
import com.terraformation.backend.splat.sqs.SplatterRequestFileLocation
import com.terraformation.backend.splat.sqs.SplatterRequestMessage
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import io.awspring.cloud.sqs.operations.SqsTemplate
import jakarta.inject.Named
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.InstantSource
import kotlin.io.path.Path
import kotlin.io.path.pathString
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType

@ConditionalOnProperty("terraware.splatter.enabled")
@Named
class SplatService(
    private val clock: InstantSource,
    config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileStore: S3FileStore,
    private val parentStore: ParentStore,
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

  fun listObservationBirdnetResults(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId? = null,
      fileId: FileId? = null,
  ): List<ObservationBirdnetResultModel> {
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
            BIRDNET_RESULTS.ASSET_STATUS_ID,
            BIRDNET_RESULTS.FILE_ID,
            BIRDNET_RESULTS.RESULTS_STORAGE_URL,
        )
        .from(OBSERVATION_MEDIA_FILES)
        .join(BIRDNET_RESULTS)
        .on(OBSERVATION_MEDIA_FILES.FILE_ID.eq(BIRDNET_RESULTS.FILE_ID))
        .where(conditions)
        .orderBy(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID, OBSERVATION_MEDIA_FILES.FILE_ID)
        .fetch { ObservationBirdnetResultModel.of(it) }
  }

  fun readObservationSplat(observationId: ObservationId, fileId: FileId): SizedInputStream {
    ensureObservationFile(observationId, fileId)

    return readSplat(fileId)
  }

  fun generateObservationSplat(
      observationId: ObservationId,
      fileId: FileId,
      force: Boolean = false,
      params: SplatGenerationParams = SplatGenerationParams(),
      runBirdnet: Boolean = true,
  ) {
    ensureObservationFile(observationId, fileId)

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    generateSplat(organizationId, fileId, force, params, runBirdnet)
  }

  fun generateOrganizationMediaSplat(
      organizationId: OrganizationId,
      fileId: FileId,
      force: Boolean = false,
      params: SplatGenerationParams = SplatGenerationParams(),
      runBirdnet: Boolean = true,
  ) {
    ensureOrganizationMediaFile(organizationId, fileId)

    generateSplat(organizationId, fileId, force, params, runBirdnet)
  }

  fun readOrganizationSplat(organizationId: OrganizationId, fileId: FileId): SizedInputStream {
    ensureOrganizationMediaFile(organizationId, fileId)

    return readSplat(fileId)
  }

  fun getOrganizationSplatInfo(
      organizationId: OrganizationId,
      fileId: FileId,
  ): SplatInfoModel {
    ensureOrganizationMediaFile(organizationId, fileId)

    return getSplatInfo(fileId)
  }

  fun setOrganizationSplatAnnotations(
      organizationId: OrganizationId,
      fileId: FileId,
      annotations: List<SplatAnnotationModel<*>>,
  ) {
    ensureOrganizationMediaFile(organizationId, fileId)
    ensureSplat(fileId)

    setSplatAnnotations(fileId, annotations)
  }

  fun setOrganizationSplatNeedsAttention(
      organizationId: OrganizationId,
      fileId: FileId,
      needsAttention: Boolean,
  ) {
    requirePermissions { updateOrganizationMedia(organizationId) }
    ensureOrganizationMediaFile(organizationId, fileId)
    ensureSplat(fileId)

    // The requirements only call for being able to set the flag; there's no process defined to
    // clear it. So for now, we only support the false -> true transition, but our API is already
    // structured to support true -> false if/when the behavior of that transition is defined.
    if (needsAttention) {
      val rowsUpdated =
          dslContext
              .update(SPLATS)
              .set(SPLATS.NEEDS_ATTENTION, true)
              .where(SPLATS.FILE_ID.eq(fileId))
              .and(SPLATS.NEEDS_ATTENTION.eq(false))
              .execute()

      if (rowsUpdated > 0) {
        eventPublisher.publishEvent(SplatMarkedNeedsAttentionEvent(fileId, organizationId))
      }
    } else {
      log.warn("Ignoring attempt to clear needs-attention flag")
    }
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

  fun getObservationSplatInfo(
      observationId: ObservationId,
      fileId: FileId,
  ): SplatInfoModel {
    ensureObservationFile(observationId, fileId)

    return getSplatInfo(fileId)
  }

  fun recordBirdnetError(fileId: FileId, errorMessage: String) {
    log.error("BirdNet generation failed for file $fileId: $errorMessage")

    with(BIRDNET_RESULTS) {
      dslContext
          .update(BIRDNET_RESULTS)
          .set(ASSET_STATUS_ID, AssetStatus.Errored)
          .set(COMPLETED_TIME, clock.instant())
          .set(ERROR_MESSAGE, errorMessage)
          .where(FILE_ID.eq(fileId))
          .execute()
    }
  }

  fun recordBirdnetSuccess(fileId: FileId) {
    log.info("BirdNet generation completed for file $fileId")

    with(BIRDNET_RESULTS) {
      dslContext
          .update(BIRDNET_RESULTS)
          .set(ASSET_STATUS_ID, AssetStatus.Ready)
          .set(COMPLETED_TIME, clock.instant())
          .where(FILE_ID.eq(fileId))
          .execute()
    }
  }

  fun setObservationSplatAnnotations(
      observationId: ObservationId,
      fileId: FileId,
      annotations: List<SplatAnnotationModel<*>>,
  ) {
    ensureObservationFile(observationId, fileId)
    ensureSplat(fileId)

    setSplatAnnotations(fileId, annotations)
  }

  private fun generateSplat(
      organizationId: OrganizationId,
      fileId: FileId,
      force: Boolean = false,
      params: SplatGenerationParams,
      runBirdnet: Boolean = false,
  ) {
    val videoUrl =
        dslContext.fetchValue(FILES.STORAGE_URL, FILES.ID.eq(fileId))
            ?: throw FileNotFoundException(fileId)
    val videoKey = videoUrl.path.trimStart('/')
    val videoPath = fileStore.getPath(videoUrl)
    val splatPath = Path(videoPath.pathString.substringBeforeLast('.') + splatFileExtension)
    val splatUrl = fileStore.getUrl(splatPath)
    val splatKey = splatUrl.path.trimStart('/')

    val birdnetUrl =
        if (runBirdnet) {
          val birdnetPath = Path(videoPath.pathString.substringBeforeLast('.') + "_birdnet.json")
          fileStore.getUrl(birdnetPath)
        } else {
          null
        }

    val birdnetOutputLocation = birdnetUrl?.let {
      val birdnetKey = it.path.trimStart('/')
      SplatterRequestFileLocation(s3BucketName, birdnetKey)
    }

    dslContext.transaction { _ ->
      val rowsInserted =
          with(SPLATS) {
            dslContext
                .insertInto(SPLATS)
                .set(ASSET_STATUS_ID, AssetStatus.Preparing)
                .set(CREATED_BY, currentUser().userId)
                .set(CREATED_TIME, clock.instant())
                .set(FILE_ID, fileId)
                .set(NEEDS_ATTENTION, false)
                .set(ORGANIZATION_ID, organizationId)
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

      if (runBirdnet && birdnetUrl != null) {
        val birdnetRowsInserted =
            with(BIRDNET_RESULTS) {
              dslContext
                  .insertInto(BIRDNET_RESULTS)
                  .set(ASSET_STATUS_ID, AssetStatus.Preparing)
                  .set(CREATED_BY, currentUser().userId)
                  .set(CREATED_TIME, clock.instant())
                  .set(FILE_ID, fileId)
                  .set(RESULTS_STORAGE_URL, birdnetUrl)
                  .onConflictDoNothing()
                  .execute()
            }

        if (birdnetRowsInserted == 0 && force) {
          with(BIRDNET_RESULTS) {
            dslContext
                .update(BIRDNET_RESULTS)
                .set(ASSET_STATUS_ID, AssetStatus.Preparing)
                .where(FILE_ID.eq(fileId))
                .execute()
          }
        }
      }

      if (rowsInserted == 1 || force) {
        val requestMessage =
            SplatterRequestMessage(
                abortAfter = params.abortAfter,
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
                restartAt = params.restartAt,
                restoreJob = params.restartAt != null,
                stepArgs = params.stepArgs,
                birdnetOutput = birdnetOutputLocation,
            )

        sqsTemplate.send(requestQueueUrl, requestMessage)

        log.info(
            "Requested splat generation for file $fileId${if (runBirdnet) " with BirdNet" else ""}"
        )
      } else {
        log.info(
            "Splat record already exists for file $fileId; ignoring additional generation request"
        )
      }
    }
  }

  @EventListener
  fun on(event: FileDeletionStartedEvent) {
    try {
      val splatsRecord = dslContext.fetchOne(SPLATS, SPLATS.FILE_ID.eq(event.fileId)) ?: return

      splatsRecord.splatStorageUrl?.let { splatStorageUrl ->
        try {
          fileStore.delete(splatStorageUrl)
        } catch (_: NoSuchFileException) {
          log.warn("Splats table referred to $splatStorageUrl which does not exist")
        }

        try {
          fileStore.delete(URI("$splatStorageUrl$JOB_ARCHIVE_SUFFIX"))
        } catch (_: NoSuchFileException) {
          // Not an error if there wasn't a job archive for the splat.
        }

        splatsRecord.delete()
      }
    } catch (e: Exception) {
      log.error("Unable to delete splat for file ${event.fileId}", e)
    }
  }

  @EventListener
  fun on(event: OrganizationVideoUploadedEvent) {
    try {
      generateOrganizationMediaSplat(event.organizationId, event.fileId)
    } catch (e: Exception) {
      log.error("Failed to auto-generate splat for file ${event.fileId}", e)
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

  private fun getSplatInfo(fileId: FileId): SplatInfoModel {
    ensureSplat(fileId)

    val annotations = listSplatAnnotations(fileId)
    val (originPosition, cameraPosition) = getSplatPositions(fileId)

    return SplatInfoModel(
        annotations = annotations,
        cameraPosition = cameraPosition,
        originPosition = originPosition,
    )
  }

  private fun getSplatPositions(fileId: FileId): Pair<CoordinateModel?, CoordinateModel?> {
    with(SPLATS) {
      val record =
          dslContext
              .select(
                  CAMERA_POSITION_X,
                  CAMERA_POSITION_Y,
                  CAMERA_POSITION_Z,
                  ORIGIN_POSITION_X,
                  ORIGIN_POSITION_Y,
                  ORIGIN_POSITION_Z,
              )
              .from(SPLATS)
              .where(FILE_ID.eq(fileId))
              .fetchOne()

      val cameraPosition =
          record?.get(CAMERA_POSITION_X)?.let {
            CoordinateModel(
                it,
                record[CAMERA_POSITION_Y]!!,
                record[CAMERA_POSITION_Z]!!,
            )
          }

      val originPosition =
          record?.get(ORIGIN_POSITION_X)?.let {
            CoordinateModel(
                it,
                record[ORIGIN_POSITION_Y]!!,
                record[ORIGIN_POSITION_Z]!!,
            )
          }

      return Pair(originPosition, cameraPosition)
    }
  }

  private fun listSplatAnnotations(
      fileId: FileId,
  ): List<ExistingSplatAnnotationModel> {
    with(SPLAT_ANNOTATIONS) {
      return dslContext
          .select(
              ID,
              FILE_ID,
              TITLE,
              BODY_TEXT,
              LABEL,
              POSITION_X,
              POSITION_Y,
              POSITION_Z,
              CAMERA_POSITION_X,
              CAMERA_POSITION_Y,
              CAMERA_POSITION_Z,
          )
          .from(SPLAT_ANNOTATIONS)
          .where(FILE_ID.eq(fileId))
          .fetch { record -> SplatAnnotationModel.of(record) }
    }
  }

  private fun setSplatAnnotations(fileId: FileId, annotations: List<SplatAnnotationModel<*>>) {
    dslContext.transaction { _ ->
      val now = clock.instant()
      val userId = currentUser().userId

      val annotationsWithIds = annotations.mapNotNull { annotation ->
        annotation.id?.let { it to annotation }
      }
      val annotationsWithoutIds = annotations.filter { it.id == null }
      val requestedIds = annotationsWithIds.map { it.first }.toSet()

      annotationsWithIds.forEach { (id, annotation) ->
        with(SPLAT_ANNOTATIONS) {
          dslContext
              .update(SPLAT_ANNOTATIONS)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(TITLE, annotation.title)
              .set(BODY_TEXT, annotation.bodyText)
              .set(LABEL, annotation.label)
              .set(POSITION_X, annotation.position.x)
              .set(POSITION_Y, annotation.position.y)
              .set(POSITION_Z, annotation.position.z)
              .set(CAMERA_POSITION_X, annotation.cameraPosition?.x)
              .set(CAMERA_POSITION_Y, annotation.cameraPosition?.y)
              .set(CAMERA_POSITION_Z, annotation.cameraPosition?.z)
              .where(ID.eq(id).and(FILE_ID.eq(fileId)))
              .execute()
        }
      }

      with(SPLAT_ANNOTATIONS) {
        val deleteCondition =
            if (requestedIds.isEmpty()) {
              FILE_ID.eq(fileId)
            } else {
              FILE_ID.eq(fileId).and(ID.notIn(requestedIds))
            }
        dslContext.deleteFrom(SPLAT_ANNOTATIONS).where(deleteCondition).execute()
      }

      if (annotationsWithoutIds.isNotEmpty()) {
        with(SPLAT_ANNOTATIONS) {
          val insertQuery =
              dslContext.insertInto(
                  SPLAT_ANNOTATIONS,
                  FILE_ID,
                  CREATED_BY,
                  CREATED_TIME,
                  MODIFIED_BY,
                  MODIFIED_TIME,
                  TITLE,
                  BODY_TEXT,
                  LABEL,
                  POSITION_X,
                  POSITION_Y,
                  POSITION_Z,
                  CAMERA_POSITION_X,
                  CAMERA_POSITION_Y,
                  CAMERA_POSITION_Z,
              )

          annotationsWithoutIds.forEach { annotation ->
            insertQuery.values(
                fileId,
                userId,
                now,
                userId,
                now,
                annotation.title,
                annotation.bodyText,
                annotation.label,
                annotation.position.x,
                annotation.position.y,
                annotation.position.z,
                annotation.cameraPosition?.x,
                annotation.cameraPosition?.y,
                annotation.cameraPosition?.z,
            )
          }

          insertQuery.execute()
        }
      }
    }
  }

  private fun ensureSplat(fileId: FileId) {
    val splatExists = dslContext.fetchExists(SPLATS, SPLATS.FILE_ID.eq(fileId))

    if (!splatExists) {
      throw FileNotFoundException(fileId)
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

  private fun ensureOrganizationMediaFile(organizationId: OrganizationId, fileId: FileId) {
    requirePermissions { readOrganizationMedia(organizationId) }

    val associationExists =
        dslContext.fetchExists(
            ORGANIZATION_MEDIA_FILES,
            ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.eq(organizationId)
                .and(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(fileId)),
        )

    if (!associationExists) {
      throw FileNotFoundException(fileId)
    }
  }

  companion object {
    const val JOB_ARCHIVE_SUFFIX = "-job.tar.gz"
  }
}
