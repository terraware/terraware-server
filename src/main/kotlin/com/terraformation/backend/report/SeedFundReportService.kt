package com.terraformation.backend.report

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.event.OrganizationDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.daily.DailyTaskTimeArrivedEvent
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.file.GoogleDriveWriter
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.event.SeedFundReportSubmittedEvent
import com.terraformation.backend.report.model.LatestSeedFundReportBodyModel
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import com.terraformation.backend.report.model.SeedFundReportMetadata
import com.terraformation.backend.report.model.SeedFundReportModel
import com.terraformation.backend.report.render.SeedFundReportRenderer
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.time.quarter
import com.terraformation.backend.tracking.db.PlantingSiteStore
import jakarta.inject.Named
import java.time.Clock
import org.jobrunr.scheduling.JobScheduler
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType

@Named
class SeedFundReportService(
    private val accessionStore: AccessionStore,
    private val batchStore: BatchStore,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val facilityStore: FacilityStore,
    private val googleDriveWriter: GoogleDriveWriter,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val projectStore: ProjectStore,
    private val seedFundReportRenderer: SeedFundReportRenderer,
    private val seedFundReportStore: SeedFundReportStore,
    @Lazy private val scheduler: JobScheduler,
    private val speciesStore: SpeciesStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  private val googleDocsMimeType = "application/vnd.google-apps.document"

  /**
   * Fetches a report using the correct model version for the body and with server-supplied fields
   * filled in.
   *
   * If the report is not submitted yet, the body will always be [LatestSeedFundReportBodyModel] and
   * the server-generated fields will have the most recent data. If the report is already submitted,
   * the body will use whatever version was the latest one at the time it was submitted, and the
   * server-generated fields will have whatever values they had when the report was submitted.
   */
  fun fetchOneById(reportId: SeedFundReportId): SeedFundReportModel {
    val report = seedFundReportStore.fetchOneById(reportId)

    // Never refresh server-generated values in reports once they're submitted.
    return if (report.isSubmitted) {
      report
    } else {
      report.copy(
          body =
              populateBody(
                  report.metadata.organizationId,
                  report.metadata.projectId,
                  report.body.toLatestVersion(),
              ),
      )
    }
  }

  /**
   * Creates a new report for the previous quarter. The server-generated fields will be populated.
   *
   * This will generally be called by a scheduled job, not in response to a user action.
   */
  fun create(organizationId: OrganizationId, projectId: ProjectId? = null): SeedFundReportMetadata {
    return seedFundReportStore.create(
        organizationId,
        projectId,
        populateBody(organizationId, projectId),
    )
  }

  /**
   * Updates a report body. The [modify] function is called with an up-to-date copy of the report
   * (latest body version, all server-generated fields refreshed) and should return a copy with its
   * edits applied.
   */
  fun update(
      reportId: SeedFundReportId,
      modify: (LatestSeedFundReportBodyModel) -> LatestSeedFundReportBodyModel,
  ) {
    val modifiedBody = modify(fetchOneById(reportId).body.toLatestVersion())

    seedFundReportStore.update(reportId, modifiedBody)
  }

  @EventListener
  fun createMissingReports(@Suppress("UNUSED_PARAMETER") event: DailyTaskTimeArrivedEvent) {
    try {
      systemUser.run {
        seedFundReportStore.findOrganizationsForCreate().forEach { create(it) }

        seedFundReportStore
            .findProjectsForCreate()
            .map { projectStore.fetchOneById(it) }
            .forEach { create(it.organizationId, it.id) }
      }
    } catch (e: Exception) {
      log.error("Unable to create reports", e)
    }
  }

  @EventListener
  fun on(event: OrganizationDeletionStartedEvent) {
    seedFundReportStore.fetchMetadataByOrganization(event.organizationId).forEach { report ->
      seedFundReportStore.delete(report.id)
    }
  }

  @EventListener
  fun on(event: ProjectDeletionStartedEvent) {
    seedFundReportStore
        .fetchMetadataByProject(event.projectId)
        .filter { it.status != SeedFundReportStatus.Submitted }
        .forEach { seedFundReportStore.delete(it.id) }
  }

  @EventListener
  fun on(event: ProjectRenamedEvent) {
    event.changedTo.name?.let { name ->
      seedFundReportStore.updateProjectName(event.projectId, name)
    }
  }

  @EventListener
  fun on(event: SeedFundReportSubmittedEvent) {
    if (config.report.exportEnabled && config.report.googleDriveId != null) {
      val reportId = event.reportId
      val jobId = scheduler.enqueue<SeedFundReportService> { exportToGoogleDrive(reportId) }

      log.debug("Enqueued job $jobId to export report $reportId to Google Drive")
    }
  }

  /**
   * Exports a report and its supporting files to Google Drive if enabled.
   *
   * Creates a folder for the organization, then a subfolder for the year+quarter; the report is
   * placed in the subfolder.
   *
   * The report is rendered as HTML and converted to a Google Docs document. It is also uploaded in
   * CSV form. All files and photos associated with the report are uploaded using their original
   * filenames.
   *
   * If the export fails, throws an exception. If the export was triggered by a report being
   * submitted, the exception will cause JobRunr to retry the export after a delay.
   */
  fun exportToGoogleDrive(reportId: SeedFundReportId) {
    val driveId = config.report.googleDriveId
    if (config.report.exportEnabled && driveId != null) {
      systemUser.run {
        val report = seedFundReportStore.fetchOneById(reportId)
        val organization = organizationStore.fetchOneById(report.metadata.organizationId)
        val folderId =
            googleDriveWriter.findOrCreateFolders(
                driveId,
                config.report.googleFolderId ?: driveId,
                listOf(
                    "${organization.name} (${organization.id})",
                    "${report.metadata.year}-Q${report.metadata.quarter}",
                ),
            )
        val baseFilename =
            "${organization.name} ${report.metadata.year}-Q${report.metadata.quarter}"

        val html = seedFundReportRenderer.renderReportHtml(report)
        googleDriveWriter.uploadFile(
            parentFolderId = folderId,
            filename = "$baseFilename Report",
            contentType = googleDocsMimeType,
            // Auto-convert HTML to Google Docs.
            inputStream = html.byteInputStream(),
            driveId = driveId,
            inputStreamContentType = MediaType.TEXT_HTML_VALUE,
        )

        val csv = seedFundReportRenderer.renderReportCsv(report)
        googleDriveWriter.uploadFile(
            parentFolderId = folderId,
            filename = "$baseFilename.csv",
            contentType = "text/csv",
            inputStream = csv.byteInputStream(),
            driveId = driveId,
        )

        seedFundReportStore.fetchFilesByReportId(reportId).forEach { model ->
          googleDriveWriter.copyFile(driveId, folderId, model.metadata)
        }

        seedFundReportStore.fetchPhotosByReportId(reportId).forEach { model ->
          googleDriveWriter.copyFile(driveId, folderId, model.metadata, model.caption)
        }
      }
    }
  }

  /** Returns a report body with up-to-date data in all its server-generated fields. */
  private fun populateBody(
      organizationId: OrganizationId,
      projectId: ProjectId? = null,
      body: LatestSeedFundReportBodyModel? = null,
  ): LatestSeedFundReportBodyModel {
    val organization = organizationStore.fetchOneById(organizationId)
    val isAnnual = body?.isAnnual ?: (seedFundReportStore.getLastQuarter().quarter == 4)
    val facilities =
        if (projectId != null) {
          facilityStore.fetchByProjectId(projectId)
        } else {
          facilityStore.fetchByOrganizationId(organizationId)
        }
    val plantingSiteModels =
        if (projectId != null) {
          plantingSiteStore.fetchSitesByProjectId(projectId)
        } else {
          plantingSiteStore.fetchSitesByOrganizationId(organizationId)
        }

    val nurseryModels = facilities.filter { it.type == FacilityType.Nursery }
    val seedBankModels = facilities.filter { it.type == FacilityType.SeedBank }

    val annualDetails =
        if (isAnnual) body?.annualDetails ?: SeedFundReportBodyModelV1.AnnualDetails() else null

    val nurseryBodies =
        nurseryModels
            .map { facility ->
              val orgStats = batchStore.getNurseryStats(facility.id)
              val projectStats = projectId?.let { batchStore.getNurseryStats(facility.id, it) }

              body
                  ?.nurseries
                  ?.find { it.id == facility.id }
                  ?.populate(facility, orgStats, projectStats)
                  ?: SeedFundReportBodyModelV1.Nursery(facility, orgStats, projectStats)
            }
            .sortedBy { it.id }

    val plantingSiteBodies =
        plantingSiteModels
            .map { plantingSiteModel ->
              val speciesModels = speciesStore.fetchSpeciesByPlantingSiteId(plantingSiteModel.id)

              body
                  ?.plantingSites
                  ?.find { it.id == plantingSiteModel.id }
                  ?.populate(plantingSiteModel, speciesModels)
                  ?: SeedFundReportBodyModelV1.PlantingSite(plantingSiteModel, speciesModels)
            }
            .sortedBy { it.id }

    val seedBankBodies =
        seedBankModels
            .map { facility ->
              val orgStats = accessionStore.getSummaryStatistics(facility.id)
              val projectStats =
                  projectId?.let { accessionStore.getSummaryStatistics(facility.id, it) }

              body
                  ?.seedBanks
                  ?.find { it.id == facility.id }
                  ?.populate(facility, orgStats, projectStats)
                  ?: SeedFundReportBodyModelV1.SeedBank(facility, orgStats, projectStats)
            }
            .sortedBy { it.id }

    return body?.copy(
        annualDetails = annualDetails,
        isAnnual = isAnnual,
        nurseries = nurseryBodies,
        organizationName = organization.name,
        plantingSites = plantingSiteBodies,
        seedBanks = seedBankBodies,
        totalNurseries = nurseryModels.size,
        totalPlantingSites = plantingSiteModels.size,
        totalSeedBanks = seedBankModels.size,
    )
        ?: SeedFundReportBodyModelV1(
            annualDetails = annualDetails,
            isAnnual = isAnnual,
            nurseries = nurseryBodies,
            organizationName = organization.name,
            plantingSites = plantingSiteBodies,
            seedBanks = seedBankBodies,
            totalNurseries = nurseryModels.size,
            totalPlantingSites = plantingSiteModels.size,
            totalSeedBanks = seedBankModels.size,
        )
  }
}
