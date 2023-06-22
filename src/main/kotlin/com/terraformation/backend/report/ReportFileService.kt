package com.terraformation.backend.report

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportFilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.event.ReportDeletionStartedEvent
import com.terraformation.backend.report.model.ReportFileModel
import com.terraformation.backend.report.model.ReportPhotoModel
import jakarta.inject.Named
import java.io.InputStream
import org.springframework.context.event.EventListener

@Named
class ReportFileService(
    private val filesDao: FilesDao,
    private val fileService: FileService,
    private val reportFilesDao: ReportFilesDao,
    private val reportPhotosDao: ReportPhotosDao,
    private val reportStore: ReportStore,
) {
  private val log = perClassLogger()

  fun storeFile(reportId: ReportId, data: InputStream, metadata: NewFileMetadata): FileId {
    return store(reportId, data, metadata) { fileId ->
      reportFilesDao.insert(ReportFilesRow(fileId = fileId, reportId = reportId))
    }
  }

  fun storePhoto(reportId: ReportId, data: InputStream, metadata: NewFileMetadata): FileId {
    return store(reportId, data, metadata) { fileId ->
      reportPhotosDao.insert(ReportPhotosRow(fileId = fileId, reportId = reportId))
    }
  }

  fun readPhoto(
      reportId: ReportId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    requirePermissions { readReport(reportId) }

    // Make sure the photo is owned by the report.
    fetchPhotosRow(reportId, fileId)

    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun readFile(reportId: ReportId, fileId: FileId): SizedInputStream {
    requirePermissions { readReport(reportId) }

    // Make sure the file is owned by the report.
    fetchFilesRow(reportId, fileId)

    return fileService.readFile(fileId)
  }

  fun listPhotos(reportId: ReportId): List<ReportPhotoModel> {
    requirePermissions { readReport(reportId) }

    val photosRows = reportPhotosDao.fetchByReportId(reportId)
    if (photosRows.isEmpty()) {
      return emptyList()
    }

    val fileIds = photosRows.mapNotNull { it.fileId }
    val filesRows = filesDao.fetchById(*fileIds.toTypedArray()).associateBy { it.id }

    return reportPhotosDao
        .fetchByReportId(reportId)
        .map { ReportPhotoModel(it, filesRows[it.fileId]!!) }
        .sortedBy { it.metadata.id.value }
  }

  fun listFiles(reportId: ReportId): List<ReportFileModel> {
    return reportStore.fetchFilesByReportId(reportId)
  }

  fun getFileModel(reportId: ReportId, fileId: FileId): ReportFileModel {
    return reportStore.fetchFileById(reportId, fileId)
  }

  fun getPhotoModel(reportId: ReportId, fileId: FileId): ReportPhotoModel {
    val photosRow = fetchPhotosRow(reportId, fileId)
    val filesRow = filesDao.fetchOneById(fileId) ?: throw FileNotFoundException(fileId)

    return ReportPhotoModel(photosRow, filesRow)
  }

  fun updatePhoto(model: ReportPhotoModel) {
    requirePermissions { updateReport(model.reportId) }

    val row = fetchPhotosRow(model.reportId, model.metadata.id)

    reportPhotosDao.update(row.copy(caption = model.caption))
  }

  fun deletePhoto(reportId: ReportId, fileId: FileId) {
    requirePermissions { updateReport(reportId) }

    val row = fetchPhotosRow(reportId, fileId)

    fileService.deleteFile(fileId) { reportPhotosDao.delete(row) }
  }

  fun deleteFile(reportId: ReportId, fileId: FileId) {
    requirePermissions { updateReport(reportId) }

    val row = fetchFilesRow(reportId, fileId)

    fileService.deleteFile(fileId) { reportFilesDao.delete(row) }
  }

  @EventListener
  fun on(event: ReportDeletionStartedEvent) {
    val reportId = event.reportId

    reportStore.fetchPhotosByReportId(reportId).forEach { deletePhoto(reportId, it.metadata.id) }
    reportStore.fetchFilesByReportId(reportId).forEach { deleteFile(reportId, it.metadata.id) }
  }

  /**
   * Returns the [ReportPhotosRow] for a specific photo on a specific report.
   *
   * @throws FileNotFoundException The photo with the requested file ID was not associated with the
   *   requested report.
   */
  private fun fetchPhotosRow(reportId: ReportId, fileId: FileId): ReportPhotosRow {
    val row = reportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  /**
   * Returns the [ReportFilesRow] for a specific file on a specific report.
   *
   * @throws FileNotFoundException The file with the requested file ID was not associated with the
   *   requested report.
   */
  private fun fetchFilesRow(reportId: ReportId, fileId: FileId): ReportFilesRow {
    val row = reportFilesDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  private fun store(
      reportId: ReportId,
      data: InputStream,
      metadata: NewFileMetadata,
      insertChildRow: (FileId) -> Unit
  ): FileId {
    requirePermissions { updateReport(reportId) }

    val fileId = fileService.storeFile("report", data, metadata, insertChildRow)

    log.info("Stored ${metadata.contentType} file $fileId for report $reportId")

    return fileId
  }
}
