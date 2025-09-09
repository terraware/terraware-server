package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportPhotosRow
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.funder.tables.daos.PublishedReportPhotosDao
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class ReportService(
    private val dslContext: DSLContext,
    private val fileService: FileService,
    private val reportPhotosDao: ReportPhotosDao,
    private val reportStore: ReportStore,
    private val publishedReportPhotosDao: PublishedReportPhotosDao,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @EventListener
  fun on(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    systemUser.run {
      try {
        val numNotified = reportStore.notifyUpcomingReports()
        log.info("Notified $numNotified upcoming reports.")
      } catch (e: Exception) {
        log.warn("Failed to notify upcoming reports: ${e.message}")
      }
    }
  }

  fun deleteReportPhoto(reportId: ReportId, fileId: FileId) {
    requirePermissions { updateReport(reportId) }
    val reportPhotosRow = fetchReportPhotosRow(reportId, fileId)
    val publishedReportsRow = fetchPublishedReportPhotosRow(reportId, fileId)

    if (publishedReportsRow != null) {
      // The photo is already published. Mark the row to be deleted on the next publishing
      reportPhotosDao.update(reportPhotosRow.copy(deleted = true))
    } else {
      fileService.deleteFile(fileId) { reportPhotosDao.delete(reportPhotosRow) }
    }
  }

  fun publishReport(reportId: ReportId) {
    dslContext.transaction { _ ->
      reportStore.publishReport(reportId)
      val deletedPhotos = reportPhotosDao.fetchByReportId(reportId).filter { it.deleted == true }
      deletedPhotos.forEach { deletePublishedReportPhoto(reportId, it.fileId!!) }
    }
  }

  fun readReportPhoto(
      reportId: ReportId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readReport(reportId) }
    fetchReportPhotosRow(reportId, fileId)
    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun storeReportPhoto(
      caption: String?,
      data: InputStream,
      metadata: NewFileMetadata,
      reportId: ReportId,
  ): FileId {
    requirePermissions { updateReport(reportId) }

    val fileId =
        fileService.storeFile("report", data, metadata) { fileId ->
          reportPhotosDao.insert(
              ReportPhotosRow(
                  fileId = fileId,
                  reportId = reportId,
                  caption = caption,
                  deleted = false,
              )
          )
        }

    return fileId
  }

  fun updateReportPhotoCaption(
      caption: String?,
      reportId: ReportId,
      fileId: FileId,
  ) {
    requirePermissions { updateReport(reportId) }
    val reportPhotosRow = fetchReportPhotosRow(reportId, fileId)

    if (reportPhotosRow.deleted == true) {
      throw IllegalStateException("Report ${reportId} photo ${fileId} is deleted.")
    }

    reportPhotosDao.update(reportPhotosRow.copy(caption = caption))
  }

  private fun deletePublishedReportPhoto(reportId: ReportId, fileId: FileId) {
    val reportPhotosRow = fetchReportPhotosRow(reportId, fileId)
    val publishedReportsRow =
        fetchPublishedReportPhotosRow(reportId, fileId) ?: throw FileNotFoundException(fileId)

    if (reportPhotosRow.deleted == false) {
      log.warn("Skipping deleting photo $fileId from report $reportId.")
    }

    fileService.deleteFile(fileId) {
      publishedReportPhotosDao.delete(publishedReportsRow)
      reportPhotosDao.delete(reportPhotosRow)
    }
  }

  private fun fetchReportPhotosRow(reportId: ReportId, fileId: FileId): ReportPhotosRow {
    val row = reportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  private fun fetchPublishedReportPhotosRow(
      reportId: ReportId,
      fileId: FileId,
  ): PublishedReportPhotosRow? {
    val row = publishedReportPhotosDao.fetchOneByFileId(fileId)
    if (row?.reportId != reportId) {
      return null
    }

    return row
  }
}
