package com.terraformation.backend.tracking

import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.OrganizationVideoUploadedEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SUPPORTED_MEDIA_TYPES
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext
import org.locationtech.jts.geom.Point
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.web.multipart.MultipartFile

@Named
class OrganizationMediaService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val muxService: MuxService,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  fun upload(
      organizationId: OrganizationId,
      file: MultipartFile,
      caption: String?,
  ): FileId {
    requirePermissions { createOrganizationMedia(organizationId) }

    val contentType = file.getPlainContentType(SUPPORTED_MEDIA_TYPES)
    val filename = file.getFilename("media")

    val fileId =
        fileService.storeFile(
            "organizationMedia",
            file.inputStream,
            FileMetadata.of(contentType, filename, file.size),
        ) { (fileId, _) ->
          dslContext
              .insertInto(ORGANIZATION_MEDIA_FILES)
              .set(ORGANIZATION_MEDIA_FILES.FILE_ID, fileId)
              .set(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID, organizationId)
              .set(ORGANIZATION_MEDIA_FILES.CAPTION, caption)
              .execute()
        }

    log.info("Stored media file $fileId for organization $organizationId")

    if (contentType.startsWith("video/")) {
      eventPublisher.publishEvent(OrganizationVideoUploadedEvent(fileId, organizationId))
    }

    return fileId
  }

  fun read(organizationId: OrganizationId, fileId: FileId): SizedInputStream {
    requirePermissions { readOrganizationMedia(organizationId) }
    ensureOrganizationFile(organizationId, fileId)

    return fileService.readFile(fileId)
  }

  fun readThumbnail(
      organizationId: OrganizationId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readOrganizationMedia(organizationId) }
    ensureOrganizationFile(organizationId, fileId)

    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun update(
      organizationId: OrganizationId,
      fileId: FileId,
      caption: String?,
      gpsCoordinates: Point?,
  ) {
    requirePermissions { updateOrganizationMedia(organizationId) }
    ensureOrganizationFile(organizationId, fileId)

    dslContext
        .update(ORGANIZATION_MEDIA_FILES)
        .set(ORGANIZATION_MEDIA_FILES.CAPTION, caption)
        .where(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(fileId))
        .execute()

    if (gpsCoordinates != null) {
      dslContext
          .update(FILES)
          .set(FILES.GEOLOCATION, gpsCoordinates)
          .where(FILES.ID.eq(fileId))
          .execute()
    }
  }

  fun delete(organizationId: OrganizationId, fileId: FileId) {
    requirePermissions { deleteOrganizationMedia(organizationId) }
    ensureOrganizationFile(organizationId, fileId)

    dslContext
        .deleteFrom(ORGANIZATION_MEDIA_FILES)
        .where(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(fileId))
        .execute()

    eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))

    log.info("Deleted media file $fileId from organization $organizationId")
  }

  fun getStream(organizationId: OrganizationId, fileId: FileId): MuxStreamModel {
    requirePermissions { readOrganizationMedia(organizationId) }
    ensureOrganizationFile(organizationId, fileId)

    return muxService.getMuxStream(fileId)
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    val fileIds =
        dslContext
            .select(ORGANIZATION_MEDIA_FILES.FILE_ID)
            .from(ORGANIZATION_MEDIA_FILES)
            .where(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.eq(event.organizationId))
            .fetch(ORGANIZATION_MEDIA_FILES.FILE_ID.asNonNullable())

    fileIds.forEach { delete(event.organizationId, it) }
  }

  private fun ensureOrganizationFile(organizationId: OrganizationId, fileId: FileId) {
    val exists =
        dslContext.fetchExists(
            ORGANIZATION_MEDIA_FILES,
            ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.eq(organizationId)
                .and(ORGANIZATION_MEDIA_FILES.FILE_ID.eq(fileId)),
        )

    if (!exists) {
      throw FileNotFoundException(fileId)
    }
  }
}
