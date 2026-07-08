package com.terraformation.backend.splat

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationVideoUploadedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileBatchNotFoundException
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileBatchType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.FILE_BATCHES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.file.S3FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.event.FileBatchFinishedUploadingEvent
import com.terraformation.backend.file.event.FileDeletionStartedEvent
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.splat.event.SplatDeletedEvent
import com.terraformation.backend.splat.event.SplatGenerationCompletedEvent
import com.terraformation.backend.splat.event.SplatGenerationFailedEvent
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
      dataFileId: FileId? = null,
  ) {
    ensureOrganizationMediaFile(organizationId, fileId)

    generateSplat(organizationId, fileId, force, params, runBirdnet, dataFileId)
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

  fun setObservationSplatNeedsAttention(
      observationId: ObservationId,
      fileId: FileId,
      needsAttention: Boolean,
  ) {
    requirePermissions { updateObservation(observationId) }
    ensureObservationFile(observationId, fileId)
    ensureSplat(fileId)

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    setSplatNeedsAttention(organizationId, fileId, needsAttention)
  }

  fun setOrganizationSplatNeedsAttention(
      organizationId: OrganizationId,
      fileId: FileId,
      needsAttention: Boolean,
  ) {
    requirePermissions { updateOrganizationMedia(organizationId) }
    ensureOrganizationMediaFile(organizationId, fileId)
    ensureSplat(fileId)

    setSplatNeedsAttention(organizationId, fileId, needsAttention)
  }

  fun deleteObservationSplat(observationId: ObservationId, fileId: FileId) {
    requirePermissions { updateObservation(observationId) }
    ensureObservationFile(observationId, fileId)
    ensureSplat(fileId)

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    deleteSplatRows(fileId, organizationId)
  }

  fun deleteOrganizationSplat(organizationId: OrganizationId, fileId: FileId) {
    requirePermissions { updateOrganizationMedia(organizationId) }
    ensureOrganizationMediaFile(organizationId, fileId)
    ensureSplat(fileId)

    deleteSplatRows(fileId, organizationId)
  }

  private fun deleteSplatRows(fileId: FileId, organizationId: OrganizationId) {
    val splatRecord = dslContext.fetchSingle(SPLATS, SPLATS.FILE_ID.eq(fileId))

    dslContext.transaction { _ ->
      dslContext.deleteFrom(SPLAT_ANNOTATIONS).where(SPLAT_ANNOTATIONS.FILE_ID.eq(fileId)).execute()
      dslContext.deleteFrom(BIRDNET_RESULTS).where(BIRDNET_RESULTS.FILE_ID.eq(fileId)).execute()
      dslContext.deleteFrom(SPLATS).where(SPLATS.FILE_ID.eq(fileId)).execute()
    }

    eventPublisher.publishEvent(
        SplatDeletedEvent(
            deletedByUserId = currentUser().userId,
            fileId = fileId,
            organizationId = organizationId,
            uploadedByUserId = splatRecord.createdBy!!,
            videoUploadedTime = splatRecord.createdTime!!,
        )
    )
  }

  fun recordSplatError(fileId: FileId, errorMessage: String) {
    log.error("Splat generation failed for file $fileId: $errorMessage")

    val splatRecord = dslContext.fetchOne(SPLATS, SPLATS.FILE_ID.eq(fileId))
    if (splatRecord == null) {
      log.warn(
          "No splats row found for file $fileId; might have been deleted before processing was done"
      )
      return
    }

    splatRecord.assetStatusId = AssetStatus.Errored
    splatRecord.completedTime = clock.instant()
    splatRecord.errorMessage = errorMessage
    splatRecord.update()

    eventPublisher.publishEvent(
        SplatGenerationFailedEvent(
            fileId = fileId,
            organizationId = splatRecord.organizationId!!,
            uploadedByUserId = splatRecord.createdBy!!,
            videoUploadedTime = splatRecord.createdTime!!,
        )
    )
  }

  fun recordSplatSuccess(fileId: FileId, modelMetadata: ModelMetadataModel? = null) {
    log.info("Splat generation completed for file $fileId")

    val splatRecord = dslContext.fetchOne(SPLATS, SPLATS.FILE_ID.eq(fileId))
    if (splatRecord == null) {
      log.warn(
          "No splats row found for file $fileId; might have been deleted before processing was done"
      )
      return
    }

    with(SPLATS) {
      val skyColor =
          modelMetadata?.skyColor?.let {
            if (isValidHexColor(it)) it
            else {
              log.warn("Invalid sky color $it for file $fileId")
              null
            }
          }

      val groundColor =
          modelMetadata?.groundColor?.let {
            if (isValidHexColor(it)) it
            else {
              log.warn("Invalid ground color $it for file $fileId")
              null
            }
          }

      dslContext
          .update(SPLATS)
          .set(ASSET_STATUS_ID, AssetStatus.Ready)
          .set(COMPLETED_TIME, clock.instant())
          .let { step ->
            if (modelMetadata != null)
                step
                    .set(AVERAGE_CAMERA_HEIGHT, modelMetadata.averageCameraHeight)
                    .set(SKY_COLOR, skyColor)
                    .set(GROUND_COLOR, groundColor)
            else step
          }
          .let { step ->
            if (modelMetadata?.sceneBounds != null)
                step.set(SCENE_BOUNDS, modelMetadata.sceneBounds.toPointField())
            else step
          }
          .let { step ->
            if (modelMetadata?.groundPlane != null)
                step.set(GROUND_PLANE, modelMetadata.groundPlane.toMultiPointField())
            else step
          }
          .where(FILE_ID.eq(fileId))
          .execute()
    }

    eventPublisher.publishEvent(
        SplatGenerationCompletedEvent(
            fileId = fileId,
            organizationId = splatRecord.organizationId!!,
            uploadedByUserId = splatRecord.createdBy!!,
            videoUploadedTime = splatRecord.createdTime!!,
        )
    )
  }

  private fun isValidHexColor(color: String?): Boolean =
      color == null || color.matches(Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$"))

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
      dataFileId: FileId? = null,
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

    val dataFileLocation = dataFileId?.let { id ->
      val dataFileUrl =
          dslContext.fetchValue(FILES.STORAGE_URL, FILES.ID.eq(id))
              ?: throw FileNotFoundException(id)
      SplatterRequestFileLocation(s3BucketName, dataFileUrl.path.trimStart('/'))
    }

    dslContext.transaction { _ ->
      val rowsInserted =
          with(SPLATS) {
            dslContext
                .insertInto(SPLATS)
                .set(ASSET_STATUS_ID, AssetStatus.Preparing)
                .set(CREATED_BY, currentUser().userId)
                .set(CREATED_TIME, clock.instant())
                .set(DATA_FILE_ID, dataFileId)
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
                birdnetOutput = birdnetOutputLocation,
                dataFile = dataFileLocation,
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
  fun on(event: FileBatchFinishedUploadingEvent) {
    val batchType =
        dslContext.fetchValue(FILE_BATCHES.BATCH_TYPE_ID, FILE_BATCHES.ID.eq(event.fileBatchId))
            ?: throw FileBatchNotFoundException(event.fileBatchId)
    if (batchType != FileBatchType.Splat) {
      // only generate splat for Splats batch types that finish uploading
      return
    }

    val batchFiles =
        dslContext
            .select(FILES.ID, FILES.CONTENT_TYPE)
            .from(FILES)
            .where(FILES.FILE_BATCH_ID.eq(event.fileBatchId))
            .fetch()

    val videoFileId =
        batchFiles.firstOrNull { it[FILES.CONTENT_TYPE]?.startsWith("video/") == true }?.value1()

    if (videoFileId == null) {
      log.warn(
          "File batch ${event.fileBatchId} does not contain a video file; not generating splat"
      )
      return
    }

    val organizationId =
        dslContext
            .select(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID)
            .from(ORGANIZATION_MEDIA_FILES)
            .where(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(videoFileId))
            .fetchOne(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID) ?: return

    if (batchFiles.size > 2) {
      log.warn("File batch ${event.fileBatchId} contains more than two files; not generating splat")
      return
    }

    val jsonFileId =
        batchFiles
            .firstOrNull { it[FILES.CONTENT_TYPE] == MediaType.APPLICATION_JSON_VALUE }
            ?.value1()
    if (jsonFileId == null) {
      log.warn(
          "File batch ${event.fileBatchId} contains an organization video but no JSON data file; " +
              "not generating splat"
      )
      return
    }

    try {
      generateOrganizationMediaSplat(organizationId, videoFileId, dataFileId = jsonFileId)
    } catch (e: Exception) {
      log.error(
          "Failed to auto-generate splat for file $videoFileId in batch ${event.fileBatchId}",
          e,
      )
    }
  }

  @EventListener
  fun on(event: OrganizationVideoUploadedEvent) {
    if (event.fileBatchId != null) {
      if (dslContext.fetchExists(FILE_BATCHES, FILE_BATCHES.ID.eq(event.fileBatchId))) {
        // Video was part of a batch; don't auto-generate splat
        return
      } else {
        throw FileBatchNotFoundException(event.fileBatchId)
      }
    }

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

    with(SPLATS) {
      val record =
          dslContext
              .select(
                  AVERAGE_CAMERA_HEIGHT,
                  CAMERA_POSITION,
                  GROUND_COLOR,
                  GROUND_PLANE,
                  ORIGIN_POSITION,
                  SCENE_BOUNDS,
                  SKY_COLOR,
              )
              .from(SPLATS)
              .where(FILE_ID.eq(fileId))
              .fetchOne()!!

      val cameraPosition = CoordinateModel.of(record, CAMERA_POSITION)
      val originPosition = CoordinateModel.of(record, ORIGIN_POSITION)
      val sceneBounds = CoordinateModel.of(record, SCENE_BOUNDS)
      val groundPlane = CoordinateModel.ofList(record, GROUND_PLANE)
      val skyColor = record[SKY_COLOR]
      val groundColor = record[GROUND_COLOR]
      val averageCameraHeight = record[AVERAGE_CAMERA_HEIGHT]

      return SplatInfoModel(
          annotations = annotations,
          averageCameraHeight = averageCameraHeight,
          cameraPosition = cameraPosition,
          groundColor = groundColor,
          groundPlane = groundPlane,
          originPosition = originPosition,
          sceneBounds = sceneBounds,
          skyColor = skyColor,
      )
    }
  }

  private fun listSplatAnnotations(
      fileId: FileId,
  ): List<ExistingSplatAnnotationModel> {
    with(SPLAT_ANNOTATIONS) {
      return dslContext
          .select(ID, FILE_ID, TITLE, BODY_TEXT, LABEL, POSITION, CAMERA_POSITION)
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
              .set(POSITION, annotation.position.toPointField())
              .set(CAMERA_POSITION, annotation.cameraPosition.toPointField())
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

      annotationsWithoutIds.forEach { annotation ->
        with(SPLAT_ANNOTATIONS) {
          dslContext
              .insertInto(SPLAT_ANNOTATIONS)
              .set(FILE_ID, fileId)
              .set(CREATED_BY, userId)
              .set(CREATED_TIME, now)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(TITLE, annotation.title)
              .set(BODY_TEXT, annotation.bodyText)
              .set(LABEL, annotation.label)
              .set(POSITION, annotation.position.toPointField())
              .set(CAMERA_POSITION, annotation.cameraPosition.toPointField())
              .execute()
        }
      }
    }
  }

  private fun setSplatNeedsAttention(
      organizationId: OrganizationId,
      fileId: FileId,
      needsAttention: Boolean,
  ) {
    val rowsUpdated =
        dslContext
            .update(SPLATS)
            .set(SPLATS.NEEDS_ATTENTION, needsAttention)
            .where(SPLATS.FILE_ID.eq(fileId))
            .and(SPLATS.NEEDS_ATTENTION.ne(needsAttention))
            .execute()

    if (needsAttention && rowsUpdated > 0) {
      val splatRecord = dslContext.fetchSingle(SPLATS, SPLATS.FILE_ID.eq(fileId))
      eventPublisher.publishEvent(
          SplatMarkedNeedsAttentionEvent(
              fileId = fileId,
              markedByUserId = currentUser().userId,
              organizationId = organizationId,
              uploadedByUserId = splatRecord.createdBy!!,
              videoUploadedTime = splatRecord.createdTime!!,
          )
      )
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
