package com.terraformation.backend.report

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.model.ReportPhotoModel
import java.io.InputStream
import javax.inject.Named

@Named
class ReportPhotoService(
    private val fileService: FileService,
    private val reportPhotosDao: ReportPhotosDao,
) {
  private val log = perClassLogger()

  fun storePhoto(reportId: ReportId, data: InputStream, metadata: FileMetadata): FileId {
    requirePermissions { updateReport(reportId) }

    val fileId =
        fileService.storeFile("report", data, metadata.size, metadata) { fileId ->
          reportPhotosDao.insert(ReportPhotosRow(fileId = fileId, reportId = reportId))
        }

    log.info("Stored photo $fileId for report $reportId")

    return fileId
  }

  fun readPhoto(
      reportId: ReportId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    requirePermissions { readReport(reportId) }

    val row = reportPhotosDao.fetchOneByFileId(fileId)

    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun listPhotos(reportId: ReportId): List<ReportPhotoModel> {
    requirePermissions { readReport(reportId) }

    return reportPhotosDao
        .fetchByReportId(reportId)
        .map { ReportPhotoModel(it) }
        .sortedBy { it.fileId.value }
  }

  fun updatePhoto(model: ReportPhotoModel) {
    requirePermissions { updateReport(model.reportId) }

    val row = reportPhotosDao.fetchOneByFileId(model.fileId)

    if (row?.reportId != model.reportId) {
      throw FileNotFoundException(model.fileId)
    }

    reportPhotosDao.update(row.copy(caption = model.caption))
  }

  fun deletePhoto(reportId: ReportId, fileId: FileId) {
    requirePermissions { updateReport(reportId) }

    val row = reportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    fileService.deleteFile(fileId) { reportPhotosDao.delete(row) }
  }
}
