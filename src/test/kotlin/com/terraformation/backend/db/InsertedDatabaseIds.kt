package com.terraformation.backend.db

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationHistoryId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionSnapshotId
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FileBatchId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GriisResourceId
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SplatAnnotationId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.WcvpTaxonId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowHistoryId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.BagId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestResultId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSiteNotificationId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId

@Suppress("unused")
class InsertedDatabaseIds {
  val accessionIds = mutableListOf<AccessionId>()
  val activityIds = mutableListOf<ActivityId>()
  val applicationHistoryIds = mutableListOf<ApplicationHistoryId>()
  val applicationIds = mutableListOf<ApplicationId>()
  val automationIds = mutableListOf<AutomationId>()
  val bagsIds = mutableListOf<BagId>()
  val batchIds = mutableListOf<BatchId>()
  val biomassSpeciesIds = mutableListOf<BiomassSpeciesId>()
  val botanicalCountryCodes = mutableListOf<String>()
  val commonIndicatorIds = mutableListOf<CommonIndicatorId>()
  val deliverableIds = mutableListOf<DeliverableId>()
  val deliveryIds = mutableListOf<DeliveryId>()
  val deviceIds = mutableListOf<DeviceId>()
  val disclaimerIds = mutableListOf<DisclaimerId>()
  val documentIds = mutableListOf<DocumentId>()
  val documentTemplateIds = mutableListOf<DocumentTemplateId>()
  val draftPlantingSiteIds = mutableListOf<DraftPlantingSiteId>()
  val eventIds = mutableListOf<EventId>()
  val facilityIds = mutableListOf<FacilityId>()
  val fileBatchIds = mutableListOf<FileBatchId>()
  val fileIds = mutableListOf<FileId>()
  val fundingEntityIds = mutableListOf<FundingEntityId>()
  val griisResourceIds = mutableListOf<GriisResourceId>()
  val internalTagIds = mutableListOf<InternalTagId>()
  val moduleIds = mutableListOf<ModuleId>()
  val monitoringPlotHistoryIds = mutableListOf<MonitoringPlotHistoryId>()
  val monitoringPlotHistoryIdsByMonitoringPlotId =
      mutableMapOf<MonitoringPlotId, MutableList<MonitoringPlotHistoryId>>()
  val monitoringPlotIds = mutableListOf<MonitoringPlotId>()
  val notificationIds = mutableListOf<NotificationId>()
  val observationIds = mutableListOf<ObservationId>()
  val organizationIds = mutableListOf<OrganizationId>()
  val participantProjectSpeciesIds = mutableListOf<ParticipantProjectSpeciesId>()
  val plantingIds = mutableListOf<PlantingId>()
  val plantingSeasonIds = mutableListOf<PlantingSeasonId>()
  val plantingSiteHistoryIds = mutableListOf<PlantingSiteHistoryId>()
  val plantingSiteHistoryIdsByPlantingSiteId =
      mutableMapOf<PlantingSiteId, MutableList<PlantingSiteHistoryId>>()
  val plantingSiteIds = mutableListOf<PlantingSiteId>()
  val plantingSiteNotificationIds = mutableListOf<PlantingSiteNotificationId>()
  val projectIds = mutableListOf<ProjectId>()
  val projectIndicatorIds = mutableListOf<ProjectIndicatorId>()
  val projectReportConfigIds = mutableListOf<ProjectReportConfigId>()
  val recordedTreeIds = mutableListOf<RecordedTreeId>()
  val reportIds = mutableListOf<ReportId>()
  val scheduledPlantingDateIds = mutableListOf<ScheduledPlantingDateId>()
  val seedbankWithdrawalIds = mutableListOf<WithdrawalId>()
  val seedFundReportIds = mutableListOf<SeedFundReportId>()
  val speciesIds = mutableListOf<SpeciesId>()
  val splatAnnotationIds = mutableListOf<SplatAnnotationId>()
  val stratumHistoryIds = mutableListOf<StratumHistoryId>()
  val stratumHistoryIdsByStratumId = mutableMapOf<StratumId, MutableList<StratumHistoryId>>()
  val stratumIds = mutableListOf<StratumId>()
  val subLocationIds = mutableListOf<SubLocationId>()
  val submissionDocumentIds = mutableListOf<SubmissionDocumentId>()
  val submissionIds = mutableListOf<SubmissionId>()
  val submissionSnapshotIds = mutableListOf<SubmissionSnapshotId>()
  val substratumHistoryIds = mutableListOf<SubstratumHistoryId>()
  val substratumHistoryIdsBySubstratumId =
      mutableMapOf<SubstratumId, MutableList<SubstratumHistoryId>>()
  val substratumIds = mutableListOf<SubstratumId>()
  val timeseriesIds = mutableListOf<TimeseriesId>()
  val uploadIds = mutableListOf<UploadId>()
  val userIds = mutableListOf<UserId>()
  val variableIds = mutableListOf<VariableId>()
  val variableManifestIds = mutableListOf<VariableManifestId>()
  val variableValueIds = mutableListOf<VariableValueId>()
  val variableWorkflowHistoryIds = mutableListOf<VariableWorkflowHistoryId>()
  val viabilityTestIds = mutableListOf<ViabilityTestId>()
  val viabilityTestResultIds = mutableListOf<ViabilityTestResultId>()
  val wcvpTaxonIds = mutableListOf<WcvpTaxonId>()
  val withdrawalIds = mutableListOf<com.terraformation.backend.db.nursery.WithdrawalId>()

