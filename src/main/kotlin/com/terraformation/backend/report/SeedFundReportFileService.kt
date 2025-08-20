package com.terraformation.backend.report

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportFilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.event.SeedFundReportDeletionStartedEvent
import com.terraformation.backend.report.model.SeedFundReportFileModel
import com.terraformation.backend.report.model.SeedFundReportPhotoModel
import jakarta.inject.Named
import java.io.InputStream
import org.springframework.context.event.EventListener

@Named
class SeedFundReportFileService(
    private val filesDao: FilesDao,
    private val fileService: FileService,
    private val seedFundReportStore: SeedFundReportStore,
    private val seedFundReportFilesDao: SeedFundReportFilesDao,
    private val seedFundReportPhotosDao: SeedFundReportPhotosDao,
) {
  private val log = perClassLogger()

  fun storeFile(reportId: SeedFundReportId, data: InputStream, metadata: NewFileMetadata): FileId {
    return store(reportId, data, metadata) { fileId ->
      seedFundReportFilesDao.insert(SeedFundReportFilesRow(fileId = fileId, reportId = reportId))
    }
  }

  fun storePhoto(reportId: SeedFundReportId, data: InputStream, metadata: NewFileMetadata): FileId {
    return store(reportId, data, metadata) { fileId ->
      seedFundReportPhotosDao.insert(SeedFundReportPhotosRow(fileId = fileId, reportId = reportId))
    }
  }

  fun readPhoto(
      reportId: SeedFundReportId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readSeedFundReport(reportId) }

    // Make sure the photo is owned by the report.
    fetchPhotosRow(reportId, fileId)

    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun readFile(reportId: SeedFundReportId, fileId: FileId): SizedInputStream {
    requirePermissions { readSeedFundReport(reportId) }

    // Make sure the file is owned by the report.
    fetchFilesRow(reportId, fileId)

    return fileService.readFile(fileId)
  }

  fun listPhotos(reportId: SeedFundReportId): List<SeedFundReportPhotoModel> {
    requirePermissions { readSeedFundReport(reportId) }

    val photosRows = seedFundReportPhotosDao.fetchByReportId(reportId)
    if (photosRows.isEmpty()) {
      return emptyList()
    }

    val fileIds = photosRows.mapNotNull { it.fileId }
    val filesRows = filesDao.fetchById(*fileIds.toTypedArray()).associateBy { it.id }

    return seedFundReportPhotosDao
        .fetchByReportId(reportId)
        .map { SeedFundReportPhotoModel(it, filesRows[it.fileId]!!) }
        .sortedBy { it.metadata.id }
  }

  fun listFiles(reportId: SeedFundReportId): List<SeedFundReportFileModel> {
    return seedFundReportStore.fetchFilesByReportId(reportId)
  }

  fun getFileModel(reportId: SeedFundReportId, fileId: FileId): SeedFundReportFileModel {
    return seedFundReportStore.fetchFileById(reportId, fileId)
  }

  fun getPhotoModel(reportId: SeedFundReportId, fileId: FileId): SeedFundReportPhotoModel {
    val photosRow = fetchPhotosRow(reportId, fileId)
    val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)

    return SeedFundReportPhotoModel(photosRow, filesRow)
  }

  fun updatePhoto(model: SeedFundReportPhotoModel) {
    requirePermissions { updateSeedFundReport(model.reportId) }

    val row = fetchPhotosRow(model.reportId, model.metadata.id)

    seedFundReportPhotosDao.update(row.copy(caption = model.caption))
  }

  fun deletePhoto(reportId: SeedFundReportId, fileId: FileId) {
    requirePermissions { updateSeedFundReport(reportId) }

    val row = fetchPhotosRow(reportId, fileId)

    fileService.deleteFile(fileId) { seedFundReportPhotosDao.delete(row) }
  }

  fun deleteFile(reportId: SeedFundReportId, fileId: FileId) {
    requirePermissions { updateSeedFundReport(reportId) }

    val row = fetchFilesRow(reportId, fileId)

    fileService.deleteFile(fileId) { seedFundReportFilesDao.delete(row) }
  }

  @EventListener
  fun on(event: SeedFundReportDeletionStartedEvent) {
    val reportId = event.reportId

    seedFundReportStore.fetchPhotosByReportId(reportId).forEach {
      deletePhoto(reportId, it.metadata.id)
    }
    seedFundReportStore.fetchFilesByReportId(reportId).forEach {
      deleteFile(reportId, it.metadata.id)
    }
  }

  /**
   * Returns the [SeedFundReportPhotosRow] for a specific photo on a specific report.
   *
   * @throws FileNotFoundException The photo with the requested file ID was not associated with the
   *   requested report.
   */
  private fun fetchPhotosRow(reportId: SeedFundReportId, fileId: FileId): SeedFundReportPhotosRow {
    val row = seedFundReportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  /**
   * Returns the [SeedFundReportFilesRow] for a specific file on a specific report.
   *
   * @throws FileNotFoundException The file with the requested file ID was not associated with the
   *   requested report.
   */
  private fun fetchFilesRow(reportId: SeedFundReportId, fileId: FileId): SeedFundReportFilesRow {
    val row = seedFundReportFilesDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  private fun store(
      reportId: SeedFundReportId,
      data: InputStream,
      metadata: NewFileMetadata,
      insertChildRow: (FileId) -> Unit,
  ): FileId {
    requirePermissions { updateSeedFundReport(reportId) }

    val fileId = fileService.storeFile("report", data, metadata, null, insertChildRow)

    log.info("Stored ${metadata.contentType} file $fileId for report $reportId")

    return fileId
  }
}
