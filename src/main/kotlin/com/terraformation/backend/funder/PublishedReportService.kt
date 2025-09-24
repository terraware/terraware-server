package com.terraformation.backend.funder

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.funder.tables.daos.PublishedReportPhotosDao
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import jakarta.inject.Named

@Named
class PublishedReportService(
    private val publishedReportPhotosDao: PublishedReportPhotosDao,
    private val thumbnailService: ThumbnailService,
) {
  fun readPhoto(
      reportId: ReportId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readPublishedReport(reportId) }
    val row = publishedReportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }
    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }
}