  val accessionId
    get() = accessionIds.last()

  val activityId
    get() = activityIds.last()

  val applicationHistoryId
    get() = applicationHistoryIds.last()

  val applicationId
    get() = applicationIds.last()

  val automationId
    get() = automationIds.last()

  val bagId
    get() = bagsIds.last()

  val batchId
    get() = batchIds.last()

  val biomassSpeciesId
    get() = biomassSpeciesIds.last()

  val botanicalCountryCode
    get() = botanicalCountryCodes.last()

  val commonIndicatorId
    get() = commonIndicatorIds.last()

  val deliverableId
    get() = deliverableIds.last()

  val deliveryId
    get() = deliveryIds.last()

  val deviceId
    get() = deviceIds.last()

  val disclaimerId
    get() = disclaimerIds.last()

  val documentId
    get() = documentIds.last()

  val documentTemplateId
    get() = documentTemplateIds.last()

  val draftPlantingSiteId
    get() = draftPlantingSiteIds.last()

  val eventId
    get() = eventIds.last()

  val facilityId
    get() = facilityIds.last()

  val fileBatchId
    get() = fileBatchIds.last()

  val fileId
    get() = fileIds.last()

  val fundingEntityId
    get() = fundingEntityIds.last()

  val griisResourceId
    get() = griisResourceIds.last()

  val internalTagId
    get() = internalTagIds.last()

  val moduleId
    get() = moduleIds.last()

  val monitoringPlotHistoryId
    get() = monitoringPlotHistoryIds.last()

  val monitoringPlotId
    get() = monitoringPlotIds.last()

  val notificationId
    get() = notificationIds.last()

  val observationId
    get() = observationIds.last()

  val organizationId
    get() = organizationIds.last()

  val participantProjectSpeciesId
    get() = participantProjectSpeciesIds.last()

  val plantingId
    get() = plantingIds.last()

  val plantingSeasonId
    get() = plantingSeasonIds.last()

  val plantingSiteHistoryId
    get() = plantingSiteHistoryIds.last()

  val plantingSiteId
    get() = plantingSiteIds.last()

  val plantingSiteNotificationId
    get() = plantingSiteNotificationIds.last()

  val projectId
    get() = projectIds.last()

  val projectIndicatorId
    get() = projectIndicatorIds.last()

  val projectReportConfigId
    get() = projectReportConfigIds.last()

  val recordedTreeId
    get() = recordedTreeIds.last()

  val reportId
    get() = reportIds.last()

  val scheduledPlantingDateId
    get() = scheduledPlantingDateIds.last()

  val seedFundReportId
    get() = seedFundReportIds.last()

  val speciesId
    get() = speciesIds.last()

  val splatAnnotationId
    get() = splatAnnotationIds.last()

  val stratumHistoryId
    get() = stratumHistoryIds.last()

  val stratumId
    get() = stratumIds.last()

  val subLocationId
    get() = subLocationIds.last()

  val submissionId
    get() = submissionIds.last()

  val substratumHistoryId
    get() = substratumHistoryIds.last()

  val substratumId
    get() = substratumIds.last()

  val timeseriesId
    get() = timeseriesIds.last()

  val uploadId
    get() = uploadIds.last()

  val userId
    get() = userIds.last()

  val variableId
    get() = variableIds.last()

  val variableManifestId
    get() = variableManifestIds.last()

  val variableValueId
    get() = variableValueIds.last()

  val variableWorkflowHistoryId
    get() = variableWorkflowHistoryIds.last()

  val viabilityTestId
    get() = viabilityTestIds.last()

  val viabilityTestResultId
    get() = viabilityTestResultIds.last()

  val wcvpTaxonId
    get() = wcvpTaxonIds.last()

  val withdrawalId
    get() = withdrawalIds.last()
}
