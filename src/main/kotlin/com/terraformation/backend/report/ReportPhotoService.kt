package com.terraformation.backend.report

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.PhotoService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.PhotoMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.model.ReportPhotoModel
import java.io.InputStream
import javax.inject.Named

@Named
class ReportPhotoService(
    private val photoService: PhotoService,
    private val reportPhotosDao: ReportPhotosDao,
) {
  private val log = perClassLogger()

  fun storePhoto(reportId: ReportId, data: InputStream, metadata: PhotoMetadata): PhotoId {
    requirePermissions { updateReport(reportId) }

    val photoId =
        photoService.storePhoto("report", data, metadata.size, metadata) { photoId ->
          reportPhotosDao.insert(ReportPhotosRow(photoId = photoId, reportId = reportId))
        }

    log.info("Stored photo $photoId for report $reportId")

    return photoId
  }

  fun readPhoto(
      reportId: ReportId,
      photoId: PhotoId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    requirePermissions { readReport(reportId) }

    val row = reportPhotosDao.fetchOneByPhotoId(photoId)

    if (row?.reportId != reportId) {
      throw PhotoNotFoundException(photoId)
    }

    return photoService.readPhoto(photoId, maxWidth, maxHeight)
  }

  fun listPhotos(reportId: ReportId): List<ReportPhotoModel> {
    requirePermissions { readReport(reportId) }

    return reportPhotosDao
        .fetchByReportId(reportId)
        .map { ReportPhotoModel(it) }
        .sortedBy { it.photoId.value }
  }

  fun updatePhoto(model: ReportPhotoModel) {
    requirePermissions { updateReport(model.reportId) }

    val row = reportPhotosDao.fetchOneByPhotoId(model.photoId)

    if (row?.reportId != model.reportId) {
      throw PhotoNotFoundException(model.photoId)
    }

    reportPhotosDao.update(row.copy(caption = model.caption))
  }

  fun deletePhoto(reportId: ReportId, photoId: PhotoId) {
    requirePermissions { updateReport(reportId) }

    val row = reportPhotosDao.fetchOneByPhotoId(photoId)
    if (row?.reportId != reportId) {
      throw PhotoNotFoundException(photoId)
    }

    photoService.deletePhoto(photoId) { reportPhotosDao.delete(row) }
  }
}
