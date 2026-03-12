package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ReportStore
import com.terraformation.backend.accelerator.model.PublishedReportComparedProps
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.accelerator.tables.pojos.ReportPhotosRow
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.tables.daos.PublishedReportPhotosDao
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.funder.db.PublishedReportStore
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.io.InputStream
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class ReportService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val reportPhotosDao: ReportPhotosDao,
    private val reportStore: ReportStore,
    private val publishedReportPhotosDao: PublishedReportPhotosDao,
    private val publishedReportStore: PublishedReportStore,
    private val systemUser: SystemUser,
    private val thumbnailService: ThumbnailService,
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

  fun fetch(
      projectId: ProjectId? = null,
      year: Int? = null,
      includeArchived: Boolean = false,
      includeFuture: Boolean = false,
      includeIndicators: Boolean = false,
      computeUnpublishedChanges: Boolean = false,
  ): List<ReportModel> {
    val reports =
        reportStore.fetch(
            projectId = projectId,
            year = year,
            includeArchived = includeArchived,
            includeFuture = includeFuture,
            includeIndicators = includeIndicators,
        )
    if (!computeUnpublishedChanges) {
      return reports
    }
    return applyUnpublishedProperties(reports, includeIndicators)
  }

  fun fetchOne(
      reportId: ReportId,
      includeIndicators: Boolean = false,
      computeUnpublishedChanges: Boolean = false,
  ): ReportModel {
    val report = reportStore.fetchOne(reportId, includeIndicators)
    if (!computeUnpublishedChanges) {
      return report
    }
    val results = applyUnpublishedProperties(listOf(report), includeIndicators)
    return results.first()
  }

  fun deleteReportPhoto(reportId: ReportId, fileId: FileId) {
    requirePermissions { updateReport(reportId) }
    val reportPhotosRow = fetchReportPhotosRow(reportId, fileId)
    val publishedReportsRow = fetchPublishedReportPhotosRow(reportId, fileId)

    if (publishedReportsRow != null) {
      // The photo is already published. Mark the row to be deleted on the next publishing
      reportPhotosDao.update(reportPhotosRow.copy(deleted = true))
    } else {
      reportPhotosDao.delete(reportPhotosRow)
      eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
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
    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun storeReportPhoto(
      caption: String?,
      data: InputStream,
      metadata: NewFileMetadata,
      reportId: ReportId,
  ): FileId {
    requirePermissions { updateReport(reportId) }

    val fileId =
        fileService.storeFile("report", data, metadata) { (fileId) ->
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

  private fun applyUnpublishedProperties(
      reports: List<ReportModel>,
      includeIndicators: Boolean,
  ): List<ReportModel> {
    if (reports.isEmpty()) {
      return reports
    }

    val allowedToViewPublished = reports.filter { currentUser().canReadPublishedReport(it.id) }

    val publishedByReportId =
        publishedReportStore.fetchPublishedReportsByIds(allowedToViewPublished.map { it.id })

    return reports.map { report ->
      val published = publishedByReportId[report.id] ?: return@map report

      val changed = mutableListOf<PublishedReportComparedProps>()

      if (report.highlights != published.highlights) {
        changed.add(PublishedReportComparedProps.Highlights)
      }
      if (report.financialSummaries != published.financialSummaries) {
        changed.add(PublishedReportComparedProps.FinancialSummaries)
      }
      if (report.additionalComments != published.additionalComments) {
        changed.add(PublishedReportComparedProps.AdditionalComments)
      }

      if (report.achievements.toSet() != published.achievements.toSet()) {
        changed.add(PublishedReportComparedProps.Achievements)
      }

      val currentChallenges = report.challenges.map { it.challenge to it.mitigationPlan }.toSet()
      val pubChallenges = published.challenges.map { it.challenge to it.mitigationPlan }.toSet()
      if (currentChallenges != pubChallenges) {
        changed.add(PublishedReportComparedProps.Challenges)
      }

      if (report.photos.map { it.fileId }.toSet() != published.photos.map { it.fileId }.toSet()) {
        changed.add(PublishedReportComparedProps.Photos)
      }

      if (includeIndicators) {
        val pubAutoCalc =
            published.autoCalculatedIndicators.associate { it.indicatorId to it.value }
        val hasAutoCalcChanged =
            pubAutoCalc.any { (indicator, pubValue) ->
              val current = report.autoCalculatedIndicators.find { it.indicator == indicator }
              val currentValue = current?.let { it.entry.overrideValue ?: it.entry.systemValue }
              currentValue != pubValue
            }
        if (hasAutoCalcChanged) {
          changed.add(PublishedReportComparedProps.AutoCalculatedIndicators)
        }

        val pubCommon = published.commonIndicators.associate { it.indicatorId to it.value }
        val currentCommon =
            report.commonIndicators
                .filter { it.indicator.isPublishable }
                .associate { it.indicator.id to it.entry.value }
        if (currentCommon != pubCommon) {
          changed.add(PublishedReportComparedProps.CommonIndicators)
        }

        val pubProject = published.projectIndicators.associate { it.indicatorId to it.value }
        val currentProject =
            report.projectIndicators
                .filter { it.indicator.isPublishable }
                .associate { it.indicator.id to it.entry.value }
        if (currentProject != pubProject) {
          changed.add(PublishedReportComparedProps.ProjectIndicators)
        }
      }

      report.copy(unpublishedProperties = changed)
    }
  }

  private fun deletePublishedReportPhoto(reportId: ReportId, fileId: FileId) {
    val reportPhotosRow = fetchReportPhotosRow(reportId, fileId)
    val publishedReportsRow =
        fetchPublishedReportPhotosRow(reportId, fileId) ?: throw FileNotFoundException(fileId)

    if (reportPhotosRow.deleted == false) {
      log.warn("Skipping deleting photo $fileId from report $reportId.")
    }

    publishedReportPhotosDao.delete(publishedReportsRow)
    reportPhotosDao.delete(reportPhotosRow)

    eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
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
