package com.terraformation.backend.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.SpringShutdownListener
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.ApplicationHistoryId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionSnapshotId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.keys.COHORTS_PKEY
import com.terraformation.backend.db.accelerator.tables.daos.ActivitiesDao
import com.terraformation.backend.db.accelerator.tables.daos.ActivityMediaFilesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationHistoriesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationsDao
import com.terraformation.backend.db.accelerator.tables.daos.CohortModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.CohortsDao
import com.terraformation.backend.db.accelerator.tables.daos.DefaultVotersDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableCohortDueDatesDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableProjectDueDatesDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableVariablesDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.accelerator.tables.daos.EventProjectsDao
import com.terraformation.backend.db.accelerator.tables.daos.EventsDao
import com.terraformation.backend.db.accelerator.tables.daos.ModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantProjectSpeciesDao
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectAcceleratorDetailsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectMetricsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectOverallScoresDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectReportConfigsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectScoresDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVoteDecisionsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportAchievementsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportChallengesDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportProjectMetricsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportStandardMetricsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportSystemMetricsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.daos.StandardMetricsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionSnapshotsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionsDao
import com.terraformation.backend.db.accelerator.tables.daos.UserInternalInterestsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ActivitiesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationHistoriesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationsRow
import com.terraformation.backend.db.accelerator.tables.pojos.CohortModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.CohortsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableCohortDueDatesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableProjectDueDatesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableVariablesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverablesRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventProjectsRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectAcceleratorDetailsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectMetricsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectOverallScoresRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectReportConfigsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectScoresRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVoteDecisionsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportAchievementsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportChallengesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportPhotosRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportProjectMetricsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportStandardMetricsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportSystemMetricsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.pojos.StandardMetricsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionSnapshotsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.db.accelerator.tables.pojos.UserInternalInterestsRow
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.ThumbnailId
import com.terraformation.backend.db.default_schema.TimeseriesId
import com.terraformation.backend.db.default_schema.TimeseriesType
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.keys.USERS_PKEY
import com.terraformation.backend.db.default_schema.tables.daos.AutomationsDao
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.CountrySubdivisionsDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.daos.DevicesDao
import com.terraformation.backend.db.default_schema.tables.daos.DisclaimersDao
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.IdentifierSequencesDao
import com.terraformation.backend.db.default_schema.tables.daos.InternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.NotificationsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationInternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationManagedLocationTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectInternalUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectLandUseModelTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesEcosystemTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesGrowthFormsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesPlantMaterialSourcingMethodsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesSuccessionalGroupsDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeZonesDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeseriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.daos.UserDisclaimersDao
import com.terraformation.backend.db.default_schema.tables.daos.UserGlobalRolesDao
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.pojos.DisclaimersRow
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectInternalUsersRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectLandUseModelTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserDisclaimersRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserGlobalRolesRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_GROWTH_FORMS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PLANT_MATERIAL_SOURCING_METHODS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_SUCCESSIONAL_GROUPS
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_GLOBAL_ROLES
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableSectionDefaultValueId
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableTableStyle
import com.terraformation.backend.db.docprod.VariableTextType
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowHistoryId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.db.docprod.keys.VARIABLES_PKEY
import com.terraformation.backend.db.docprod.tables.daos.DocumentSavedVersionsDao
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.db.docprod.tables.daos.DocumentsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableImageValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableLinkValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestEntriesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableManifestsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableNumbersDao
import com.terraformation.backend.db.docprod.tables.daos.VariableOwnersDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionDefaultValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionRecommendationsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSectionsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectOptionValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectOptionsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableSelectsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTableColumnsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTablesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableTextsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableValueTableRowsDao
import com.terraformation.backend.db.docprod.tables.daos.VariableValuesDao
import com.terraformation.backend.db.docprod.tables.daos.VariableWorkflowHistoryDao
import com.terraformation.backend.db.docprod.tables.daos.VariablesDao
import com.terraformation.backend.db.docprod.tables.pojos.DocumentSavedVersionsRow
import com.terraformation.backend.db.docprod.tables.pojos.DocumentTemplatesRow
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableImageValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableLinkValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestEntriesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableManifestsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableNumbersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableOwnersRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionDefaultValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionRecommendationsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSectionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectOptionsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableSelectsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTableColumnsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTablesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableTextsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableValueTableRowsRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableValuesRow
import com.terraformation.backend.db.docprod.tables.pojos.VariableWorkflowHistoryRow
import com.terraformation.backend.db.docprod.tables.pojos.VariablesRow
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.daos.FundingEntitiesDao
import com.terraformation.backend.db.funder.tables.daos.FundingEntityProjectsDao
import com.terraformation.backend.db.funder.tables.daos.FundingEntityUsersDao
import com.terraformation.backend.db.funder.tables.daos.PublishedActivitiesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedActivityMediaFilesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectCarbonCertsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectDetailsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectLandUseDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectSdgDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportPhotosDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportProjectMetricsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportStandardMetricsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportSystemMetricsDao
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.pojos.FundingEntityProjectsRow
import com.terraformation.backend.db.funder.tables.pojos.FundingEntityUsersRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivitiesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivityMediaFilesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectCarbonCertsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectDetailsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectLandUseRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectSdgRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportPhotosRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportProjectMetricsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportStandardMetricsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportSystemMetricsRow
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.keys.BATCHES_PKEY
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistorySubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchPhotosDao
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchSubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchesDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalPhotosDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchSubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.BagId
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestResultId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId as SeedbankWithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose as SeedbankWithdrawalPurpose
import com.terraformation.backend.db.seedbank.keys.ACCESSION_PKEY
import com.terraformation.backend.db.seedbank.tables.daos.AccessionCollectorsDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionPhotosDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionQuantityHistoryDao
import com.terraformation.backend.db.seedbank.tables.daos.AccessionsDao
import com.terraformation.backend.db.seedbank.tables.daos.BagsDao
import com.terraformation.backend.db.seedbank.tables.daos.GeolocationsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestResultsDao
import com.terraformation.backend.db.seedbank.tables.daos.ViabilityTestsDao
import com.terraformation.backend.db.seedbank.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.pojos.WithdrawalsRow as SeedbankWithdrawalsRow
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.ObservedPlotCoordinatesId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSiteNotificationId
import com.terraformation.backend.db.tracking.PlantingSubzoneHistoryId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlantingZoneHistoryId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.keys.PLANTING_SITES_PKEY
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.DraftPlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotOverlapsDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassDetailsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassQuadratDetailsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassSpeciesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPhotosDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservedPlotCoordinatesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteNotificationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzoneHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSubzonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZoneHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingZonesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedPlantsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedTreesDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.DraftPlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotOverlapsRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationRequestedSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotCoordinatesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteNotificationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzoneHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZoneHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZoneT0TempDensitiesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlotT0DensitiesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlotT0ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedTreesRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.util.toInstant
import jakarta.ws.rs.NotFoundException
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSupertypeOf
import org.jooq.Condition
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Table
import org.jooq.TableRecord
import org.jooq.UpdatableRecord
import org.jooq.impl.DAOImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.test.context.transaction.InheritedTransactionRemover
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Superclass for tests that make use of a database. You will usually want to subclass
 * [DatabaseTest] or [ControllerIntegrationTest] instead of this.
 *
 * Base class for database-backed tests. Subclass this to get a fully-configured database with a
 * [DSLContext] and a set of jOOQ DAO objects ready to use. The database is run in a Docker
 * container which is torn down after all tests have finished. This cuts down on the chance that
 * tests will behave differently from one development environment to the next.
 *
 * In general, you should only use this for testing database-centric code! Do not put SQL queries in
 * the middle of business logic. If you want to test code that _uses_ data from the database, pull
 * the queries out into data-access classes (the code base uses the suffix "Store" for these
 * classes) and stub out the stores. Then test the store classes separately. Database-backed tests
 * are slower and are usually not as easy to read or maintain.
 *
 * Some things to be aware of:
 * - Each test method is run in a transaction which is rolled back afterward, so no need to worry
 *   about test methods polluting the database for each other if they're writing values.
 * - But that means test methods can't use data written by previous methods. If your test method
 *   needs sample data, either put it in a migration (migrations are run before any tests) or in a
 *   helper method.
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ComponentScan(basePackageClasses = [UsersDao::class])
@ContextConfiguration(
    initializers = [DatabaseBackedTest.DockerPostgresDataSourceInitializer::class]
)
@EnableConfigurationProperties(TerrawareServerConfig::class)
@Suppress("MemberVisibilityCanBePrivate") // Some DAOs are not used in tests yet
@Testcontainers
@TestExecutionListeners(
    InheritedTransactionRemover::class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS,
)
@Transactional
abstract class DatabaseBackedTest {
  @Autowired
  @Suppress("SpringJavaInjectionPointsAutowiringInspection") // Spurious IntelliJ warning
  lateinit var dslContext: DSLContext

  /** IDs of entities that have been inserted using the `insert` helper methods during this test. */
  val inserted = Inserted()

  @Autowired
  fun registerApplicationContext(context: ApplicationContext) {
    SpringShutdownListener.register(context)
  }

  /**
   * Creates a lazily-instantiated jOOQ DAO object. In most cases, type inference will figure out
   * which DAO class to instantiate.
   */
  private inline fun <reified T : DAOImpl<*, *, *>> lazyDao(): Lazy<T> {
    return lazy {
      val singleArgConstructor =
          T::class.constructors.first {
            it.parameters.size == 1 &&
                it.parameters[0].type.isSupertypeOf(Configuration::class.createType())
          }

      singleArgConstructor.call(dslContext.configuration())
    }
  }

  protected val accessionCollectorsDao: AccessionCollectorsDao by lazyDao()
  protected val accessionPhotosDao: AccessionPhotosDao by lazyDao()
  protected val accessionQuantityHistoryDao: AccessionQuantityHistoryDao by lazyDao()
  protected val accessionsDao: AccessionsDao by lazyDao()
  protected val activitiesDao: ActivitiesDao by lazyDao()
  protected val activityMediaFilesDao: ActivityMediaFilesDao by lazyDao()
  protected val applicationHistoriesDao: ApplicationHistoriesDao by lazyDao()
  protected val applicationModulesDao: ApplicationModulesDao by lazyDao()
  protected val applicationsDao: ApplicationsDao by lazyDao()
  protected val automationsDao: AutomationsDao by lazyDao()
  protected val bagsDao: BagsDao by lazyDao()
  protected val batchDetailsHistoryDao: BatchDetailsHistoryDao by lazyDao()
  protected val batchDetailsHistorySubLocationsDao: BatchDetailsHistorySubLocationsDao by lazyDao()
  protected val batchesDao: BatchesDao by lazyDao()
  protected val batchPhotosDao: BatchPhotosDao by lazyDao()
  protected val batchQuantityHistoryDao: BatchQuantityHistoryDao by lazyDao()
  protected val batchSubLocationsDao: BatchSubLocationsDao by lazyDao()
  protected val batchWithdrawalsDao: BatchWithdrawalsDao by lazyDao()
  protected val cohortModulesDao: CohortModulesDao by lazyDao()
  protected val cohortsDao: CohortsDao by lazyDao()
  protected val countriesDao: CountriesDao by lazyDao()
  protected val countrySubdivisionsDao: CountrySubdivisionsDao by lazyDao()
  protected val defaultVotersDao: DefaultVotersDao by lazyDao()
  protected val deliverableCohortDueDatesDao: DeliverableCohortDueDatesDao by lazyDao()
  protected val deliverableDocumentsDao: DeliverableDocumentsDao by lazyDao()
  protected val deliverableProjectDueDatesDao: DeliverableProjectDueDatesDao by lazyDao()
  protected val deliverablesDao: DeliverablesDao by lazyDao()
  protected val deliverableVariablesDao: DeliverableVariablesDao by lazyDao()
  protected val deliveriesDao: DeliveriesDao by lazyDao()
  protected val deviceManagersDao: DeviceManagersDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val deviceTemplatesDao: DeviceTemplatesDao by lazyDao()
  protected val disclaimersDao: DisclaimersDao by lazyDao()
  protected val documentSavedVersionsDao: DocumentSavedVersionsDao by lazyDao()
  protected val documentsDao: DocumentsDao by lazyDao()
  protected val draftPlantingSitesDao: DraftPlantingSitesDao by lazyDao()
  protected val eventProjectsDao: EventProjectsDao by lazyDao()
  protected val eventsDao: EventsDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val filesDao: FilesDao by lazyDao()
  protected val fundingEntitiesDao: FundingEntitiesDao by lazyDao()
  protected val fundingEntityProjectsDao: FundingEntityProjectsDao by lazyDao()
  protected val fundingEntityUsersDao: FundingEntityUsersDao by lazyDao()
  protected val geolocationsDao: GeolocationsDao by lazyDao()
  protected val identifierSequencesDao: IdentifierSequencesDao by lazyDao()
  protected val internalTagsDao: InternalTagsDao by lazyDao()
  protected val documentTemplatesDao: DocumentTemplatesDao by lazyDao()
  protected val modulesDao: ModulesDao by lazyDao()
  protected val monitoringPlotHistoriesDao: MonitoringPlotHistoriesDao by lazyDao()
  protected val monitoringPlotOverlapsDao: MonitoringPlotOverlapsDao by lazyDao()
  protected val monitoringPlotsDao: MonitoringPlotsDao by lazyDao()
  protected val notificationsDao: NotificationsDao by lazyDao()
  protected val nurseryWithdrawalsDao:
      com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao by
      lazyDao()
  protected val observationBiomassDetailsDao: ObservationBiomassDetailsDao by lazyDao()
  protected val observationBiomassSpeciesDao: ObservationBiomassSpeciesDao by lazyDao()
  protected val observationBiomassQuadratDetailsDao: ObservationBiomassQuadratDetailsDao by
      lazyDao()
  protected val observationPhotosDao: ObservationPhotosDao by lazyDao()
  protected val observationPlotConditionsDao: ObservationPlotConditionsDao by lazyDao()
  protected val observationPlotsDao: ObservationPlotsDao by lazyDao()
  protected val observationRequestedSubzonesDao: ObservationRequestedSubzonesDao by lazyDao()
  protected val observationsDao: ObservationsDao by lazyDao()
  protected val observedPlotCoordinatesDao: ObservedPlotCoordinatesDao by lazyDao()
  protected val organizationInternalTagsDao: OrganizationInternalTagsDao by lazyDao()
  protected val organizationManagedLocationTypesDao: OrganizationManagedLocationTypesDao by
      lazyDao()
  protected val organizationReportSettingsDao: OrganizationReportSettingsDao by lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val organizationUsersDao: OrganizationUsersDao by lazyDao()
  protected val participantsDao: ParticipantsDao by lazyDao()
  protected val participantProjectSpeciesDao: ParticipantProjectSpeciesDao by lazyDao()
  protected val plantingsDao: PlantingsDao by lazyDao()
  protected val plantingSeasonsDao: PlantingSeasonsDao by lazyDao()
  protected val plantingSiteHistoriesDao: PlantingSiteHistoriesDao by lazyDao()
  protected val plantingSiteNotificationsDao: PlantingSiteNotificationsDao by lazyDao()
  protected val plantingSitePopulationsDao: PlantingSitePopulationsDao by lazyDao()
  protected val plantingSitesDao: PlantingSitesDao by lazyDao()
  protected val plantingSubzoneHistoriesDao: PlantingSubzoneHistoriesDao by lazyDao()
  protected val plantingSubzonePopulationsDao: PlantingSubzonePopulationsDao by lazyDao()
  protected val plantingSubzonesDao: PlantingSubzonesDao by lazyDao()
  protected val plantingZoneHistoriesDao: PlantingZoneHistoriesDao by lazyDao()
  protected val plantingZonePopulationsDao: PlantingZonePopulationsDao by lazyDao()
  protected val plantingZonesDao: PlantingZonesDao by lazyDao()
  protected val projectAcceleratorDetailsDao: ProjectAcceleratorDetailsDao by lazyDao()
  protected val projectInternalUsersDao: ProjectInternalUsersDao by lazyDao()
  protected val projectLandUseModelTypesDao: ProjectLandUseModelTypesDao by lazyDao()
  protected val projectMetricsDao: ProjectMetricsDao by lazyDao()
  protected val projectOverallScoresDao: ProjectOverallScoresDao by lazyDao()
  protected val projectReportConfigsDao: ProjectReportConfigsDao by lazyDao()
  protected val projectReportSettingsDao: ProjectReportSettingsDao by lazyDao()
  protected val projectScoresDao: ProjectScoresDao by lazyDao()
  protected val projectsDao: ProjectsDao by lazyDao()
  protected val projectVoteDecisionDao: ProjectVoteDecisionsDao by lazyDao()
  protected val projectVotesDao: ProjectVotesDao by lazyDao()
  protected val publishedActivitiesDao: PublishedActivitiesDao by lazyDao()
  protected val publishedActivityMediaFilesDao: PublishedActivityMediaFilesDao by lazyDao()
  protected val publishedProjectCarbonCertsDao: PublishedProjectCarbonCertsDao by lazyDao()
  protected val publishedProjectDetailsDao: PublishedProjectDetailsDao by lazyDao()
  protected val publishedProjectLandUseDao: PublishedProjectLandUseDao by lazyDao()
  protected val publishedProjectSdgDao: PublishedProjectSdgDao by lazyDao()
  protected val publishedReportStandardMetricsDao: PublishedReportStandardMetricsDao by lazyDao()
  protected val publishedReportSystemMetricsDao: PublishedReportSystemMetricsDao by lazyDao()
  protected val publishedReportPhotosDao: PublishedReportPhotosDao by lazyDao()
  protected val publishedReportProjectMetricsDao: PublishedReportProjectMetricsDao by lazyDao()
  protected val recordedPlantsDao: RecordedPlantsDao by lazyDao()
  protected val recordedTreesDao: RecordedTreesDao by lazyDao()
  protected val reportAchievementsDao: ReportAchievementsDao by lazyDao()
  protected val reportChallengesDao: ReportChallengesDao by lazyDao()
  protected val reportPhotosDao: ReportPhotosDao by lazyDao()
  protected val reportProjectMetricsDao: ReportProjectMetricsDao by lazyDao()
  protected val reportStandardMetricsDao: ReportStandardMetricsDao by lazyDao()
  protected val reportSystemMetricsDao: ReportSystemMetricsDao by lazyDao()
  protected val reportsDao: ReportsDao by lazyDao()
  protected val seedFundReportFilesDao: SeedFundReportFilesDao by lazyDao()
  protected val seedFundReportPhotosDao: SeedFundReportPhotosDao by lazyDao()
  protected val seedFundReportsDao: SeedFundReportsDao by lazyDao()
  protected val speciesDao: SpeciesDao by lazyDao()
  protected val speciesEcosystemTypesDao: SpeciesEcosystemTypesDao by lazyDao()
  protected val speciesGrowthFormsDao: SpeciesGrowthFormsDao by lazyDao()
  protected val speciesPlantMaterialSourcingMethodsDao: SpeciesPlantMaterialSourcingMethodsDao by
      lazyDao()
  protected val speciesProblemsDao: SpeciesProblemsDao by lazyDao()
  protected val speciesSuccessionalGroupsDao: SpeciesSuccessionalGroupsDao by lazyDao()
  protected val standardMetricsDao: StandardMetricsDao by lazyDao()
  protected val subLocationsDao: SubLocationsDao by lazyDao()
  protected val submissionsDao: SubmissionsDao by lazyDao()
  protected val submissionDocumentsDao: SubmissionDocumentsDao by lazyDao()
  protected val submissionSnapshotsDao: SubmissionSnapshotsDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val timeZonesDao: TimeZonesDao by lazyDao()
  protected val uploadProblemsDao: UploadProblemsDao by lazyDao()
  protected val uploadsDao: UploadsDao by lazyDao()
  protected val userDisclaimersDao: UserDisclaimersDao by lazyDao()
  protected val userInternalInterestsDao: UserInternalInterestsDao by lazyDao()
  protected val userGlobalRolesDao: UserGlobalRolesDao by lazyDao()
  protected val usersDao: UsersDao by lazyDao()
  protected val variableImageValuesDao: VariableImageValuesDao by lazyDao()
  protected val variableLinkValuesDao: VariableLinkValuesDao by lazyDao()
  protected val variableManifestEntriesDao: VariableManifestEntriesDao by lazyDao()
  protected val variableManifestsDao: VariableManifestsDao by lazyDao()
  protected val variableNumbersDao: VariableNumbersDao by lazyDao()
  protected val variableOwnersDao: VariableOwnersDao by lazyDao()
  protected val variableSelectsDao: VariableSelectsDao by lazyDao()
  protected val variableSelectOptionValuesDao: VariableSelectOptionValuesDao by lazyDao()
  protected val variableSelectOptionsDao: VariableSelectOptionsDao by lazyDao()
  protected val variableSectionsDao: VariableSectionsDao by lazyDao()
  protected val variableSectionDefaultValuesDao: VariableSectionDefaultValuesDao by lazyDao()
  protected val variableSectionRecommendationsDao: VariableSectionRecommendationsDao by lazyDao()
  protected val variableSectionValuesDao: VariableSectionValuesDao by lazyDao()
  protected val variableTablesDao: VariableTablesDao by lazyDao()
  protected val variableTableColumnsDao: VariableTableColumnsDao by lazyDao()
  protected val variableTextsDao: VariableTextsDao by lazyDao()
  protected val variableValueTableRowsDao: VariableValueTableRowsDao by lazyDao()
  protected val variableValuesDao: VariableValuesDao by lazyDao()
  protected val variableWorkflowHistoryDao: VariableWorkflowHistoryDao by lazyDao()
  protected val variablesDao: VariablesDao by lazyDao()
  protected val viabilityTestResultsDao: ViabilityTestResultsDao by lazyDao()
  protected val viabilityTestsDao: ViabilityTestsDao by lazyDao()
  protected val withdrawalPhotosDao: WithdrawalPhotosDao by lazyDao()
  protected val withdrawalsDao: WithdrawalsDao by lazyDao()

  private var nextOrganizationNumber = 1

  protected fun insertOrganization(
      name: String = "Organization ${nextOrganizationNumber++}",
      countryCode: String? = null,
      countrySubdivisionCode: String? = null,
      createdBy: UserId = currentUser().userId,
      timeZone: ZoneId? = null,
  ): OrganizationId {
    return with(ORGANIZATIONS) {
      dslContext
          .insertInto(ORGANIZATIONS)
          .set(COUNTRY_CODE, countryCode)
          .set(COUNTRY_SUBDIVISION_CODE, countrySubdivisionCode)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(TIME_ZONE, timeZone)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.organizationIds.add(it) }
    }
  }

  private var nextFacilityNumber = 1

  fun insertFacility(
      organizationId: OrganizationId = inserted.organizationId,
      name: String = "Facility $nextFacilityNumber",
      description: String? = "Description $nextFacilityNumber",
      createdBy: UserId = currentUser().userId,
      type: FacilityType = FacilityType.SeedBank,
      maxIdleMinutes: Int = 30,
      lastTimeseriesTime: Instant? = null,
      idleAfterTime: Instant? = null,
      idleSinceTime: Instant? = null,
      lastNotificationDate: LocalDate? = null,
      nextNotificationTime: Instant = Instant.EPOCH,
      timeZone: ZoneId? = null,
      buildStartedDate: LocalDate? = null,
      buildCompletedDate: LocalDate? = null,
      operationStartedDate: LocalDate? = null,
      capacity: Int? = null,
      facilityNumber: Int = nextFacilityNumber,
  ): FacilityId {
    nextFacilityNumber++

    return with(FACILITIES) {
      dslContext
          .insertInto(FACILITIES)
          .set(BUILD_COMPLETED_DATE, buildCompletedDate)
          .set(BUILD_STARTED_DATE, buildStartedDate)
          .set(CAPACITY, capacity)
          .set(CONNECTION_STATE_ID, FacilityConnectionState.NotConnected)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DESCRIPTION, description)
          .set(FACILITY_NUMBER, facilityNumber)
          .set(IDLE_AFTER_TIME, idleAfterTime)
          .set(IDLE_SINCE_TIME, idleSinceTime)
          .set(LAST_NOTIFICATION_DATE, lastNotificationDate)
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(NEXT_NOTIFICATION_TIME, nextNotificationTime)
          .set(OPERATION_STARTED_DATE, operationStartedDate)
          .set(ORGANIZATION_ID, organizationId)
          .set(TIME_ZONE, timeZone)
          .set(TYPE_ID, type)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.facilityIds.add(it) }
    }
  }

  protected fun getFacilityById(facilityId: FacilityId): FacilitiesRow {
    return facilitiesDao.fetchOneById(facilityId) ?: throw NotFoundException()
  }

  private var nextProjectNumber = 1

  protected fun insertProject(
      organizationId: OrganizationId = inserted.organizationId,
      name: String = "Project ${nextProjectNumber++}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      description: String? = null,
      participantId: ParticipantId? = null,
      countryCode: String? = null,
  ): ProjectId {
    val row =
        ProjectsRow(
            countryCode = countryCode,
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
            organizationId = organizationId,
            participantId = participantId,
        )

    projectsDao.insert(row)

    return row.id!!.also { inserted.projectIds.add(it) }
  }

  protected fun insertProjectAcceleratorDetails(
      row: ProjectAcceleratorDetailsRow = ProjectAcceleratorDetailsRow(),
      annualCarbon: Number? = row.annualCarbon,
      applicationReforestableLand: Number? = row.applicationReforestableLand,
      carbonCapacity: Number? = row.carbonCapacity,
      confirmedReforestableLand: Number? = row.confirmedReforestableLand,
      dealDescription: String? = row.dealDescription,
      dealName: String? = row.dealName,
      dealStage: DealStage? = row.dealStageId,
      dropboxFolderPath: String? = row.dropboxFolderPath,
      failureRisk: String? = row.failureRisk,
      fileNaming: String? = row.fileNaming,
      googleFolderUrl: Any? = row.googleFolderUrl,
      hubSpotUrl: Any? = row.hubspotUrl,
      investmentThesis: String? = row.investmentThesis,
      logframeUrl: Any? = row.logframeUrl,
      maxCarbonAccumulation: Number? = row.maxCarbonAccumulation,
      minCarbonAccumulation: Number? = row.minCarbonAccumulation,
      numCommunities: Int? = row.numCommunities,
      numNativeSpecies: Int? = row.numNativeSpecies,
      perHectareBudget: Number? = row.perHectareBudget,
      pipeline: Pipeline? = row.pipelineId,
      plantingSitesCql: String? = row.plantingSitesCql,
      projectBoundariesCql: String? = row.projectBoundariesCql,
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      totalCarbon: Number? = row.totalCarbon,
      totalExpansionPotential: Number? = row.totalExpansionPotential,
      whatNeedsToBeTrue: String? = row.whatNeedsToBeTrue,
  ): ProjectAcceleratorDetailsRow {
    val rowWithDefaults =
        ProjectAcceleratorDetailsRow(
            annualCarbon = annualCarbon?.toBigDecimal(),
            applicationReforestableLand = applicationReforestableLand?.toBigDecimal(),
            carbonCapacity = carbonCapacity?.toBigDecimal(),
            confirmedReforestableLand = confirmedReforestableLand?.toBigDecimal(),
            dealDescription = dealDescription,
            dealName = dealName,
            dealStageId = dealStage,
            dropboxFolderPath = dropboxFolderPath,
            failureRisk = failureRisk,
            fileNaming = fileNaming,
            googleFolderUrl = googleFolderUrl?.let { URI("$it") },
            hubspotUrl = hubSpotUrl?.let { URI("$it") },
            investmentThesis = investmentThesis,
            logframeUrl = logframeUrl?.let { URI("$it") },
            maxCarbonAccumulation = maxCarbonAccumulation?.toBigDecimal(),
            minCarbonAccumulation = minCarbonAccumulation?.toBigDecimal(),
            numCommunities = numCommunities,
            numNativeSpecies = numNativeSpecies,
            perHectareBudget = perHectareBudget?.toBigDecimal(),
            pipelineId = pipeline,
            plantingSitesCql = plantingSitesCql,
            projectBoundariesCql = projectBoundariesCql,
            projectId = projectId,
            totalCarbon = totalCarbon?.toBigDecimal(),
            totalExpansionPotential = totalExpansionPotential?.toBigDecimal(),
            whatNeedsToBeTrue = whatNeedsToBeTrue,
        )

    projectAcceleratorDetailsDao.insert(rowWithDefaults)

    return rowWithDefaults
  }

  protected fun insertProjectInternalUser(
      row: ProjectInternalUsersRow = ProjectInternalUsersRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      userId: UserId = row.userId ?: inserted.userId,
      role: ProjectInternalRole? = row.projectInternalRoleId,
      roleName: String? = row.roleName,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedBy: UserId = currentUser().userId,
      modifiedTime: Instant = Instant.EPOCH,
  ): ProjectInternalUsersRow {
    val rowWithDefaults =
        ProjectInternalUsersRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            projectId = projectId,
            projectInternalRoleId = role,
            roleName = roleName,
            userId = userId,
        )

    projectInternalUsersDao.insert(rowWithDefaults)

    return rowWithDefaults
  }

  protected fun insertPublishedProjectDetails(
      row: PublishedProjectDetailsRow = PublishedProjectDetailsRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      accumulationRate: Number? = row.accumulationRate,
      annualCarbon: Number? = row.annualCarbon,
      countryCode: String? = row.countryCode,
      dealDescription: String? = row.dealDescription,
      dealName: String? = row.dealName,
      methodologyNumber: String? = row.methodologyNumber,
      minProjectArea: Number? = row.minProjectArea,
      numNativeSpecies: Int? = row.numNativeSpecies,
      perHectareEstimatedBudget: Number? = row.perHectareEstimatedBudget,
      projectArea: Number? = row.projectArea,
      projectHighlightPhotoValueId: VariableValueId? =
          row.projectHighlightPhotoValueId?.let { VariableValueId(it) },
      projectZoneFigureValueId: VariableValueId? =
          row.projectZoneFigureValueId?.let { VariableValueId(it) },
      standard: String? = row.standard,
      tfReforestableLand: Number? = row.tfReforestableLand,
      totalExpansionPotential: Number? = row.totalExpansionPotential,
      totalVcu: Number? = row.totalVcu,
      verraLink: String? = row.verraLink,
      sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
      carbonCertifications: Set<CarbonCertification> = emptySet(),
      landUseModelHectares: Map<LandUseModelType, Number?> = emptyMap(),
      publishedBy: UserId = currentUser().userId,
      publishedTime: Instant = Instant.EPOCH,
  ): PublishedProjectDetailsRow {
    val rowWithDefaults =
        PublishedProjectDetailsRow(
            projectId = projectId,
            accumulationRate = accumulationRate?.toBigDecimal(),
            annualCarbon = annualCarbon?.toBigDecimal(),
            countryCode = countryCode,
            dealDescription = dealDescription,
            dealName = dealName,
            methodologyNumber = methodologyNumber,
            minProjectArea = minProjectArea?.toBigDecimal(),
            numNativeSpecies = numNativeSpecies,
            perHectareEstimatedBudget = perHectareEstimatedBudget?.toBigDecimal(),
            projectArea = projectArea?.toBigDecimal(),
            projectHighlightPhotoValueId = projectHighlightPhotoValueId?.value,
            projectZoneFigureValueId = projectZoneFigureValueId?.value,
            standard = standard,
            tfReforestableLand = tfReforestableLand?.toBigDecimal(),
            totalExpansionPotential = totalExpansionPotential?.toBigDecimal(),
            totalVcu = totalVcu?.toBigDecimal(),
            verraLink = verraLink,
            publishedBy = publishedBy,
            publishedTime = publishedTime,
        )

    publishedProjectDetailsDao.insert(rowWithDefaults)
    sdgList.forEach { insertPublishedProjectSdg(projectId = projectId, sdgNumber = it.sdgNumber) }
    carbonCertifications.forEach {
      insertPublishedProjectCarbonCert(projectId = projectId, carbonCertification = it.displayName)
    }
    landUseModelHectares.keys.forEach { landType ->
      insertPublishedProjectLandHectare(
          projectId = projectId,
          landUseModelType = landType,
          landUseHectares = landUseModelHectares[landType],
      )
    }

    return rowWithDefaults
  }

  protected fun insertPublishedProjectSdg(
      row: PublishedProjectSdgRow = PublishedProjectSdgRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      sdgNumber: Int = row.sdgNumber!!,
  ) {
    val rowWithDefaults =
        PublishedProjectSdgRow(
            projectId = projectId,
            sdgNumber = sdgNumber,
        )
    publishedProjectSdgDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedProjectCarbonCert(
      row: PublishedProjectCarbonCertsRow = PublishedProjectCarbonCertsRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      carbonCertification: String = row.carbonCertification!!,
  ) {
    val rowWithDefaults =
        PublishedProjectCarbonCertsRow(
            projectId = projectId,
            carbonCertification = carbonCertification,
        )
    publishedProjectCarbonCertsDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedProjectLandHectare(
      row: PublishedProjectLandUseRow = PublishedProjectLandUseRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      landUseModelType: LandUseModelType = row.landUseModelTypeId!!,
      landUseHectares: Number? = row.landUseModelHectares,
  ) {
    val rowWithDefaults =
        PublishedProjectLandUseRow(
            projectId = projectId,
            landUseModelTypeId = landUseModelType,
            landUseModelHectares = landUseHectares?.toBigDecimal(),
        )
    publishedProjectLandUseDao.insert(rowWithDefaults)
  }

  protected fun deleteProjectAcceleratorDetails(projectId: ProjectId = inserted.projectId) {
    projectAcceleratorDetailsDao.deleteById(projectId)
  }

  protected fun insertProjectLandUseModelType(
      projectId: ProjectId = inserted.projectId,
      landUseModelType: LandUseModelType = LandUseModelType.OtherLandUseModel,
  ) {
    projectLandUseModelTypesDao.insert(
        ProjectLandUseModelTypesRow(landUseModelTypeId = landUseModelType, projectId = projectId)
    )
  }

  protected fun insertProjectMetric(
      row: ProjectMetricsRow = ProjectMetricsRow(),
      component: MetricComponent = row.componentId ?: MetricComponent.ProjectObjectives,
      description: String? = row.description,
      isPublishable: Boolean = row.isPublishable ?: true,
      name: String = row.name ?: "Metric name",
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      reference: String = row.reference ?: "1.1",
      type: MetricType = row.typeId ?: MetricType.Impact,
      unit: String? = row.unit,
  ): ProjectMetricId {
    val rowWithDefaults =
        row.copy(
            componentId = component,
            description = description,
            isPublishable = isPublishable,
            name = name,
            reference = reference,
            projectId = projectId,
            typeId = type,
            unit = unit,
        )

    projectMetricsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.projectMetricIds.add(it) }
  }

  protected fun insertProjectOverallScore(
      projectId: ProjectId = inserted.projectId,
      detailsUrl: URI? = null,
      overallScore: Double? = null,
      summary: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    val row =
        ProjectOverallScoresRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            detailsUrl = detailsUrl,
            overallScore = overallScore,
            projectId = projectId,
            summary = summary,
        )

    projectOverallScoresDao.insert(row)
  }

  protected fun insertProjectScore(
      projectId: ProjectId = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      category: ScoreCategory = ScoreCategory.Legal,
      score: Int? = null,
      qualitative: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    val row =
        ProjectScoresRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            phaseId = phase,
            projectId = projectId,
            qualitative = qualitative,
            score = score,
            scoreCategoryId = category,
        )

    projectScoresDao.insert(row)
  }

  protected fun insertDefaultVoter(userId: UserId) {
    val row = DefaultVotersRow(userId)
    defaultVotersDao.insert(row)
  }

  private var nextDeliverableNumber: Int = 1

  protected fun insertDeliverable(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      deliverableCategoryId: DeliverableCategory = DeliverableCategory.FinancialViability,
      deliverableTypeId: DeliverableType = DeliverableType.Document,
      descriptionHtml: String? = "Description $nextDeliverableNumber",
      isSensitive: Boolean = false,
      isRequired: Boolean = false,
      moduleId: ModuleId? = inserted.moduleId,
      name: String = "Deliverable $nextDeliverableNumber",
      position: Int = nextDeliverableNumber,
  ): DeliverableId {
    nextDeliverableNumber++

    val row =
        DeliverablesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            deliverableCategoryId = deliverableCategoryId,
            deliverableTypeId = deliverableTypeId,
            descriptionHtml = descriptionHtml,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            moduleId = moduleId,
            name = name,
            position = position,
            isSensitive = isSensitive,
            isRequired = isRequired,
        )

    deliverablesDao.insert(row)

    return row.id!!.also { inserted.deliverableIds.add(it) }
  }

  protected fun insertDeliverableCohortDueDate(
      deliverableId: DeliverableId = inserted.deliverableId,
      cohortId: CohortId = inserted.cohortId,
      dueDate: LocalDate,
  ) {
    val row =
        DeliverableCohortDueDatesRow(
            cohortId = cohortId,
            deliverableId = deliverableId,
            dueDate = dueDate,
        )

    deliverableCohortDueDatesDao.insert(row)
  }

  protected fun insertDeliverableDocument(
      deliverableId: DeliverableId = inserted.deliverableId,
      templateUrl: Any? = null,
  ) {
    val row =
        DeliverableDocumentsRow(
            deliverableId = deliverableId,
            deliverableTypeId = DeliverableType.Document,
            templateUrl = templateUrl?.let { URI.create("$it") },
        )

    deliverableDocumentsDao.insert(row)
  }

  protected fun insertDeliverableProjectDueDate(
      deliverableId: DeliverableId = inserted.deliverableId,
      projectId: ProjectId = inserted.projectId,
      dueDate: LocalDate,
  ) {
    val row =
        DeliverableProjectDueDatesRow(
            deliverableId = deliverableId,
            dueDate = dueDate,
            projectId = projectId,
        )

    deliverableProjectDueDatesDao.insert(row)
  }

  private var nextDeliverablePosition = 1

  protected fun insertDeliverableVariable(
      deliverableId: DeliverableId = inserted.deliverableId,
      variableId: VariableId = inserted.variableId,
      position: Int = nextDeliverablePosition++,
  ) {
    deliverableVariablesDao.insert(
        DeliverableVariablesRow(
            deliverableId = deliverableId,
            position = position,
            variableId = variableId,
        )
    )
  }

  private var nextDeviceNumber = 1

  protected fun insertDevice(
      facilityId: FacilityId = inserted.facilityId,
      name: String = "device ${nextDeviceNumber++}",
      createdBy: UserId = currentUser().userId,
      type: String = "type",
  ): DeviceId {
    return with(DEVICES) {
      dslContext
          .insertInto(DEVICES)
          .set(ADDRESS, "address")
          .set(CREATED_BY, createdBy)
          .set(DEVICE_TYPE, type)
          .set(FACILITY_ID, facilityId)
          .set(MAKE, "make")
          .set(MODEL, "model")
          .set(MODIFIED_BY, createdBy)
          .set(NAME, name)
          .set(PROTOCOL, "protocol")
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.deviceIds.add(it) }
    }
  }

  private var nextTimeseriesNumber = 1

  protected fun insertTimeseries(
      deviceId: DeviceId = inserted.deviceId,
      name: String = "timeseries ${nextTimeseriesNumber++}",
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
      type: TimeseriesType = TimeseriesType.Numeric,
      units: String = "volts",
      decimalPlaces: Int? = 1,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      retentionDays: Int? = null,
  ): TimeseriesId {
    val row =
        TimeseriesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            decimalPlaces = decimalPlaces,
            deviceId = deviceId,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            retentionDays = retentionDays,
            typeId = type,
            units = units,
        )

    timeseriesDao.insert(row)

    return row.id!!.also { inserted.timeseriesIds.add(it) }
  }

  protected fun insertTimeseriesValue(
      timeseriesId: TimeseriesId = inserted.timeseriesId,
      createdTime: Instant = Instant.EPOCH,
      value: String = "1",
  ) {
    with(TIMESERIES_VALUES) {
      dslContext
          .insertInto(TIMESERIES_VALUES)
          .set(TIMESERIES_ID, timeseriesId)
          .set(CREATED_TIME, createdTime)
          .set(VALUE, value)
          .execute()
    }
  }

  protected fun insertDeviceManager(
      balenaModifiedTime: Instant = Instant.EPOCH,
      createdTime: Instant = Instant.EPOCH,
      facilityId: FacilityId? = null,
      refreshedTime: Instant = Instant.EPOCH,
      userId: UserId? = if (facilityId != null) currentUser().userId else null,
  ): DeviceManagersRow {
    val balenaId = BalenaDeviceId(nextBalenaId.getAndIncrement())

    val row =
        DeviceManagersRow(
            balenaModifiedTime = balenaModifiedTime,
            balenaId = balenaId,
            balenaUuid = "uuid-$balenaId",
            createdTime = createdTime,
            deviceName = "Device $balenaId",
            facilityId = facilityId,
            isOnline = false,
            refreshedTime = refreshedTime,
            sensorKitId = "$balenaId",
            userId = userId,
        )

    deviceManagersDao.insert(row)
    return row
  }

  var nextAutomationNumber = 1

  protected fun insertAutomation(
      facilityId: FacilityId = inserted.facilityId,
      name: String = "automation ${nextAutomationNumber++}",
      type: String = AutomationModel.SENSOR_BOUNDS_TYPE,
      deviceId: DeviceId? = inserted.deviceId,
      timeseriesName: String? = "timeseries",
      lowerThreshold: Double? = 10.0,
      upperThreshold: Double? = 20.0,
      createdBy: UserId = currentUser().userId,
  ): AutomationId {
    return with(AUTOMATIONS) {
      dslContext
          .insertInto(AUTOMATIONS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(DEVICE_ID, deviceId)
          .set(FACILITY_ID, facilityId)
          .set(LOWER_THRESHOLD, lowerThreshold)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .set(NAME, name)
          .set(TIMESERIES_NAME, timeseriesName)
          .set(TYPE, type)
          .set(UPPER_THRESHOLD, upperThreshold)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.automationIds.add(it) }
    }
  }

  private var nextSpeciesNumber = 1

  fun insertSpecies(
      scientificName: String = "Species ${nextSpeciesNumber++}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedTime: Instant = createdTime,
      organizationId: OrganizationId = inserted.organizationId,
      deletedTime: Instant? = null,
      checkedTime: Instant? = null,
      initialScientificName: String = scientificName,
      commonName: String? = null,
      ecosystemTypes: Set<EcosystemType> = emptySet(),
      growthForms: Set<GrowthForm> = emptySet(),
      plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod> = emptySet(),
      successionalGroups: Set<SuccessionalGroup> = emptySet(),
      rare: Boolean? = null,
      conservationCategory: ConservationCategory? = null,
      seedStorageBehavior: SeedStorageBehavior? = null,
  ): SpeciesId {
    val actualSpeciesId =
        with(SPECIES) {
          dslContext
              .insertInto(SPECIES)
              .set(CHECKED_TIME, checkedTime)
              .set(COMMON_NAME, commonName)
              .set(CONSERVATION_CATEGORY_ID, conservationCategory)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, createdTime)
              .set(DELETED_BY, if (deletedTime != null) createdBy else null)
              .set(DELETED_TIME, deletedTime)
              .set(INITIAL_SCIENTIFIC_NAME, initialScientificName)
              .set(MODIFIED_BY, createdBy)
              .set(MODIFIED_TIME, modifiedTime)
              .set(ORGANIZATION_ID, organizationId)
              .set(RARE, rare)
              .set(SCIENTIFIC_NAME, scientificName)
              .set(SEED_STORAGE_BEHAVIOR_ID, seedStorageBehavior)
              .returning(ID)
              .fetchOne(ID)!!
        }

    ecosystemTypes.forEach { ecosystemType ->
      dslContext
          .insertInto(SPECIES_ECOSYSTEM_TYPES)
          .set(SPECIES_ECOSYSTEM_TYPES.SPECIES_ID, actualSpeciesId)
          .set(SPECIES_ECOSYSTEM_TYPES.ECOSYSTEM_TYPE_ID, ecosystemType)
          .execute()
    }

    growthForms.forEach { growthForm ->
      dslContext
          .insertInto(SPECIES_GROWTH_FORMS)
          .set(SPECIES_GROWTH_FORMS.SPECIES_ID, actualSpeciesId)
          .set(SPECIES_GROWTH_FORMS.GROWTH_FORM_ID, growthForm)
          .execute()
    }

    plantMaterialSourcingMethods.forEach { plantMaterialSourcingMethod ->
      dslContext
          .insertInto(SPECIES_PLANT_MATERIAL_SOURCING_METHODS)
          .set(SPECIES_PLANT_MATERIAL_SOURCING_METHODS.SPECIES_ID, actualSpeciesId)
          .set(
              SPECIES_PLANT_MATERIAL_SOURCING_METHODS.PLANT_MATERIAL_SOURCING_METHOD_ID,
              plantMaterialSourcingMethod,
          )
          .execute()
    }

    successionalGroups.forEach { successionalGroup ->
      dslContext
          .insertInto(SPECIES_SUCCESSIONAL_GROUPS)
          .set(SPECIES_SUCCESSIONAL_GROUPS.SPECIES_ID, actualSpeciesId)
          .set(SPECIES_SUCCESSIONAL_GROUPS.SUCCESSIONAL_GROUP_ID, successionalGroup)
          .execute()
    }

    return actualSpeciesId.also { inserted.speciesIds.add(it) }
  }

  fun insertSubmission(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
      deliverableId: DeliverableId? = inserted.deliverableId,
      feedback: String? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      internalComment: String? = null,
      projectId: ProjectId? = inserted.projectId,
      submissionStatus: SubmissionStatus = SubmissionStatus.NotSubmitted,
  ): SubmissionId {
    val row =
        SubmissionsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            deliverableId = deliverableId,
            feedback = feedback,
            internalComment = internalComment,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            projectId = projectId,
            submissionStatusId = submissionStatus,
        )

    submissionsDao.insert(row)

    return row.id!!.also { inserted.submissionIds.add(it) }
  }

  private var nextSubmissionNumber = 1

  fun insertSubmissionDocument(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      description: String? = null,
      documentStore: DocumentStore = DocumentStore.Google,
      location: String = "Location $nextSubmissionNumber",
      name: String = "Submission Document $nextSubmissionNumber",
      originalName: String? = "Original Name $nextSubmissionNumber",
      projectId: ProjectId? = inserted.projectId,
      submissionId: SubmissionId? = inserted.submissionId,
  ): SubmissionDocumentId {
    nextSubmissionNumber++

    val row =
        SubmissionDocumentsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            documentStoreId = documentStore,
            location = location,
            name = name,
            originalName = originalName,
            projectId = projectId,
            submissionId = submissionId,
        )

    submissionDocumentsDao.insert(row)

    return row.id!!.also { inserted.submissionDocumentIds.add(it) }
  }

  fun insertSubmissionSnapshot(
      fileId: FileId? = inserted.fileId,
      submissionId: SubmissionId? = inserted.submissionId,
  ): SubmissionSnapshotId {
    val row =
        SubmissionSnapshotsRow(
            fileId = fileId,
            submissionId = submissionId,
        )

    submissionSnapshotsDao.insert(row)

    return row.id!!.also { inserted.submissionSnapshotIds.add(it) }
  }

  /** Creates a user that can be referenced by various tests. */
  fun insertUser(
      authId: String? = "${UUID.randomUUID()}",
      email: String = "$authId@terraformation.com",
      firstName: String? = "First",
      lastName: String? = "Last",
      type: UserType = UserType.Individual,
      emailNotificationsEnabled: Boolean = false,
      timeZone: ZoneId? = null,
      locale: Locale? = null,
      cookiesConsented: Boolean? = null,
      cookiesConsentedTime: Instant? = if (cookiesConsented != null) Instant.EPOCH else null,
      createdTime: Instant = Instant.EPOCH,
      deletedTime: Instant? = null,
  ): UserId {
    val insertedId =
        with(USERS) {
          dslContext
              .insertInto(USERS)
              .set(AUTH_ID, authId)
              .set(COOKIES_CONSENTED, cookiesConsented)
              .set(COOKIES_CONSENTED_TIME, cookiesConsentedTime)
              .set(CREATED_TIME, createdTime)
              .set(DELETED_TIME, deletedTime)
              .set(EMAIL, email)
              .set(EMAIL_NOTIFICATIONS_ENABLED, emailNotificationsEnabled)
              .set(FIRST_NAME, firstName)
              .set(LAST_NAME, lastName)
              .set(LOCALE, locale)
              .set(MODIFIED_TIME, createdTime)
              .set(TIME_ZONE, timeZone)
              .set(USER_TYPE_ID, type)
              .returning(ID)
              .fetchOne(ID)!!
        }

    return insertedId.also { inserted.userIds.add(it) }
  }

  fun insertUserDisclaimer(
      userId: UserId = inserted.userId,
      disclaimerId: DisclaimerId = inserted.disclaimerId,
      acceptedOn: Instant = Instant.EPOCH,
  ) {
    userDisclaimersDao.insert(
        UserDisclaimersRow(
            userId = userId,
            disclaimerId = disclaimerId,
            acceptedOn = acceptedOn,
        )
    )
  }

  fun insertUserInternalInterest(
      internalInterest: InternalInterest,
      userId: UserId = inserted.userId,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    userInternalInterestsDao.insert(
        UserInternalInterestsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            internalInterestId = internalInterest,
            userId = userId,
        )
    )
  }

  fun insertUserGlobalRole(
      userId: UserId = currentUser().userId,
      role: GlobalRole,
  ) {
    userGlobalRolesDao.insert(UserGlobalRolesRow(globalRoleId = role, userId = userId))
    clearCachedPermissions(userId)
  }

  fun deleteObservationBiomassDetails(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
  ) {
    with(OBSERVATION_BIOMASS_DETAILS) {
      dslContext
          .deleteFrom(this)
          .where(OBSERVATION_ID.eq(observationId))
          .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
          .execute()
    }
  }

  fun deleteRecordedTree(
      treeId: RecordedTreeId,
  ) {
    with(RECORDED_TREES) { dslContext.deleteFrom(this).where(ID.eq(treeId)).execute() }

    inserted.recordedTreeIds.removeIf { it == treeId }
  }

  fun deleteUserGlobalRole(
      userId: UserId = currentUser().userId,
      role: GlobalRole,
  ) {
    with(USER_GLOBAL_ROLES) {
      dslContext
          .deleteFrom(USER_GLOBAL_ROLES)
          .where(USER_ID.eq(userId))
          .and(GLOBAL_ROLE_ID.eq(role))
          .execute()
    }

    clearCachedPermissions(userId)
  }

  /** Adds a user to an organization. */
  fun insertOrganizationUser(
      userId: UserId = currentUser().userId,
      organizationId: OrganizationId = inserted.organizationId,
      role: Role = Role.Contributor,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, createdTime)
          .set(ORGANIZATION_ID, organizationId)
          .set(ROLE_ID, role)
          .set(USER_ID, userId)
          .onConflict(ORGANIZATION_ID, USER_ID)
          .doUpdate()
          .set(ROLE_ID, role)
          .execute()
    }

    clearCachedPermissions(userId)
  }

  /** Removes a user from an organization. */
  fun deleteOrganizationUser(
      userId: UserId = currentUser().userId,
      organizationId: OrganizationId = inserted.organizationId,
  ) {
    with(ORGANIZATION_USERS) {
      dslContext
          .deleteFrom(ORGANIZATION_USERS)
          .where(ORGANIZATION_ID.eq(organizationId))
          .and(USER_ID.eq(userId))
          .execute()
    }

    clearCachedPermissions(userId)
  }

  private var nextSubLocationNumber = 1

  /** Adds a sub-location to a facility. */
  fun insertSubLocation(
      facilityId: FacilityId = inserted.facilityId,
      name: String = "Location ${nextSubLocationNumber++}",
      createdBy: UserId = currentUser().userId,
  ): SubLocationId {
    val insertedId =
        with(SUB_LOCATIONS) {
          dslContext
              .insertInto(SUB_LOCATIONS)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, Instant.EPOCH)
              .set(FACILITY_ID, facilityId)
              .set(MODIFIED_BY, createdBy)
              .set(MODIFIED_TIME, Instant.EPOCH)
              .set(NAME, name)
              .returning(ID)
              .fetchOne(ID)!!
        }

    return insertedId.also { inserted.subLocationIds.add(it) }
  }

  private var nextStorageUrlNumber = 1

  fun insertFile(
      row: FilesRow = FilesRow(),
      fileName: String = row.fileName ?: "fileName",
      contentType: String = row.contentType ?: "image/jpeg",
      size: Long = row.size ?: 1,
      capturedLocalTime: LocalDateTime? = row.capturedLocalTime,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      geolocation: Point? = row.geolocation?.centroid,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      storageUrl: Any = row.storageUrl ?: "http://dummy/${nextStorageUrlNumber++}",
  ): FileId {
    val rowWithDefaults =
        row.copy(
            capturedLocalTime = capturedLocalTime,
            contentType = contentType,
            createdBy = createdBy,
            createdTime = createdTime,
            fileName = fileName,
            geolocation = geolocation,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            size = size,
            storageUrl = URI("$storageUrl"),
        )

    filesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.fileIds.add(it) }
  }

  private var nextUploadNumber = 1

  fun insertUpload(
      type: UploadType = UploadType.SpeciesCSV,
      fileName: String = "${nextUploadNumber}.csv",
      storageUrl: URI = URI.create("file:///${nextUploadNumber}.csv"),
      contentType: String = "text/csv",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      status: UploadStatus = UploadStatus.Receiving,
      organizationId: OrganizationId? = null,
      facilityId: FacilityId? = null,
      locale: Locale = Locale.ENGLISH,
  ): UploadId {
    nextUploadNumber++

    return with(UPLOADS) {
      dslContext
          .insertInto(UPLOADS)
          .set(CONTENT_TYPE, contentType)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(FACILITY_ID, facilityId)
          .set(FILENAME, fileName)
          .set(LOCALE, locale)
          .set(ORGANIZATION_ID, organizationId)
          .set(STATUS_ID, status)
          .set(STORAGE_URL, storageUrl)
          .set(TYPE_ID, type)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.uploadIds.add(it) }
    }
  }

  fun insertNotification(
      userId: UserId = currentUser().userId,
      type: NotificationType = NotificationType.FacilityIdle,
      organizationId: OrganizationId? = null,
      title: String = "",
      body: String = "",
      localUrl: URI = URI.create(""),
      createdTime: Instant = Instant.EPOCH,
      isRead: Boolean = false,
  ): NotificationId {
    return with(NOTIFICATIONS) {
      dslContext
          .insertInto(NOTIFICATIONS)
          .set(USER_ID, userId)
          .set(NOTIFICATION_TYPE_ID, type)
          .set(ORGANIZATION_ID, organizationId)
          .set(TITLE, title)
          .set(BODY, body)
          .set(LOCAL_URL, localUrl)
          .set(CREATED_TIME, createdTime)
          .set(IS_READ, isRead)
          .returning(ID)
          .fetchOne(ID)!!
          .also { inserted.notificationIds.add(it) }
    }
  }

  private var nextAccessionNumber = 1

  /**
   * Inserts a new accession with reasonable defaults for required fields.
   *
   * Since accessions have a ton of fields, this works a little differently than the other insert
   * helper methods in that it takes an optional [row] argument. Any fields in [row] that are not
   * overridden by other parameters to this function are retained as-is. This approach means we
   * don't have to have a parameter here for every single accession field, just the ones that are
   * used in more than one or two places in the test suite.
   */
  fun insertAccession(
      row: AccessionsRow = AccessionsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      dataSourceId: DataSource = row.dataSourceId ?: DataSource.Web,
      facilityId: FacilityId = row.facilityId ?: inserted.facilityId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      number: String? = row.number ?: "${nextAccessionNumber++}",
      projectId: ProjectId? = row.projectId,
      receivedDate: LocalDate? = row.receivedDate,
      speciesId: SpeciesId? = row.speciesId,
      stateId: AccessionState = row.stateId ?: AccessionState.Processing,
      treesCollectedFrom: Int? = row.treesCollectedFrom,
  ): AccessionId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            dataSourceId = dataSourceId,
            facilityId = facilityId,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            number = number,
            receivedDate = receivedDate,
            speciesId = speciesId,
            stateId = stateId,
            treesCollectedFrom = treesCollectedFrom,
            projectId = projectId,
        )

    accessionsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.accessionIds.add(it) }
  }

  private var nextBagNumber = 1

  fun insertBag(
      row: BagsRow = BagsRow(),
      accessionId: AccessionId = row.accessionId ?: inserted.accessionId,
      bagNumber: String = row.bagNumber ?: "${nextBagNumber++}",
  ): BagId {
    val rowWithDefaults = row.copy(bagNumber = bagNumber, accessionId = accessionId)

    bagsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.bagsIds.add(it) }
  }

  fun insertViabilityTest(
      row: ViabilityTestsRow = ViabilityTestsRow(),
      accessionId: AccessionId = row.accessionId ?: inserted.accessionId,
      testType: ViabilityTestType? = row.testType ?: ViabilityTestType.Lab,
      seedTypeId: ViabilityTestSeedType? = row.seedTypeId,
      substrateId: ViabilityTestSubstrate? = row.substrateId,
      treatmentId: SeedTreatment? = row.treatmentId,
      seedsSown: Int? = row.seedsSown,
      notes: String? = row.notes,
      staffResponsible: String? = row.staffResponsible,
      totalSeedsGerminated: Int? = row.totalSeedsGerminated,
      totalPercentGerminated: Int? = row.totalPercentGerminated,
      startDate: LocalDate? = row.startDate,
      endDate: LocalDate? = row.endDate,
      seedsCompromised: Int? = row.seedsCompromised,
      seedsEmpty: Int? = row.seedsEmpty,
      seedsFilled: Int? = row.seedsFilled,
  ): ViabilityTestId {
    val rowWithDefaults =
        row.copy(
            accessionId = accessionId,
            testType = testType,
            seedTypeId = seedTypeId,
            substrateId = substrateId,
            treatmentId = treatmentId,
            seedsSown = seedsSown,
            notes = notes,
            staffResponsible = staffResponsible,
            totalSeedsGerminated = totalSeedsGerminated,
            totalPercentGerminated = totalPercentGerminated,
            startDate = startDate,
            endDate = endDate,
            seedsCompromised = seedsCompromised,
            seedsEmpty = seedsEmpty,
            seedsFilled = seedsFilled,
        )

    viabilityTestsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.viabilityTestIds.add(it) }
  }

  private var nextViabilityTestResultsNumber: Int = 1

  fun insertViabilityTestResult(
      row: ViabilityTestResultsRow = ViabilityTestResultsRow(),
      viabilityTestId: ViabilityTestId = row.testId ?: inserted.viabilityTestId,
      recordingDate: LocalDate = row.recordingDate ?: LocalDate.EPOCH,
      seedsGerminated: Int = row.seedsGerminated ?: nextViabilityTestResultsNumber++,
  ): ViabilityTestResultId {
    val rowWithDefaults =
        row.copy(
            testId = viabilityTestId,
            recordingDate = recordingDate,
            seedsGerminated = seedsGerminated,
        )

    viabilityTestResultsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.viabilityTestResultIds.add(it) }
  }

  private var nextBatchNumber: Int = 1

  fun insertBatch(
      row: BatchesRow = BatchesRow(),
      activeGrowthQuantity: Int = row.activeGrowthQuantity ?: 0,
      addedDate: LocalDate = row.addedDate ?: LocalDate.EPOCH,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      facilityId: FacilityId = row.facilityId ?: inserted.facilityId,
      germinatingQuantity: Int = row.germinatingQuantity ?: 0,
      hardeningOffQuantity: Int = row.hardeningOffQuantity ?: 0,
      organizationId: OrganizationId = row.organizationId ?: inserted.organizationId,
      projectId: ProjectId? = row.projectId,
      readyQuantity: Int = row.readyQuantity ?: 0,
      readyByDate: LocalDate? = row.readyByDate,
      speciesId: SpeciesId = row.speciesId ?: inserted.speciesId,
      version: Int = row.version ?: 1,
      batchNumber: String = row.batchNumber ?: "${nextBatchNumber++}",
      germinationRate: Int? = row.germinationRate,
      totalGerminated: Int? = row.totalGerminated,
      totalGerminationCandidates: Int? = row.totalGerminationCandidates,
      lossRate: Int? =
          row.lossRate
              ?: if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0) 0
              else null,
      totalLost: Int? =
          row.totalLost
              ?: if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0) 0
              else null,
      totalLossCandidates: Int? =
          row.totalLossCandidates
              ?: if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0)
                  activeGrowthQuantity + hardeningOffQuantity + readyQuantity
              else null,
  ): BatchId {
    val effectiveGerminationRate =
        germinationRate
            ?: if (totalGerminated != null && totalGerminationCandidates != null) {
              ((100.0 * totalGerminated) / totalGerminationCandidates).roundToInt()
            } else {
              null
            }
    val effectiveLossRate =
        lossRate
            ?: if (totalLost != null && totalLossCandidates != null) {
              ((100.0 * totalLost) / totalLossCandidates).roundToInt()
            } else {
              null
            }

    val rowWithDefaults =
        row.copy(
            activeGrowthQuantity = activeGrowthQuantity,
            addedDate = addedDate,
            batchNumber = batchNumber,
            createdBy = createdBy,
            createdTime = createdTime,
            facilityId = facilityId,
            germinatingQuantity = germinatingQuantity,
            germinationRate = effectiveGerminationRate,
            hardeningOffQuantity = hardeningOffQuantity,
            totalGerminated = totalGerminated,
            totalGerminationCandidates = totalGerminationCandidates,
            latestObservedActiveGrowthQuantity = activeGrowthQuantity,
            latestObservedGerminatingQuantity = germinatingQuantity,
            latestObservedHardeningOffQuantity = hardeningOffQuantity,
            latestObservedReadyQuantity = readyQuantity,
            latestObservedTime = createdTime,
            lossRate = effectiveLossRate,
            totalLost = totalLost,
            totalLossCandidates = totalLossCandidates,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            organizationId = organizationId,
            projectId = projectId,
            readyQuantity = readyQuantity,
            readyByDate = readyByDate,
            speciesId = speciesId,
            version = version,
        )

    batchesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.batchIds.add(it) }
  }

  fun insertBatchSubLocation(
      batchId: BatchId = inserted.batchId,
      subLocationId: SubLocationId = inserted.subLocationId,
      facilityId: FacilityId? = null,
  ) {
    val effectiveFacilityId =
        facilityId ?: subLocationsDao.fetchOneById(subLocationId)!!.facilityId!!

    val row =
        BatchSubLocationsRow(
            batchId = batchId,
            facilityId = effectiveFacilityId,
            subLocationId = subLocationId,
        )

    batchSubLocationsDao.insert(row)
  }

  fun insertNurseryWithdrawal(
      row: WithdrawalsRow = WithdrawalsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      destinationFacilityId: FacilityId? = row.destinationFacilityId,
      facilityId: FacilityId = row.facilityId ?: inserted.facilityId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      purpose: WithdrawalPurpose = WithdrawalPurpose.Other,
      undoesWithdrawalId: WithdrawalId? = row.undoesWithdrawalId,
      withdrawnDate: LocalDate = row.withdrawnDate ?: LocalDate.EPOCH,
  ): WithdrawalId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            destinationFacilityId = destinationFacilityId,
            facilityId = facilityId,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            purposeId = purpose,
            undoesWithdrawalId = undoesWithdrawalId,
            withdrawnDate = withdrawnDate,
        )

    nurseryWithdrawalsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.withdrawalIds.add(it) }
  }

  fun insertSeedbankWithdrawal(
      row: SeedbankWithdrawalsRow = SeedbankWithdrawalsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      updatedTime: Instant = row.createdTime ?: Instant.EPOCH,
      withdrawnBy: UserId = row.withdrawnBy ?: currentUser().userId,
      date: LocalDate = row.date ?: LocalDate.EPOCH,
      accessionId: AccessionId = row.accessionId ?: inserted.accessionId,
      purpose: SeedbankWithdrawalPurpose? = row.purposeId,
      destination: String? = row.destination,
      staffResponsible: String? = row.staffResponsible,
      notes: String? = row.notes,
      viabilityTestId: ViabilityTestId? = row.viabilityTestId,
      withdrawnGrams: BigDecimal? = row.withdrawnGrams,
      withdrawnQuantity: BigDecimal? = row.withdrawnQuantity,
      withdrawnUnitsId: SeedQuantityUnits = row.withdrawnUnitsId ?: SeedQuantityUnits.Grams,
      estimatedCount: Int? = row.estimatedCount,
      estimatedWeightQuantity: BigDecimal? = row.estimatedWeightQuantity,
      estimatedWeightUnitsId: SeedQuantityUnits? = row.estimatedWeightUnitsId,
      batchId: BatchId? = row.batchId ?: inserted.batchId,
  ): SeedbankWithdrawalId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            updatedTime = updatedTime,
            withdrawnBy = withdrawnBy,
            date = date,
            accessionId = accessionId,
            purposeId = purpose,
            destination = destination,
            staffResponsible = staffResponsible,
            notes = notes,
            viabilityTestId = viabilityTestId,
            withdrawnGrams = withdrawnGrams,
            withdrawnQuantity = withdrawnQuantity,
            withdrawnUnitsId = withdrawnUnitsId,
            estimatedCount = estimatedCount,
            estimatedWeightQuantity = estimatedWeightQuantity,
            estimatedWeightUnitsId = estimatedWeightUnitsId,
            batchId = batchId,
        )

    withdrawalsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.seedbankWithdrawalIds.add(it) }
  }

  fun insertBatchWithdrawal(
      row: BatchWithdrawalsRow = BatchWithdrawalsRow(),
      activeGrowthQuantityWithdrawn: Int = row.activeGrowthQuantityWithdrawn ?: 0,
      batchId: BatchId = row.batchId ?: inserted.batchId,
      destinationBatchId: BatchId? = row.destinationBatchId,
      germinatingQuantityWithdrawn: Int = row.germinatingQuantityWithdrawn ?: 0,
      hardeningOffQuantityWithdrawn: Int = row.hardeningOffQuantityWithdrawn ?: 0,
      readyQuantityWithdrawn: Int = row.readyQuantityWithdrawn ?: 0,
      withdrawalId: WithdrawalId = row.withdrawalId ?: inserted.withdrawalId,
  ) {
    val rowWithDefaults =
        row.copy(
            activeGrowthQuantityWithdrawn = activeGrowthQuantityWithdrawn,
            batchId = batchId,
            destinationBatchId = destinationBatchId,
            germinatingQuantityWithdrawn = germinatingQuantityWithdrawn,
            hardeningOffQuantityWithdrawn = hardeningOffQuantityWithdrawn,
            readyQuantityWithdrawn = readyQuantityWithdrawn,
            withdrawalId = withdrawalId,
        )

    batchWithdrawalsDao.insert(rowWithDefaults)
  }

  var nextPlantingSiteNumber: Int = 1
  private lateinit var lastPlantingSitesRow: PlantingSitesRow

  fun insertPlantingSite(
      row: PlantingSitesRow = PlantingSitesRow(),
      areaHa: BigDecimal? = row.areaHa,
      x: Number? = null,
      y: Number? = if (x != null) 0 else null,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry? = row.boundary,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      exclusion: Geometry? = row.exclusion,
      gridOrigin: Geometry? = row.gridOrigin,
      organizationId: OrganizationId = row.organizationId ?: inserted.organizationId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: "Site ${nextPlantingSiteNumber++}",
      timeZone: ZoneId? = row.timeZone,
      projectId: ProjectId? = row.projectId,
      countryCode: String? = row.countryCode,
      insertHistory: Boolean = true,
      survivalRateIncludesTempPlots: Boolean = false,
  ): PlantingSiteId {
    val effectiveBoundary =
        when {
          boundary != null -> boundary
          x != null && y != null ->
              rectangle(
                  width.toDouble() * MONITORING_PLOT_SIZE,
                  height.toDouble() * MONITORING_PLOT_SIZE,
                  x.toDouble() * MONITORING_PLOT_SIZE,
                  y.toDouble() * MONITORING_PLOT_SIZE,
              )
          else -> null
        }

    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = effectiveBoundary,
            countryCode = countryCode,
            createdBy = createdBy,
            createdTime = createdTime,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            organizationId = organizationId,
            projectId = projectId,
            timeZone = timeZone,
            survivalRateIncludesTempPlots = survivalRateIncludesTempPlots,
        )

    plantingSitesDao.insert(rowWithDefaults)
    lastPlantingSitesRow = rowWithDefaults

    val plantingSiteId = rowWithDefaults.id!!
    inserted.plantingSiteIds.add(plantingSiteId)

    if (insertHistory && effectiveBoundary != null) {
      insertPlantingSiteHistory()
    }

    return plantingSiteId
  }

  fun insertPlantingSiteHistory(
      areaHa: BigDecimal? = lastPlantingSitesRow.areaHa,
      boundary: Geometry = lastPlantingSitesRow.boundary!!,
      createdBy: UserId = lastPlantingSitesRow.createdBy!!,
      createdTime: Instant = lastPlantingSitesRow.createdTime!!,
      exclusion: Geometry? = lastPlantingSitesRow.exclusion,
      gridOrigin: Geometry? = lastPlantingSitesRow.gridOrigin,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
  ): PlantingSiteHistoryId {
    val row =
        PlantingSiteHistoriesRow(
            areaHa = areaHa,
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            exclusion = exclusion,
            gridOrigin = gridOrigin,
            plantingSiteId = plantingSiteId,
        )

    plantingSiteHistoriesDao.insert(row)

    return row.id!!.also { inserted.plantingSiteHistoryIds.add(it) }
  }

  fun insertPlantingSeason(
      timeZone: ZoneId = ZoneOffset.UTC,
      endDate: LocalDate = LocalDate.EPOCH.plusDays(1),
      endTime: Instant = endDate.plusDays(1).toInstant(timeZone),
      isActive: Boolean = false,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      startDate: LocalDate = LocalDate.EPOCH,
      startTime: Instant = startDate.toInstant(timeZone),
  ): PlantingSeasonId {
    val row =
        PlantingSeasonsRow(
            endDate = endDate,
            endTime = endTime,
            isActive = isActive,
            plantingSiteId = plantingSiteId,
            startDate = startDate,
            startTime = startTime,
        )

    plantingSeasonsDao.insert(row)

    return row.id!!.also { inserted.plantingSeasonIds.add(it) }
  }

  fun insertPlantingSiteNotification(
      row: PlantingSiteNotificationsRow = PlantingSiteNotificationsRow(),
      number: Int = row.notificationNumber ?: 1,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      sentTime: Instant = row.sentTime ?: Instant.EPOCH,
      type: NotificationType,
  ): PlantingSiteNotificationId {
    val rowWithDefaults =
        row.copy(
            notificationNumber = number,
            notificationTypeId = type,
            plantingSiteId = plantingSiteId,
            sentTime = sentTime,
        )

    plantingSiteNotificationsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingSiteNotificationIds.add(it) }
  }

  private var nextPlantingZoneNumber: Int = 1
  private lateinit var lastPlantingZonesRow: PlantingZonesRow

  fun insertPlantingZone(
      row: PlantingZonesRow = PlantingZonesRow(),
      areaHa: BigDecimal = row.areaHa ?: BigDecimal.TEN,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          row.boundary
              ?: rectangle(
                  width.toDouble() * MONITORING_PLOT_SIZE,
                  height.toDouble() * MONITORING_PLOT_SIZE,
                  x.toDouble() * MONITORING_PLOT_SIZE,
                  y.toDouble() * MONITORING_PLOT_SIZE,
              ),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      errorMargin: BigDecimal = row.errorMargin ?: PlantingZoneModel.DEFAULT_ERROR_MARGIN,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: "Z${nextPlantingZoneNumber++}",
      numPermanentPlots: Int =
          row.numPermanentPlots ?: PlantingZoneModel.DEFAULT_NUM_PERMANENT_PLOTS,
      numTemporaryPlots: Int =
          row.numTemporaryPlots ?: PlantingZoneModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      stableId: Any = row.name ?: name,
      studentsT: BigDecimal = row.studentsT ?: PlantingZoneModel.DEFAULT_STUDENTS_T,
      targetPlantingDensity: BigDecimal? = row.targetPlantingDensity,
      variance: BigDecimal = row.variance ?: PlantingZoneModel.DEFAULT_VARIANCE,
      insertHistory: Boolean = true,
      boundaryModifiedBy: UserId = row.boundaryModifiedBy ?: modifiedBy,
      boundaryModifiedTime: Instant = row.boundaryModifiedTime ?: modifiedTime,
  ): PlantingZoneId {
    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = boundary,
            boundaryModifiedBy = boundaryModifiedBy,
            boundaryModifiedTime = boundaryModifiedTime,
            createdBy = createdBy,
            createdTime = createdTime,
            errorMargin = errorMargin,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            numPermanentPlots = numPermanentPlots,
            numTemporaryPlots = numTemporaryPlots,
            plantingSiteId = plantingSiteId,
            stableId = StableId("$stableId"),
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )

    plantingZonesDao.insert(rowWithDefaults)
    lastPlantingZonesRow = rowWithDefaults

    val plantingZoneId = rowWithDefaults.id!!
    inserted.plantingZoneIds.add(plantingZoneId)

    if (insertHistory && inserted.plantingSiteHistoryIds.isNotEmpty()) {
      insertPlantingZoneHistory()
    }

    return plantingZoneId
  }

  fun insertPlantingZoneHistory(
      areaHa: BigDecimal = lastPlantingZonesRow.areaHa!!,
      boundary: Geometry = lastPlantingZonesRow.boundary!!,
      name: String = lastPlantingZonesRow.name!!,
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      plantingZoneId: PlantingZoneId = inserted.plantingZoneId,
      stableId: Any = lastPlantingZonesRow.stableId!!,
  ): PlantingZoneHistoryId {
    val row =
        PlantingZoneHistoriesRow(
            areaHa = areaHa,
            boundary = boundary,
            name = name,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingZoneId = plantingZoneId,
            stableId = StableId("$stableId"),
        )

    plantingZoneHistoriesDao.insert(row)

    return row.id!!.also { inserted.plantingZoneHistoryIds.add(it) }
  }

  private var nextPlantingSubzoneNumber: Int = 1
  private lateinit var lastPlantingSubzonesRow: PlantingSubzonesRow

  fun insertPlantingSubzone(
      row: PlantingSubzonesRow = PlantingSubzonesRow(),
      areaHa: BigDecimal = row.areaHa ?: BigDecimal.ONE,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          row.boundary
              ?: rectangle(
                  width.toDouble() * MONITORING_PLOT_SIZE,
                  height.toDouble() * MONITORING_PLOT_SIZE,
                  x.toDouble() * MONITORING_PLOT_SIZE,
                  y.toDouble() * MONITORING_PLOT_SIZE,
              ),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      observedTime: Instant? = row.observedTime,
      plantingCompletedTime: Instant? = row.plantingCompletedTime,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      plantingZoneId: PlantingZoneId = row.plantingZoneId ?: inserted.plantingZoneId,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: "${nextPlantingSubzoneNumber++}",
      fullName: String = "${lastPlantingZonesRow.name}-$name",
      stableId: Any = row.fullName ?: fullName,
      insertHistory: Boolean = true,
  ): PlantingSubzoneId {
    val rowWithDefaults =
        row.copy(
            areaHa = areaHa,
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            fullName = fullName,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            observedTime = observedTime,
            plantingCompletedTime = plantingCompletedTime,
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId,
            stableId = StableId("$stableId"),
        )

    plantingSubzonesDao.insert(rowWithDefaults)
    lastPlantingSubzonesRow = rowWithDefaults

    val plantingSubzoneId = rowWithDefaults.id!!
    inserted.plantingSubzoneIds.add(plantingSubzoneId)

    if (insertHistory && inserted.plantingZoneHistoryIds.isNotEmpty()) {
      insertPlantingSubzoneHistory()
    }

    return plantingSubzoneId
  }

  fun insertPlantingSubzoneHistory(
      areaHa: BigDecimal = lastPlantingSubzonesRow.areaHa!!,
      boundary: Geometry = lastPlantingSubzonesRow.boundary!!,
      fullName: String = lastPlantingSubzonesRow.fullName!!,
      name: String = lastPlantingSubzonesRow.name!!,
      plantingSubzoneId: PlantingSubzoneId? = inserted.plantingSubzoneId,
      plantingZoneHistoryId: PlantingZoneHistoryId = inserted.plantingZoneHistoryId,
      stableId: Any = lastPlantingSubzonesRow.stableId!!,
  ): PlantingSubzoneHistoryId {
    val row =
        PlantingSubzoneHistoriesRow(
            areaHa = areaHa,
            boundary = boundary,
            fullName = fullName,
            name = name,
            plantingZoneHistoryId = plantingZoneHistoryId,
            plantingSubzoneId = plantingSubzoneId,
            stableId = StableId("$stableId"),
        )

    plantingSubzoneHistoriesDao.insert(row)

    return row.id!!.also { inserted.plantingSubzoneHistoryIds.add(it) }
  }

  private var nextModuleNumber: Int = 1

  fun insertModule(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      name: String = "Module $nextModuleNumber",
      position: Int = nextModuleNumber,
      overview: String? = null,
      preparationMaterials: String? = null,
      liveSessionDescription: String? = null,
      workshopDescription: String? = null,
      oneOnOneSessionDescription: String? = null,
      additionalResources: String? = null,
      phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy,
  ): ModuleId {
    nextModuleNumber++

    val row =
        ModulesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
            overview = overview,
            position = position,
            preparationMaterials = preparationMaterials,
            liveSessionDescription = liveSessionDescription,
            workshopDescription = workshopDescription,
            oneOnOneSessionDescription = oneOnOneSessionDescription,
            additionalResources = additionalResources,
            phaseId = phase,
        )

    modulesDao.insert(row)

    return row.id!!.also { inserted.moduleIds.add(it) }
  }

  private var nextMonitoringPlotNumber: Long = 1
  private lateinit var lastMonitoringPlotsRow: MonitoringPlotsRow

  fun insertMonitoringPlot(
      row: MonitoringPlotsRow = MonitoringPlotsRow(),
      x: Number = 0,
      y: Number = 0,
      elevationMeters: BigDecimal? = row.elevationMeters,
      sizeMeters: Int = row.sizeMeters ?: MONITORING_PLOT_SIZE_INT,
      boundary: Polygon =
          (row.boundary as? Polygon)
              ?: rectanglePolygon(
                  sizeMeters,
                  sizeMeters,
                  x.toDouble() * sizeMeters.toDouble(),
                  y.toDouble() * sizeMeters.toDouble(),
              ),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      isAdHoc: Boolean = row.isAdHoc ?: false,
      isAvailable: Boolean = row.isAvailable ?: true,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      organizationId: OrganizationId = row.organizationId ?: inserted.organizationId,
      permanentIndex: Int? = row.permanentIndex,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      plantingSubzoneId: PlantingSubzoneId? =
          row.plantingSubzoneId ?: inserted.plantingSubzoneIds.lastOrNull(),
      plotNumber: Long =
          row.plotNumber
              ?: IdentifierGenerator(TestClock(), dslContext)
                  .generateNumericIdentifier(organizationId, NumericIdentifierType.PlotNumber),
      insertHistory: Boolean = true,
  ): MonitoringPlotId {
    val rowWithDefaults =
        row.copy(
            boundary = boundary,
            createdBy = createdBy,
            createdTime = createdTime,
            elevationMeters = elevationMeters,
            isAdHoc = isAdHoc,
            isAvailable = isAvailable,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            organizationId = organizationId,
            permanentIndex = permanentIndex,
            plantingSiteId = plantingSiteId,
            plantingSubzoneId = plantingSubzoneId,
            plotNumber = plotNumber,
            sizeMeters = sizeMeters,
        )

    monitoringPlotsDao.insert(rowWithDefaults)
    lastMonitoringPlotsRow = rowWithDefaults

    val monitoringPlotId = rowWithDefaults.id!!
    inserted.monitoringPlotIds.add(monitoringPlotId)

    if (insertHistory && inserted.plantingSiteHistoryIds.isNotEmpty()) {
      insertMonitoringPlotHistory(plantingSubzoneId = plantingSubzoneId)
    }

    return monitoringPlotId
  }

  fun insertMonitoringPlotHistory(
      createdBy: UserId = lastMonitoringPlotsRow.createdBy!!,
      createdTime: Instant = lastMonitoringPlotsRow.createdTime!!,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      plantingSubzoneId: PlantingSubzoneId? =
          if (inserted.plantingSubzoneIds.isNotEmpty()) inserted.plantingSubzoneId else null,
      plantingSubzoneHistoryId: PlantingSubzoneHistoryId? =
          if (plantingSubzoneId != null) inserted.plantingSubzoneHistoryId else null,
  ): MonitoringPlotHistoryId {
    val row =
        MonitoringPlotHistoriesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            monitoringPlotId = monitoringPlotId,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = plantingSiteId,
            plantingSubzoneHistoryId = plantingSubzoneHistoryId,
            plantingSubzoneId = plantingSubzoneId,
        )

    monitoringPlotHistoriesDao.insert(row)

    return row.id!!.also { inserted.monitoringPlotHistoryIds.add(it) }
  }

  fun insertMonitoringPlotOverlap(
      overlapsPlotId: MonitoringPlotId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
  ) {
    monitoringPlotOverlapsDao.insert(MonitoringPlotOverlapsRow(monitoringPlotId, overlapsPlotId))
  }

  fun insertDisclaimer(
      row: DisclaimersRow = DisclaimersRow(),
      content: String = row.content ?: "Disclaimer",
      effectiveOn: Instant = row.effectiveOn ?: Instant.EPOCH,
  ): DisclaimerId {
    val rowWithDefaults =
        row.copy(
            content = content,
            effectiveOn = effectiveOn,
        )

    disclaimersDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.disclaimerIds.add(it) }
  }

  private var nextDraftPlantingSiteNumber = 1

  fun insertDraftPlantingSite(
      row: DraftPlantingSitesRow = DraftPlantingSitesRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      data: ArbitraryJsonObject = row.data ?: JSONB.valueOf("{}"),
      description: String? = row.description,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      name: String = row.name ?: "Draft site ${nextDraftPlantingSiteNumber++}",
      numPlantingSubzones: Int? = row.numPlantingSubzones,
      numPlantingZones: Int? = row.numPlantingZones,
      organizationId: OrganizationId = row.organizationId ?: inserted.organizationId,
      projectId: ProjectId? = row.projectId,
      timeZone: ZoneId? = row.timeZone,
  ): DraftPlantingSiteId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            data = data,
            description = description,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            numPlantingSubzones = numPlantingSubzones,
            numPlantingZones = numPlantingZones,
            organizationId = organizationId,
            projectId = projectId,
            timeZone = timeZone,
        )

    draftPlantingSitesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.draftPlantingSiteIds.add(it) }
  }

  fun insertDelivery(
      row: DeliveriesRow = DeliveriesRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: createdBy,
      modifiedTime: Instant = row.modifiedTime ?: createdTime,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      withdrawalId: WithdrawalId = row.withdrawalId ?: inserted.withdrawalId,
  ): DeliveryId {
    val rowWithDetails =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSiteId = plantingSiteId,
            withdrawalId = withdrawalId,
        )

    deliveriesDao.insert(rowWithDetails)

    return rowWithDetails.id!!.also { inserted.deliveryIds.add(it) }
  }

  fun insertPlanting(
      row: PlantingsRow = PlantingsRow(),
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      deliveryId: DeliveryId = row.deliveryId ?: inserted.deliveryId,
      numPlants: Int = row.numPlants ?: 1,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      plantingTypeId: PlantingType = row.plantingTypeId ?: PlantingType.Delivery,
      plantingSubzoneId: PlantingSubzoneId? =
          row.plantingSubzoneId ?: inserted.plantingSubzoneIds.lastOrNull(),
      speciesId: SpeciesId = row.speciesId ?: inserted.speciesId,
  ): PlantingId {
    val rowWithDefaults =
        row.copy(
            createdBy = createdBy,
            createdTime = createdTime,
            deliveryId = deliveryId,
            numPlants = numPlants,
            plantingSiteId = plantingSiteId,
            plantingTypeId = plantingTypeId,
            plantingSubzoneId = plantingSubzoneId,
            speciesId = speciesId,
        )

    plantingsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.plantingIds.add(it) }
  }

  fun insertPlantingSitePopulation(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingSitePopulationsDao.insert(
        PlantingSitePopulationsRow(
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        )
    )
  }

  fun insertPlantingSubzonePopulation(
      plantingSubzoneId: PlantingSubzoneId = inserted.plantingSubzoneId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingSubzonePopulationsDao.insert(
        PlantingSubzonePopulationsRow(
            plantingSubzoneId = plantingSubzoneId,
            speciesId = speciesId,
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        )
    )
  }

  fun insertPlantingZonePopulation(
      plantingZoneId: PlantingZoneId = inserted.plantingZoneId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    plantingZonePopulationsDao.insert(
        PlantingZonePopulationsRow(
            plantingZoneId = plantingZoneId,
            speciesId = speciesId,
            totalPlants = totalPlants,
            plantsSinceLastObservation = plantsSinceLastObservation,
        )
    )
  }

  fun addPlantingSubzonePopulation(
      plantingSubzoneId: PlantingSubzoneId = inserted.plantingSubzoneId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    with(PLANTING_SUBZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_SUBZONE_POPULATIONS)
          .set(PLANTING_SUBZONE_ID, plantingSubzoneId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, totalPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(totalPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation),
          )
          .execute()
    }
  }

  fun addPlantingZonePopulation(
      plantingZoneId: PlantingZoneId = inserted.plantingZoneId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
      plantsSinceLastObservation: Int = totalPlants,
  ) {
    with(PLANTING_ZONE_POPULATIONS) {
      dslContext
          .insertInto(PLANTING_ZONE_POPULATIONS)
          .set(PLANTING_ZONE_ID, plantingZoneId)
          .set(SPECIES_ID, speciesId)
          .set(TOTAL_PLANTS, totalPlants)
          .set(PLANTS_SINCE_LAST_OBSERVATION, plantsSinceLastObservation)
          .onDuplicateKeyUpdate()
          .set(TOTAL_PLANTS, TOTAL_PLANTS.plus(totalPlants))
          .set(
              PLANTS_SINCE_LAST_OBSERVATION,
              PLANTS_SINCE_LAST_OBSERVATION.plus(plantsSinceLastObservation),
          )
          .execute()
    }
  }

  fun insertSeedFundReport(
      row: SeedFundReportsRow = SeedFundReportsRow(),
      body: String = row.body?.data() ?: """{"version":"1","organizationName":"org"}""",
      lockedBy: UserId? = row.lockedBy,
      lockedTime: Instant? = row.lockedTime ?: lockedBy?.let { Instant.EPOCH },
      organizationId: OrganizationId = row.organizationId ?: inserted.organizationId,
      projectId: ProjectId? = row.projectId,
      projectName: String? = row.projectName,
      quarter: Int = row.quarter ?: 1,
      submittedBy: UserId? = row.submittedBy,
      submittedTime: Instant? = row.submittedTime ?: submittedBy?.let { Instant.EPOCH },
      status: SeedFundReportStatus =
          row.statusId
              ?: when {
                lockedBy != null -> SeedFundReportStatus.Locked
                submittedBy != null -> SeedFundReportStatus.Submitted
                else -> SeedFundReportStatus.New
              },
      year: Int = row.year ?: 1970,
  ): SeedFundReportId {
    val projectNameWithDefault =
        projectName ?: projectId?.let { projectsDao.fetchOneById(it)?.name }

    val rowWithDefaults =
        row.copy(
            body = JSONB.jsonb(body),
            lockedBy = lockedBy,
            lockedTime = lockedTime,
            organizationId = organizationId,
            projectId = projectId,
            projectName = projectNameWithDefault,
            quarter = quarter,
            statusId = status,
            submittedBy = submittedBy,
            submittedTime = submittedTime,
            year = year,
        )

    seedFundReportsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.seedFundReportIds.add(it) }
  }

  fun insertStandardMetric(
      row: StandardMetricsRow = StandardMetricsRow(),
      component: MetricComponent = row.componentId ?: MetricComponent.ProjectObjectives,
      description: String? = row.description,
      isPublishable: Boolean = row.isPublishable ?: true,
      name: String = row.name ?: "Metric name",
      reference: String = row.reference ?: "1.1",
      type: MetricType = row.typeId ?: MetricType.Impact,
  ): StandardMetricId {
    val rowWithDefaults =
        row.copy(
            componentId = component,
            description = description,
            isPublishable = isPublishable,
            name = name,
            reference = reference,
            typeId = type,
        )

    standardMetricsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.standardMetricIds.add(it) }
  }

  fun insertOrganizationReportSettings(
      organizationId: OrganizationId = inserted.organizationId,
      isEnabled: Boolean = true,
  ) {
    val row =
        OrganizationReportSettingsRow(
            isEnabled = isEnabled,
            organizationId = organizationId,
        )

    organizationReportSettingsDao.insert(row)
  }

  fun insertProjectReportConfig(
      row: ProjectReportConfigsRow = ProjectReportConfigsRow(),
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      frequency: ReportFrequency = row.reportFrequencyId ?: ReportFrequency.Quarterly,
      reportingStartDate: LocalDate = row.reportingStartDate ?: LocalDate.EPOCH,
      reportingEndDate: LocalDate = row.reportingEndDate ?: reportingStartDate.plusDays(1),
  ): ProjectReportConfigId {
    val rowWithDefaults =
        row.copy(
            projectId = projectId,
            reportFrequencyId = frequency,
            reportingStartDate = reportingStartDate,
            reportingEndDate = reportingEndDate,
        )
    projectReportConfigsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.projectReportConfigIds.add(it) }
  }

  fun insertProjectReportSettings(
      projectId: ProjectId = inserted.projectId,
      isEnabled: Boolean = true,
  ) {
    val row =
        ProjectReportSettingsRow(
            isEnabled = isEnabled,
            projectId = projectId,
        )

    projectReportSettingsDao.insert(row)
  }

  fun insertObservation(
      row: ObservationsRow = ObservationsRow(),
      createdTime: Instant = Instant.EPOCH,
      endDate: LocalDate = row.endDate ?: LocalDate.of(2023, 1, 31),
      isAdHoc: Boolean = row.isAdHoc ?: false,
      observationType: ObservationType = row.observationTypeId ?: ObservationType.Monitoring,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      startDate: LocalDate = row.startDate ?: LocalDate.of(2023, 1, 1),
      completedTime: Instant? = row.completedTime,
      state: ObservationState =
          row.stateId
              ?: if (completedTime != null) {
                ObservationState.Completed
              } else {
                ObservationState.InProgress
              },
      plantingSiteHistoryId: PlantingSiteHistoryId? =
          row.plantingSiteHistoryId
              ?: if (state != ObservationState.Upcoming) {
                inserted.plantingSiteHistoryId
              } else {
                null
              },
      upcomingNotificationSentTime: Instant? = row.upcomingNotificationSentTime,
  ): ObservationId {
    val rowWithDefaults =
        row.copy(
            completedTime = completedTime,
            createdTime = createdTime,
            endDate = endDate,
            isAdHoc = isAdHoc,
            observationTypeId = observationType,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = plantingSiteId,
            startDate = startDate,
            stateId = state,
            upcomingNotificationSentTime = upcomingNotificationSentTime,
        )

    observationsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.observationIds.add(it) }
  }

  fun insertObservationBiomassDetails(
      row: ObservationBiomassDetailsRow = ObservationBiomassDetailsRow(),
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      description: String? = row.description,
      forestType: BiomassForestType = row.forestTypeId ?: BiomassForestType.Terrestrial,
      smallTreesCountLow: Int = row.smallTreesCountLow ?: 0,
      smallTreesCountHigh: Int = row.smallTreesCountHigh ?: 0,
      herbaceousCoverPercent: Int = row.herbaceousCoverPercent ?: 0,
      soilAssessment: String = row.soilAssessment ?: "Soil Assessment",
      waterDepthCm: Int? = row.waterDepthCm,
      salinityPpt: BigDecimal? = row.salinityPpt,
      ph: BigDecimal? = row.ph,
      tideId: MangroveTide? = row.tideId,
      tideTime: Instant? = row.tideTime,
  ) {
    val rowWithDefaults =
        row.copy(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            description = description,
            forestTypeId = forestType,
            smallTreesCountLow = smallTreesCountLow,
            smallTreesCountHigh = smallTreesCountHigh,
            herbaceousCoverPercent = herbaceousCoverPercent,
            soilAssessment = soilAssessment,
            waterDepthCm = waterDepthCm,
            salinityPpt = salinityPpt,
            ph = ph,
            tideId = tideId,
            tideTime = tideTime,
        )

    observationBiomassDetailsDao.insert(rowWithDefaults)
  }

  fun insertObservationBiomassSpecies(
      row: ObservationBiomassSpeciesRow = ObservationBiomassSpeciesRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      speciesId: SpeciesId? = row.speciesId,
      scientificName: String? = row.scientificName,
      commonName: String? = row.commonName,
      isInvasive: Boolean = row.isInvasive ?: false,
      isThreatened: Boolean = row.isThreatened ?: false,
  ): BiomassSpeciesId {
    val rowWithDefaults =
        row.copy(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            speciesId = speciesId,
            scientificName = scientificName,
            commonName = commonName,
            isInvasive = isInvasive,
            isThreatened = isThreatened,
        )

    observationBiomassSpeciesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.biomassSpeciesIds.add(it) }
  }

  fun insertObservationBiomassQuadratDetails(
      row: ObservationBiomassQuadratDetailsRow = ObservationBiomassQuadratDetailsRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.SouthwestCorner,
      description: String? = row.description,
  ) {
    val rowWithDefaults =
        row.copy(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            positionId = position,
            description = description,
        )

    observationBiomassQuadratDetailsDao.insert(rowWithDefaults)
  }

  fun insertObservationBiomassQuadratSpecies(
      row: ObservationBiomassQuadratSpeciesRow = ObservationBiomassQuadratSpeciesRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.SouthwestCorner,
      biomassSpeciesId: BiomassSpeciesId? = row.biomassSpeciesId,
      abundancePercent: Int = row.abundancePercent ?: 0,
  ) {
    with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
      dslContext
          .insertInto(this)
          .set(OBSERVATION_ID, observationId)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(POSITION_ID, position)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(ABUNDANCE_PERCENT, abundancePercent)
          .execute()
    }
  }

  fun insertObservationPhoto(
      row: ObservationPhotosRow = ObservationPhotosRow(),
      caption: String? = row.caption,
      fileId: FileId = row.fileId ?: inserted.fileId,
      isOriginal: Boolean = row.isOriginal ?: true,
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.SouthwestCorner,
      type: ObservationPhotoType = row.typeId ?: ObservationPhotoType.Plot,
  ) {
    val rowWithDefaults =
        row.copy(
            caption = caption,
            fileId = fileId,
            isOriginal = isOriginal,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            positionId = position,
            typeId = type,
        )

    observationPhotosDao.insert(rowWithDefaults)
  }

  fun insertObservationPlot(
      row: ObservationPlotsRow = ObservationPlotsRow(),
      completedBy: UserId? = row.completedBy,
      completedTime: Instant? =
          row.completedTime ?: if (completedBy != null) Instant.EPOCH else null,
      claimedBy: UserId? = row.claimedBy ?: completedBy,
      claimedTime: Instant? = row.claimedTime ?: if (claimedBy != null) Instant.EPOCH else null,
      createdBy: UserId = row.createdBy ?: currentUser().userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      isPermanent: Boolean = row.isPermanent ?: false,
      monitoringPlotHistoryId: MonitoringPlotHistoryId =
          row.monitoringPlotHistoryId ?: inserted.monitoringPlotHistoryId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      statusId: ObservationPlotStatus =
          row.statusId
              ?: when {
                completedTime != null -> ObservationPlotStatus.Completed
                claimedTime != null -> ObservationPlotStatus.Claimed
                else -> ObservationPlotStatus.Unclaimed
              },
  ) {
    val rowWithDefaults =
        row.copy(
            claimedBy = claimedBy,
            claimedTime = claimedTime,
            completedBy = completedBy,
            completedTime = completedTime,
            createdBy = createdBy,
            createdTime = createdTime,
            isPermanent = isPermanent,
            observationId = observationId,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            monitoringPlotHistoryId = monitoringPlotHistoryId,
            monitoringPlotId = monitoringPlotId,
            statusId = statusId,
        )

    observationPlotsDao.insert(rowWithDefaults)
  }

  fun insertObservationPlotCondition(
      row: ObservationPlotConditionsRow = ObservationPlotConditionsRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      condition: ObservableCondition = row.conditionId ?: ObservableCondition.AnimalDamage,
  ) {
    val rowWithDefaults =
        row.copy(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            conditionId = condition,
        )

    observationPlotConditionsDao.insert(rowWithDefaults)
  }

  fun insertObservationRequestedSubzone(
      observationId: ObservationId = inserted.observationId,
      plantingSubzoneId: PlantingSubzoneId = inserted.plantingSubzoneId,
  ) {
    observationRequestedSubzonesDao.insert(
        ObservationRequestedSubzonesRow(observationId, plantingSubzoneId)
    )
  }

  fun insertObservedCoordinates(
      row: ObservedPlotCoordinatesRow = ObservedPlotCoordinatesRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      gpsCoordinates: Point = row.gpsCoordinates?.centroid ?: point(1),
      position: ObservationPlotPosition = row.positionId ?: ObservationPlotPosition.NortheastCorner,
  ): ObservedPlotCoordinatesId {
    val rowWithDefaults =
        row.copy(
            gpsCoordinates = gpsCoordinates,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            positionId = position,
        )

    observedPlotCoordinatesDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  fun insertObservedPlotSpeciesTotals(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      certainty: RecordedSpeciesCertainty = RecordedSpeciesCertainty.Known,
      speciesId: SpeciesId? =
          if (certainty == RecordedSpeciesCertainty.Known) {
            inserted.speciesId
          } else {
            null
          },
      speciesName: String? =
          if (certainty == RecordedSpeciesCertainty.Other) {
            "Other species"
          } else {
            null
          },
      totalLive: Int = 0,
      totalDead: Int = 0,
      totalExisting: Int = 0,
      cumulativeDead: Int = 0,
      permanentLive: Int = 0,
      mortalityRate: Int =
          if (cumulativeDead + permanentLive == 0) {
            0
          } else {
            (cumulativeDead * 100.0 / (cumulativeDead + permanentLive)).roundToInt()
          },
      survivalRate: Int? = null,
  ) {
    with(OBSERVED_PLOT_SPECIES_TOTALS) {
      dslContext
          .insertInto(this)
          .set(OBSERVATION_ID, observationId)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(CUMULATIVE_DEAD, cumulativeDead)
          .set(PERMANENT_LIVE, permanentLive)
          .set(MORTALITY_RATE, mortalityRate)
          .set(SURVIVAL_RATE, survivalRate)
          .execute()
    }
  }

  fun insertObservedSiteSpeciesTotals(
      row: ObservedSiteSpeciesTotalsRow = ObservedSiteSpeciesTotalsRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      plantingSiteId: PlantingSiteId = row.plantingSiteId ?: inserted.plantingSiteId,
      certainty: RecordedSpeciesCertainty = row.certaintyId ?: RecordedSpeciesCertainty.Known,
      speciesId: SpeciesId? =
          row.speciesId
              ?: if (certainty == RecordedSpeciesCertainty.Known) {
                inserted.speciesId
              } else {
                null
              },
      speciesName: String? =
          row.speciesName
              ?: if (certainty == RecordedSpeciesCertainty.Other) {
                "Other species"
              } else {
                null
              },
      totalLive: Int = row.totalLive ?: 0,
      totalDead: Int = row.totalDead ?: 0,
      totalExisting: Int = row.totalExisting ?: 0,
      cumulativeDead: Int = row.cumulativeDead ?: 0,
      permanentLive: Int = row.permanentLive ?: 0,
      mortalityRate: Int =
          row.mortalityRate
              ?: if (cumulativeDead + permanentLive == 0) {
                0
              } else {
                (cumulativeDead * 100.0 / (cumulativeDead + permanentLive)).roundToInt()
              },
  ) {
    with(OBSERVED_SITE_SPECIES_TOTALS) {
      dslContext
          .insertInto(OBSERVED_SITE_SPECIES_TOTALS)
          .set(OBSERVATION_ID, observationId)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(CUMULATIVE_DEAD, cumulativeDead)
          .set(PERMANENT_LIVE, permanentLive)
          .set(MORTALITY_RATE, mortalityRate)
          .execute()
    }
  }

  fun insertPlantingZoneT0TempDensity(
      row: PlantingZoneT0TempDensitiesRow = PlantingZoneT0TempDensitiesRow(),
      plantingZoneId: PlantingZoneId = row.plantingZoneId ?: inserted.plantingZoneId,
      speciesId: SpeciesId = row.speciesId ?: inserted.speciesId,
      zoneDensity: BigDecimal = row.zoneDensity ?: BigDecimal.valueOf(10),
      createdBy: UserId = row.createdBy ?: inserted.userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: inserted.userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
      dslContext
          .insertInto(this)
          .set(PLANTING_ZONE_ID, plantingZoneId)
          .set(SPECIES_ID, speciesId)
          .set(ZONE_DENSITY, zoneDensity)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_BY, modifiedBy)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  fun insertPlotT0Density(
      row: PlotT0DensitiesRow = PlotT0DensitiesRow(),
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      speciesId: SpeciesId = row.speciesId ?: inserted.speciesId,
      plotDensity: BigDecimal = row.plotDensity ?: BigDecimal.valueOf(10),
      createdBy: UserId = row.createdBy ?: inserted.userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: inserted.userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    with(PLOT_T0_DENSITIES) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(SPECIES_ID, speciesId)
          .set(PLOT_DENSITY, plotDensity)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_BY, modifiedBy)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  fun insertPlotT0Observation(
      row: PlotT0ObservationsRow = PlotT0ObservationsRow(),
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      createdBy: UserId = row.createdBy ?: inserted.userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: inserted.userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    with(PLOT_T0_OBSERVATIONS) {
      dslContext
          .insertInto(this)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(OBSERVATION_ID, observationId)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_BY, modifiedBy)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  private val nextTreeNumber = mutableMapOf<ObservationId, Int>()
  private val nextTrunkNumber = mutableMapOf<Pair<ObservationId, Int>, Int>()

  fun insertRecordedTree(
      row: RecordedTreesRow = RecordedTreesRow(),
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      biomassSpeciesId: BiomassSpeciesId? = row.biomassSpeciesId ?: inserted.biomassSpeciesId,
      treeNumber: Int = row.treeNumber ?: nextTreeNumber.getOrDefault(observationId, 1),
      trunkNumber: Int =
          row.trunkNumber ?: nextTrunkNumber.getOrDefault(observationId to treeNumber, 1),
      treeGrowthForm: TreeGrowthForm = row.treeGrowthFormId ?: TreeGrowthForm.Tree,
      isDead: Boolean = row.isDead ?: false,
      diameterAtBreastHeightCm: BigDecimal? = row.diameterAtBreastHeightCm,
      pointOfMeasurementM: BigDecimal? = row.pointOfMeasurementM,
      heightM: BigDecimal? = row.heightM,
      shrubDiameterCm: Int? = row.shrubDiameterCm,
      description: String? = row.description,
  ): RecordedTreeId {
    val rowWithDefaults =
        row.copy(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            biomassSpeciesId = biomassSpeciesId,
            treeNumber = treeNumber,
            trunkNumber = trunkNumber,
            treeGrowthFormId = treeGrowthForm,
            isDead = isDead,
            diameterAtBreastHeightCm = diameterAtBreastHeightCm,
            pointOfMeasurementM = pointOfMeasurementM,
            heightM = heightM,
            shrubDiameterCm = shrubDiameterCm,
            description = description,
        )

    recordedTreesDao.insert(rowWithDefaults)
    nextTreeNumber[observationId] = treeNumber + 1
    nextTrunkNumber[observationId to treeNumber] = trunkNumber + 1

    return rowWithDefaults.id!!.also { inserted.recordedTreeIds.add(it) }
  }

  fun insertRecordedPlant(
      row: RecordedPlantsRow = RecordedPlantsRow(),
      gpsCoordinates: Point = row.gpsCoordinates?.centroid ?: point(1),
      monitoringPlotId: MonitoringPlotId = row.monitoringPlotId ?: inserted.monitoringPlotId,
      observationId: ObservationId = row.observationId ?: inserted.observationId,
      speciesId: SpeciesId? = row.speciesId,
      speciesName: String? = row.speciesName,
      certainty: RecordedSpeciesCertainty =
          row.certaintyId
              ?: when {
                speciesId != null -> RecordedSpeciesCertainty.Known
                speciesName != null -> RecordedSpeciesCertainty.Other
                else -> RecordedSpeciesCertainty.Unknown
              },
      status: RecordedPlantStatus = row.statusId ?: RecordedPlantStatus.Live,
  ): RecordedPlantId {
    val rowWithDefaults =
        row.copy(
            certaintyId = certainty,
            gpsCoordinates = gpsCoordinates,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            speciesId = speciesId,
            speciesName = speciesName,
            statusId = status,
        )

    recordedPlantsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!
  }

  protected fun insertReport(
      row: ReportsRow = ReportsRow(),
      configId: ProjectReportConfigId = row.configId ?: inserted.projectReportConfigId,
      projectId: ProjectId = row.projectId ?: inserted.projectId,
      frequency: ReportFrequency = row.reportFrequencyId ?: ReportFrequency.Quarterly,
      quarter: ReportQuarter? = row.reportQuarterId ?: ReportQuarter.Q1,
      status: ReportStatus = row.statusId ?: ReportStatus.NotSubmitted,
      startDate: LocalDate = row.startDate ?: LocalDate.EPOCH,
      endDate: LocalDate = row.endDate ?: LocalDate.EPOCH.plusDays(1),
      highlights: String? = row.highlights,
      internalComment: String? = row.internalComment,
      feedback: String? = row.feedback,
      additionalComments: String? = row.additionalComments,
      financialSummaries: String? = row.financialSummaries,
      createdBy: UserId = row.createdBy ?: inserted.userId,
      createdTime: Instant = row.createdTime ?: Instant.EPOCH,
      modifiedBy: UserId = row.modifiedBy ?: inserted.userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
      submittedBy: UserId? =
          row.submittedBy
              ?: if (status != ReportStatus.NotSubmitted && status != ReportStatus.NotNeeded) {
                inserted.userId
              } else {
                null
              },
      submittedTime: Instant? = row.submittedTime ?: submittedBy?.let { Instant.EPOCH },
      upcomingNotificationSentTime: Instant? = row.upcomingNotificationSentTime,
  ): ReportId {
    val rowWithDefaults =
        row.copy(
            configId = configId,
            projectId = projectId,
            reportFrequencyId = frequency,
            reportQuarterId = quarter,
            statusId = status,
            startDate = startDate,
            endDate = endDate,
            highlights = highlights,
            internalComment = internalComment,
            feedback = feedback,
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            submittedBy = submittedBy,
            submittedTime = submittedTime,
            upcomingNotificationSentTime = upcomingNotificationSentTime,
            additionalComments = additionalComments,
            financialSummaries = financialSummaries,
        )

    reportsDao.insert(rowWithDefaults)

    return rowWithDefaults.id!!.also { inserted.reportIds.add(it) }
  }

  private val nextReportAchievementPosition = mutableMapOf<ReportId, Int>()

  protected fun insertReportAchievement(
      row: ReportAchievementsRow = ReportAchievementsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      position: Int = row.position ?: nextReportAchievementPosition.getOrDefault(reportId, 0),
      achievement: String = row.achievement ?: "Achievement $position",
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            position = position,
            achievement = achievement,
        )

    reportAchievementsDao.insert(rowWithDefaults)

    nextReportAchievementPosition[reportId] = position + 1
  }

  private val nextReportChallengePosition = mutableMapOf<ReportId, Int>()

  protected fun insertReportChallenge(
      row: ReportChallengesRow = ReportChallengesRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      position: Int = row.position ?: nextReportChallengePosition.getOrDefault(reportId, 0),
      challenge: String = row.challenge ?: "Challenge $position",
      mitigationPlan: String = row.mitigationPlan ?: "Mitigation Plan $position",
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            position = position,
            challenge = challenge,
            mitigationPlan = mitigationPlan,
        )

    reportChallengesDao.insert(rowWithDefaults)

    nextReportChallengePosition[reportId] = position + 1
  }

  protected fun insertReportPhoto(
      row: ReportPhotosRow = ReportPhotosRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      fileId: FileId = row.fileId ?: inserted.fileId,
      caption: String? = row.caption,
      deleted: Boolean = row.deleted ?: false,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            fileId = fileId,
            caption = caption,
            deleted = deleted,
        )

    reportPhotosDao.insert(rowWithDefaults)
  }

  protected fun insertReportProjectMetric(
      row: ReportProjectMetricsRow = ReportProjectMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metricId: ProjectMetricId = row.projectMetricId ?: inserted.projectMetricId,
      target: Int? = row.target,
      value: Int? = row.value,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
      modifiedBy: UserId = row.modifiedBy ?: currentUser().userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            projectMetricId = metricId,
            target = target,
            value = value,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportProjectMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertReportStandardMetric(
      row: ReportStandardMetricsRow = ReportStandardMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metricId: StandardMetricId = row.standardMetricId ?: inserted.standardMetricId,
      target: Int? = row.target,
      value: Int? = row.value,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
      modifiedBy: UserId = row.modifiedBy ?: currentUser().userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            standardMetricId = metricId,
            target = target,
            value = value,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportStandardMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertReportSystemMetric(
      row: ReportSystemMetricsRow = ReportSystemMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metric: SystemMetric = row.systemMetricId ?: SystemMetric.SeedsCollected,
      target: Int? = row.target,
      systemValue: Int? = row.systemValue,
      systemTime: Instant? = row.systemTime ?: systemValue?.let { Instant.EPOCH },
      overrideValue: Int? = row.overrideValue,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
      modifiedBy: UserId = row.modifiedBy ?: currentUser().userId,
      modifiedTime: Instant = row.modifiedTime ?: Instant.EPOCH,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            systemMetricId = metric,
            target = target,
            systemValue = systemValue,
            systemTime = systemTime,
            overrideValue = overrideValue,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportSystemMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedReport(
      reportId: ReportId = inserted.reportId,
      projectId: ProjectId = inserted.projectId,
      startDate: LocalDate = LocalDate.of(2025, 1, 1),
      endDate: LocalDate = LocalDate.of(2025, 3, 31),
      frequency: ReportFrequency = ReportFrequency.Quarterly,
      quarter: ReportQuarter? = ReportQuarter.Q1,
      publishedBy: UserId = currentUser().userId,
      publishedTime: Instant = Instant.EPOCH,
      additionalComments: String? = null,
      financialSummaries: String? = null,
      highlights: String? = null,
  ) {
    with(PUBLISHED_REPORTS) {
      dslContext
          .insertInto(PUBLISHED_REPORTS)
          .set(REPORT_ID, reportId)
          .set(PROJECT_ID, projectId)
          .set(REPORT_FREQUENCY_ID, frequency)
          .set(REPORT_QUARTER_ID, quarter)
          .set(START_DATE, startDate)
          .set(END_DATE, endDate)
          .set(ADDITIONAL_COMMENTS, additionalComments)
          .set(FINANCIAL_SUMMARIES, financialSummaries)
          .set(HIGHLIGHTS, highlights)
          .set(PUBLISHED_BY, publishedBy)
          .set(PUBLISHED_TIME, publishedTime)
          .execute()
    }
  }

  protected fun insertPublishedReportAchievement(
      reportId: ReportId = inserted.reportId,
      position: Int = 1,
      achievement: String = "Achievement",
  ) {
    with(PUBLISHED_REPORT_ACHIEVEMENTS) {
      dslContext
          .insertInto(PUBLISHED_REPORT_ACHIEVEMENTS)
          .set(REPORT_ID, reportId)
          .set(POSITION, position)
          .set(ACHIEVEMENT, achievement)
          .execute()
    }
  }

  protected fun insertPublishedReportChallenge(
      reportId: ReportId = inserted.reportId,
      position: Int = 1,
      challenge: String = "Challenge",
      mitigationPlan: String = "Mitigation",
  ) {
    with(PUBLISHED_REPORT_CHALLENGES) {
      dslContext
          .insertInto(PUBLISHED_REPORT_CHALLENGES)
          .set(REPORT_ID, reportId)
          .set(POSITION, position)
          .set(CHALLENGE, challenge)
          .set(MITIGATION_PLAN, mitigationPlan)
          .execute()
    }
  }

  protected fun insertPublishedReportPhoto(
      row: PublishedReportPhotosRow = PublishedReportPhotosRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      fileId: FileId = row.fileId ?: inserted.fileId,
      caption: String? = row.caption,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            fileId = fileId,
            caption = caption,
        )

    publishedReportPhotosDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedReportProjectMetric(
      row: PublishedReportProjectMetricsRow = PublishedReportProjectMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metricId: ProjectMetricId = row.projectMetricId ?: inserted.projectMetricId,
      target: Int? = row.target,
      value: Int? = row.value,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            projectMetricId = metricId,
            target = target,
            value = value,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
        )

    publishedReportProjectMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedReportStandardMetric(
      row: PublishedReportStandardMetricsRow = PublishedReportStandardMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metricId: StandardMetricId = row.standardMetricId ?: inserted.standardMetricId,
      target: Int? = row.target,
      value: Int? = row.value,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            standardMetricId = metricId,
            target = target,
            value = value,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
        )

    publishedReportStandardMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertPublishedReportSystemMetric(
      row: PublishedReportSystemMetricsRow = PublishedReportSystemMetricsRow(),
      reportId: ReportId = row.reportId ?: inserted.reportId,
      metric: SystemMetric = row.systemMetricId ?: SystemMetric.Seedlings,
      target: Int? = row.target,
      value: Int? = row.value,
      projectsComments: String? = row.projectsComments,
      progressNotes: String? = row.progressNotes,
      status: ReportMetricStatus? = row.statusId,
  ) {
    val rowWithDefaults =
        row.copy(
            reportId = reportId,
            systemMetricId = metric,
            target = target,
            value = value,
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
        )

    publishedReportSystemMetricsDao.insert(rowWithDefaults)
  }

  protected fun insertActivity(
      activityDate: LocalDate = LocalDate.EPOCH,
      activityType: ActivityType = ActivityType.Planting,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      description: String = "Activity",
      isHighlight: Boolean = false,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      projectId: ProjectId = inserted.projectId,
      verifiedBy: UserId? = null,
      verifiedTime: Instant? = if (verifiedBy != null) Instant.EPOCH else null,
      activityStatus: ActivityStatus =
          if (verifiedBy != null) ActivityStatus.Verified else ActivityStatus.NotVerified,
  ): ActivityId {
    val row =
        ActivitiesRow(
            activityDate = activityDate,
            activityStatusId = activityStatus,
            activityTypeId = activityType,
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            isHighlight = isHighlight,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            projectId = projectId,
            verifiedBy = verifiedBy,
            verifiedTime = verifiedTime,
        )

    activitiesDao.insert(row)

    return row.id!!.also { inserted.activityIds.add(it) }
  }

  private var lastActivityMediaFileActivityId: ActivityId? = null
  private var nextActivityMediaFileListPosition = 1

  protected fun insertActivityMediaFile(
      activityId: ActivityId = inserted.activityId,
      caption: String? = null,
      fileId: FileId = inserted.fileId,
      isCoverPhoto: Boolean = false,
      isHiddenOnMap: Boolean = false,
      listPosition: Int =
          if (activityId == lastActivityMediaFileActivityId) nextActivityMediaFileListPosition
          else 1,
      type: ActivityMediaType = ActivityMediaType.Photo,
  ) {
    val row =
        ActivityMediaFilesRow(
            activityId = activityId,
            activityMediaTypeId = type,
            caption = caption,
            fileId = fileId,
            isCoverPhoto = isCoverPhoto,
            isHiddenOnMap = isHiddenOnMap,
            listPosition = listPosition,
        )

    activityMediaFilesDao.insert(row)

    lastActivityMediaFileActivityId = activityId
    nextActivityMediaFileListPosition = listPosition + 1
  }

  protected fun insertPublishedActivity(
      activityId: ActivityId = inserted.activityId,
      activityDate: LocalDate = LocalDate.EPOCH,
      activityType: ActivityType = ActivityType.Planting,
      description: String = "Activity",
      isHighlight: Boolean = false,
      projectId: ProjectId = inserted.projectId,
      publishedBy: UserId = currentUser().userId,
      publishedTime: Instant = Instant.EPOCH,
  ) {
    val row =
        PublishedActivitiesRow(
            activityDate = activityDate,
            activityId = activityId,
            activityTypeId = activityType,
            description = description,
            isHighlight = isHighlight,
            projectId = projectId,
            publishedBy = publishedBy,
            publishedTime = publishedTime,
        )

    publishedActivitiesDao.insert(row)
  }

  private var lastPublishedActivityMediaFileActivityId: ActivityId? = null
  private var nextPublishedActivityMediaFileListPosition = 1

  protected fun insertPublishedActivityMediaFile(
      activityId: ActivityId = inserted.activityId,
      caption: String? = null,
      fileId: FileId = inserted.fileId,
      isCoverPhoto: Boolean = false,
      isHiddenOnMap: Boolean = false,
      listPosition: Int =
          if (activityId == lastPublishedActivityMediaFileActivityId)
              nextPublishedActivityMediaFileListPosition
          else 1,
      type: ActivityMediaType = ActivityMediaType.Photo,
  ) {
    val row =
        PublishedActivityMediaFilesRow(
            activityId = activityId,
            activityMediaTypeId = type,
            caption = caption,
            fileId = fileId,
            isCoverPhoto = isCoverPhoto,
            isHiddenOnMap = isHiddenOnMap,
            listPosition = listPosition,
        )

    publishedActivityMediaFilesDao.insert(row)

    lastPublishedActivityMediaFileActivityId = activityId
    nextPublishedActivityMediaFileListPosition = listPosition + 1
  }

  private var nextInternalTagNumber = 1

  protected fun insertInternalTag(
      name: String = "Tag ${nextInternalTagNumber++}",
      description: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): InternalTagId {
    val row =
        InternalTagsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            isSystem = false,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
        )

    internalTagsDao.insert(row)

    return row.id!!.also { inserted.internalTagIds.add(it) }
  }

  protected fun insertOrganizationInternalTag(
      organizationId: OrganizationId = inserted.organizationId,
      tagId: InternalTagId =
          if (inserted.internalTagIds.isEmpty()) InternalTagIds.Reporter
          else inserted.internalTagId,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ) {
    organizationInternalTagsDao.insert(
        OrganizationInternalTagsRow(
            internalTagId = tagId,
            organizationId = organizationId,
            createdBy = createdBy,
            createdTime = createdTime,
        )
    )
  }

  fun insertApplication(
      boundary: Geometry? = null,
      countryCode: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      feedback: String? = null,
      internalComment: String? = null,
      internalName: String = "XXX",
      projectId: ProjectId = inserted.projectId,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      status: ApplicationStatus = ApplicationStatus.NotSubmitted,
  ): ApplicationId {
    val row =
        ApplicationsRow(
            applicationStatusId = status,
            boundary = boundary,
            countryCode = countryCode,
            createdBy = createdBy,
            createdTime = createdTime,
            feedback = feedback,
            internalComment = internalComment,
            internalName = internalName,
            projectId = projectId,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    applicationsDao.insert(row)

    return row.id!!.also { inserted.applicationIds.add(it) }
  }

  fun insertApplicationHistory(
      applicationId: ApplicationId = inserted.applicationId,
      boundary: Geometry? = null,
      feedback: String? = null,
      internalComment: String? = null,
      modifiedBy: UserId = currentUser().userId,
      modifiedTime: Instant = Instant.EPOCH,
      status: ApplicationStatus = ApplicationStatus.NotSubmitted,
  ): ApplicationHistoryId {
    val row =
        ApplicationHistoriesRow(
            applicationId = applicationId,
            applicationStatusId = status,
            boundary = boundary,
            feedback = feedback,
            internalComment = internalComment,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    applicationHistoriesDao.insert(row)

    return row.id!!.also { inserted.applicationHistoryIds.add(it) }
  }

  fun insertApplicationModule(
      applicationId: ApplicationId = inserted.applicationId,
      moduleId: ModuleId = inserted.moduleId,
      status: ApplicationModuleStatus = ApplicationModuleStatus.Incomplete,
  ) {
    val row =
        ApplicationModulesRow(
            applicationId = applicationId,
            applicationModuleStatusId = status,
            moduleId = moduleId,
        )

    applicationModulesDao.insert(row)
  }

  fun insertParticipant(
      name: String = "Participant ${UUID.randomUUID()}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      cohortId: CohortId? = null,
  ): ParticipantId {
    val row =
        ParticipantsRow(
            cohortId = cohortId,
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
        )

    participantsDao.insert(row)

    return row.id!!.also { inserted.participantIds.add(it) }
  }

  fun insertParticipantProjectSpecies(
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      feedback: String? = null,
      internalComment: String? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = Instant.EPOCH,
      projectId: ProjectId = inserted.projectId,
      rationale: String? = null,
      speciesId: SpeciesId = inserted.speciesId,
      speciesNativeCategory: SpeciesNativeCategory? = null,
      submissionStatus: SubmissionStatus = SubmissionStatus.NotSubmitted,
  ): ParticipantProjectSpeciesId {
    val row =
        ParticipantProjectSpeciesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            feedback = feedback,
            internalComment = internalComment,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            projectId = projectId,
            rationale = rationale,
            speciesId = speciesId,
            speciesNativeCategoryId = speciesNativeCategory,
            submissionStatusId = submissionStatus,
        )

    participantProjectSpeciesDao.insert(row)

    return row.id!!.also { inserted.participantProjectSpeciesIds.add(it) }
  }

  fun insertCohort(
      name: String = "Cohort ${UUID.randomUUID()}",
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
  ): CohortId {
    val row =
        CohortsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            phaseId = phase,
        )

    cohortsDao.insert(row)

    return row.id!!.also { inserted.cohortIds.add(it) }
  }

  private var nextCohortModuleStartDate = LocalDate.EPOCH

  fun insertCohortModule(
      cohortId: CohortId = inserted.cohortId,
      moduleId: ModuleId = inserted.moduleId,
      title: String = "Module 1",
      startDate: LocalDate = nextCohortModuleStartDate,
      endDate: LocalDate = startDate.plusDays(6),
  ) {
    val row =
        CohortModulesRow(
            cohortId = cohortId,
            moduleId = moduleId,
            title = title,
            startDate = startDate,
            endDate = endDate,
        )

    nextCohortModuleStartDate = endDate.plusDays(1)
    cohortModulesDao.insert(row)
  }

  fun insertEvent(
      moduleId: ModuleId = inserted.moduleId,
      eventStatus: EventStatus = EventStatus.NotStarted,
      eventType: EventType = EventType.Workshop,
      meetingUrl: Any? = null,
      slidesUrl: Any? = null,
      recordingUrl: Any? = null,
      revision: Int = 1,
      startTime: Instant = Instant.EPOCH.plusSeconds(3600),
      endTime: Instant = startTime.plusSeconds(3600),
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): EventId {
    val row =
        EventsRow(
            moduleId = moduleId,
            eventStatusId = eventStatus,
            eventTypeId = eventType,
            meetingUrl = meetingUrl?.let { URI("$it") },
            slidesUrl = slidesUrl?.let { URI("$it") },
            recordingUrl = recordingUrl?.let { URI("$it") },
            revision = revision,
            startTime = startTime,
            endTime = endTime,
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
        )
    eventsDao.insert(row)

    return row.id!!.also { inserted.eventIds.add(it) }
  }

  fun insertEventProject(
      eventId: EventId = inserted.eventId,
      projectId: ProjectId = inserted.projectId,
  ) {
    val row =
        EventProjectsRow(
            eventId = eventId,
            projectId = projectId,
        )
    eventProjectsDao.insert(row)
  }

  fun insertVote(
      projectId: ProjectId = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      user: UserId = currentUser().userId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): ProjectVotesRow {
    val row =
        ProjectVotesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            projectId = projectId,
            phaseId = phase,
            userId = user,
            voteOptionId = voteOption,
            conditionalInfo = conditionalInfo,
        )

    projectVotesDao.insert(row)

    return row
  }

  fun insertVoteDecision(
      projectId: ProjectId = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      voteOption: VoteOption? = null,
      modifiedTime: Instant = Instant.EPOCH,
  ): ProjectVoteDecisionsRow {
    val row =
        ProjectVoteDecisionsRow(
            projectId = projectId,
            phaseId = phase,
            modifiedTime = modifiedTime,
            voteOptionId = voteOption,
        )

    projectVoteDecisionDao.insert(row)

    return row
  }

  private var nextDocumentSuffix = 0

  protected fun insertDocument(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
      documentTemplateId: DocumentTemplateId = inserted.documentTemplateId,
      internalComment: String? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "Document ${nextDocumentSuffix++}",
      ownedBy: UserId = createdBy,
      projectId: ProjectId = inserted.projectId,
      status: DocumentStatus = DocumentStatus.Draft,
      variableManifestId: VariableManifestId = inserted.variableManifestId,
  ): DocumentId {
    val row =
        DocumentsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            documentTemplateId = documentTemplateId,
            internalComment = internalComment,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            ownedBy = ownedBy,
            projectId = projectId,
            statusId = status,
            variableManifestId = variableManifestId,
        )

    documentsDao.insert(row)

    return row.id!!.also { inserted.documentIds.add(it) }
  }

  protected fun insertImageValue(
      variableId: VariableId,
      fileId: FileId,
      listPosition: Int = 0,
      caption: String? = null,
      id: VariableValueId =
          insertValue(
              listPosition = listPosition,
              variableId = variableId,
              type = VariableType.Image,
          ),
  ): VariableValueId {
    val row =
        VariableImageValuesRow(
            caption = caption,
            fileId = fileId,
            variableId = variableId,
            variableTypeId = VariableType.Image,
            variableValueId = id,
        )

    variableImageValuesDao.insert(row)

    return row.variableValueId!!
  }

  protected fun insertLinkValue(
      variableId: VariableId,
      listPosition: Int = 0,
      id: VariableValueId =
          insertValue(
              listPosition = listPosition,
              variableId = variableId,
              type = VariableType.Link,
          ),
      url: String,
      title: String? = null,
  ): VariableValueId {
    val row = VariableLinkValuesRow(id, variableId, VariableType.Link, url, title)

    variableLinkValuesDao.insert(row)

    return row.variableValueId!!
  }

  private var nextDocumentTemplate = 0

  protected fun insertDocumentTemplate(
      name: String = "Document Template ${nextDocumentTemplate++}",
  ): DocumentTemplateId {
    val row = DocumentTemplatesRow(name = name)

    documentTemplatesDao.insert(row)

    return row.id!!.also { inserted.documentTemplateIds.add(it) }
  }

  protected fun insertNumberVariable(
      id: VariableId? = null,
      decimalPlaces: Int? = null,
      deliverableId: DeliverableId? = null,
      minValue: BigDecimal? = null,
      maxValue: BigDecimal? = null,
      stableId: String = "$nextVariableNumber",
  ): VariableId {
    val actualId =
        id
            ?: insertVariable(
                type = VariableType.Number,
                deliverableId = deliverableId,
                stableId = stableId,
            )

    val row =
        VariableNumbersRow(
            variableId = actualId,
            variableTypeId = VariableType.Number,
            decimalPlaces = decimalPlaces,
            maxValue = maxValue,
            minValue = minValue,
        )

    variableNumbersDao.insert(row)

    return actualId
  }

  protected fun insertSavedVersion(
      maxValueId: VariableValueId,
      documentId: DocumentId = inserted.documentId,
      name: String = "Saved",
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
      isSubmitted: Boolean = false,
      variableManifestId: VariableManifestId = inserted.variableManifestId,
  ): DocumentSavedVersionId {
    val row =
        DocumentSavedVersionsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            documentId = documentId,
            isSubmitted = isSubmitted,
            maxVariableValueId = maxValueId,
            name = name,
            variableManifestId = variableManifestId,
        )

    documentSavedVersionsDao.insert(row)

    return row.id!!
  }

  protected fun insertSectionValue(
      variableId: VariableId,
      listPosition: Int = 0,
      projectId: ProjectId = inserted.projectId,
      id: VariableValueId =
          insertValue(
              variableId = variableId,
              listPosition = listPosition,
              type = VariableType.Section,
              projectId = projectId,
          ),
      textValue: String? = null,
      usedVariableId: VariableId? = null,
      usageType: VariableUsageType? =
          if (usedVariableId != null) VariableUsageType.Injection else null,
      displayStyle: VariableInjectionDisplayStyle? =
          if (usedVariableId != null) VariableInjectionDisplayStyle.Block else null,
  ): VariableValueId {
    val usedVariableType = usedVariableId?.let { variablesDao.fetchOneById(it)!!.variableTypeId!! }

    val row =
        VariableSectionValuesRow(
            displayStyleId = displayStyle,
            textValue = textValue,
            usageTypeId = usageType,
            usedVariableId = usedVariableId,
            usedVariableTypeId = usedVariableType,
            variableId = variableId,
            variableTypeId = VariableType.Section,
            variableValueId = id,
        )

    variableSectionValuesDao.insert(row)

    return row.variableValueId!!
  }

  protected fun insertDefaultSectionValue(
      variableId: VariableId,
      variableManifestId: VariableManifestId = inserted.variableManifestId,
      listPosition: Int = 0,
      textValue: String? = null,
      usedVariableId: VariableId? = null,
      usageType: VariableUsageType? =
          if (usedVariableId != null) VariableUsageType.Injection else null,
      displayStyle: VariableInjectionDisplayStyle? =
          if (usedVariableId != null) VariableInjectionDisplayStyle.Inline else null,
  ): VariableSectionDefaultValueId {
    val usedVariableType = usedVariableId?.let { variablesDao.fetchOneById(it)!!.variableTypeId!! }

    val row =
        VariableSectionDefaultValuesRow(
            displayStyleId = displayStyle,
            listPosition = listPosition,
            textValue = textValue,
            usageTypeId = usageType,
            usedVariableId = usedVariableId,
            usedVariableTypeId = usedVariableType,
            variableId = variableId,
            variableManifestId = variableManifestId,
            variableTypeId = VariableType.Section,
        )

    variableSectionDefaultValuesDao.insert(row)

    return row.id!!
  }

  protected fun insertSectionRecommendation(
      sectionId: VariableId,
      recommendedId: VariableId,
      manifestId: VariableManifestId = inserted.variableManifestId,
  ) {
    val row =
        VariableSectionRecommendationsRow(
            recommendedVariableId = recommendedId,
            sectionVariableId = sectionId,
            sectionVariableTypeId = VariableType.Section,
            variableManifestId = manifestId,
        )

    variableSectionRecommendationsDao.insert(row)
  }

  protected fun insertSectionVariable(
      id: VariableId = insertVariable(type = VariableType.Section),
      parentId: VariableId? = null,
      renderHeading: Boolean = true,
  ): VariableId {
    val row =
        VariableSectionsRow(
            variableId = id,
            variableTypeId = VariableType.Section,
            parentVariableId = parentId,
            parentVariableTypeId = if (parentId != null) VariableType.Section else null,
            renderHeading = renderHeading,
        )

    variableSectionsDao.insert(row)

    return id
  }

  private var nextSelectOptionPosition = 0

  protected fun insertSelectOption(
      variableId: VariableId,
      name: String,
      position: Int = nextSelectOptionPosition++,
      description: String? = null,
      renderedText: String? = null,
  ): VariableSelectOptionId {
    val row =
        VariableSelectOptionsRow(
            variableId = variableId,
            variableTypeId = VariableType.Select,
            position = position,
            name = name,
            renderedText = renderedText,
            description = description,
        )

    variableSelectOptionsDao.insert(row)

    return row.id!!
  }

  protected fun insertSelectValue(
      variableId: VariableId,
      listPosition: Int = 0,
      id: VariableValueId =
          insertValue(
              variableId = variableId,
              type = VariableType.Select,
          ),
      optionIds: Set<VariableSelectOptionId>,
  ): VariableValueId {
    optionIds.forEach { optionId ->
      val row =
          VariableSelectOptionValuesRow(
              variableId = variableId,
              variableTypeId = VariableType.Select,
              optionId = optionId,
              variableValueId = id,
          )

      variableSelectOptionValuesDao.insert(row)
    }

    return id
  }

  protected fun insertSelectVariable(
      id: VariableId? = null,
      deliverableId: DeliverableId? = null,
      isMultiple: Boolean = false,
  ): VariableId {
    val actualId = id ?: insertVariable(type = VariableType.Select, deliverableId = deliverableId)

    val row =
        VariableSelectsRow(
            variableId = actualId,
            variableTypeId = VariableType.Select,
            isMultiple = isMultiple,
        )

    variableSelectsDao.insert(row)

    return actualId
  }

  private var lastTableColumnTableId: VariableId? = null
  private var lastTableColumnPosition = 0

  protected fun insertTableColumn(
      tableId: VariableId,
      columnId: VariableId,
      isHeader: Boolean = false,
      position: Int? = null,
  ): VariableId {
    val effectivePosition =
        when {
          position != null -> position
          lastTableColumnTableId == tableId -> lastTableColumnPosition + 1
          else -> 1
        }

    lastTableColumnTableId = tableId
    lastTableColumnPosition = effectivePosition

    val row =
        VariableTableColumnsRow(
            isHeader = isHeader,
            position = effectivePosition,
            tableVariableId = tableId,
            tableVariableTypeId = VariableType.Table,
            variableId = columnId,
        )

    variableTableColumnsDao.insert(row)

    return row.variableId!!
  }

  protected fun insertTableVariable(
      id: VariableId? = null,
      deliverableId: DeliverableId? = null,
      style: VariableTableStyle = VariableTableStyle.Horizontal,
      stableId: String = "$nextVariableNumber",
  ): VariableId {
    val actualId =
        id
            ?: insertVariable(
                type = VariableType.Table,
                deliverableId = deliverableId,
                stableId = stableId,
                isList = true,
            )

    val row =
        VariableTablesRow(
            variableId = actualId,
            variableTypeId = VariableType.Table,
            tableStyleId = style,
        )

    variableTablesDao.insert(row)

    return actualId
  }

  protected fun insertTextVariable(
      id: VariableId? = null,
      deliverableId: DeliverableId? = null,
      textType: VariableTextType = VariableTextType.SingleLine,
      stableId: String = "$nextVariableNumber",
  ): VariableId {
    val actualId =
        id
            ?: insertVariable(
                type = VariableType.Text,
                deliverableId = deliverableId,
                stableId = stableId,
            )

    val row =
        VariableTextsRow(
            variableId = actualId,
            variableTypeId = VariableType.Text,
            variableTextTypeId = textType,
        )

    variableTextsDao.insert(row)

    return actualId
  }

  protected fun insertThumbnail(
      contentType: String = MediaType.IMAGE_JPEG_VALUE,
      createdTime: Instant = Instant.EPOCH,
      fileId: FileId,
      width: Int = 320,
      height: Int = 240,
      size: Number = 1,
      storageUrl: Any = "https://thumb",
  ): ThumbnailId {
    val row =
        ThumbnailsRow(
            contentType = contentType,
            createdTime = createdTime,
            fileId = fileId,
            height = height,
            size = size.toInt(),
            storageUrl = URI("$storageUrl"),
            width = width,
        )

    thumbnailsDao.insert(row)

    return row.id!!
  }

  protected fun insertValue(
      variableId: VariableId,
      listPosition: Int = 0,
      projectId: ProjectId = inserted.projectId,
      isDeleted: Boolean = false,
      textValue: String? = null,
      numberValue: BigDecimal? = null,
      dateValue: LocalDate? = null,
      type: VariableType? = null,
      citation: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
  ): VariableValueId {
    val actualType = type ?: variablesDao.fetchOneById(variableId)!!.variableTypeId!!

    val row =
        VariableValuesRow(
            citation = citation,
            projectId = projectId,
            variableId = variableId,
            variableTypeId = actualType,
            listPosition = listPosition,
            createdBy = createdBy,
            createdTime = createdTime,
            numberValue = numberValue,
            textValue = textValue,
            dateValue = dateValue,
            isDeleted = isDeleted,
        )

    variableValuesDao.insert(row)

    return row.id!!.also { inserted.variableValueIds.add(it) }
  }

  protected fun insertValueTableRow(
      valueId: VariableValueId,
      rowValueId: VariableValueId,
  ) {
    val row =
        VariableValueTableRowsRow(
            variableValueId = valueId,
            tableRowValueId = rowValueId,
        )

    variableValueTableRowsDao.insert(row)
  }

  private var nextVariableNumber = 1

  protected fun insertVariable(
      deliverableId: DeliverableId? = null,
      deliverablePosition: Int? = if (deliverableId != null) nextDeliverablePosition++ else null,
      deliverableQuestion: String? = null,
      dependencyCondition: DependencyCondition? = null,
      dependencyValue: String? = null,
      dependencyVariableStableId: String? = null,
      description: String? = null,
      internalOnly: Boolean = false,
      isList: Boolean = false,
      isRequired: Boolean = false,
      name: String = "Variable $nextVariableNumber",
      replacesVariableId: VariableId? = null,
      stableId: String = "$nextVariableNumber",
      type: VariableType = VariableType.Text,
  ): VariableId {
    nextVariableNumber++

    val row =
        VariablesRow(
            deliverableQuestion = deliverableQuestion,
            dependencyConditionId = dependencyCondition,
            dependencyValue = dependencyValue,
            dependencyVariableStableId = dependencyVariableStableId?.let { StableId(it) },
            description = description,
            internalOnly = internalOnly,
            isList = isList,
            isRequired = isRequired,
            name = name,
            replacesVariableId = replacesVariableId,
            stableId = StableId(stableId),
            variableTypeId = type,
        )

    variablesDao.insert(row)

    val variableId = row.id!!
    inserted.variableIds.add(variableId)

    if (deliverableId != null && deliverablePosition != null) {
      insertDeliverableVariable(
          deliverableId = deliverableId,
          variableId = variableId,
          position = deliverablePosition,
      )
    }

    return variableId
  }

  protected fun insertVariableManifest(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = Instant.EPOCH,
      documentTemplateId: DocumentTemplateId = inserted.documentTemplateId,
  ): VariableManifestId {
    val row =
        VariableManifestsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            documentTemplateId = documentTemplateId,
        )

    variableManifestsDao.insert(row)

    return row.id!!.also { inserted.variableManifestIds.add(it) }
  }

  private var nextManifestPosition = 0

  protected fun insertVariableManifestEntry(
      variableId: VariableId,
      manifestId: VariableManifestId = inserted.variableManifestId,
      position: Int = nextManifestPosition++,
  ): VariableId {
    val row =
        VariableManifestEntriesRow(
            position = position,
            variableId = variableId,
            variableManifestId = manifestId,
        )

    variableManifestEntriesDao.insert(row)

    return variableId
  }

  protected fun insertVariableOwner(
      variableId: VariableId = inserted.variableId,
      ownedBy: UserId,
      projectId: ProjectId = inserted.projectId,
  ) {
    val row =
        VariableOwnersRow(
            ownedBy = ownedBy,
            projectId = projectId,
            variableId = variableId,
        )

    variableOwnersDao.insert(row)
  }

  protected fun insertVariableWorkflowHistory(
      projectId: ProjectId = inserted.projectId,
      variableId: VariableId = inserted.variableId,
      feedback: String? = null,
      internalComment: String? = null,
      status: VariableWorkflowStatus = VariableWorkflowStatus.NotSubmitted,
      maxVariableValueId: VariableValueId = inserted.variableValueId,
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
  ): VariableWorkflowHistoryId {
    val row =
        VariableWorkflowHistoryRow(
            createdBy = createdBy,
            createdTime = createdTime,
            feedback = feedback,
            internalComment = internalComment,
            maxVariableValueId = maxVariableValueId,
            projectId = projectId,
            variableId = variableId,
            variableWorkflowStatusId = status,
        )

    variableWorkflowHistoryDao.insert(row)

    return row.id!!.also { inserted.variableWorkflowHistoryIds.add(it) }
  }

  private fun insertStableVariable(stableId: StableId, type: VariableType): VariableId {
    return when (type) {
      VariableType.Number ->
          insertNumberVariable(
              insertVariable(type = VariableType.Number, stableId = stableId.value)
          )
      VariableType.Text ->
          insertTextVariable(insertVariable(type = VariableType.Text, stableId = stableId.value))
      VariableType.Select ->
          insertSelectVariable(
              insertVariable(type = VariableType.Select, stableId = stableId.value)
          )
      VariableType.Table ->
          insertTableVariable(insertVariable(type = VariableType.Table, stableId = stableId.value))
      else -> insertVariable(type = type, stableId = stableId.value)
    }
  }

  protected fun insertFundingEntity(
      name: String = "TestFundingEntity ${UUID.randomUUID()}",
      createdBy: UserId = currentUser().userId,
      createdTime: Instant = Instant.EPOCH,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
  ): FundingEntityId {
    val row =
        FundingEntitiesRow(
            name = name,
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    fundingEntitiesDao.insert(row)

    return row.id!!.also { inserted.fundingEntityIds.add(it) }
  }

  protected fun insertFundingEntityProject(
      fundingEntityId: FundingEntityId = inserted.fundingEntityId,
      projectId: ProjectId = inserted.projectId,
  ) {
    fundingEntityProjectsDao.insert(
        FundingEntityProjectsRow(fundingEntityId = fundingEntityId, projectId = projectId)
    )
  }

  protected fun insertFundingEntityUser(
      fundingEntityId: FundingEntityId = inserted.fundingEntityId,
      userId: UserId = inserted.userId,
  ) {
    fundingEntityUsersDao.insert(
        FundingEntityUsersRow(fundingEntityId = fundingEntityId, userId = userId)
    )
  }

  protected fun setupStableIdVariables(): Map<StableId, VariableId> {
    val stableIds: Map<StableId, VariableType> =
        with(StableIds) {
          mapOf(
              accumulationRate to VariableType.Number,
              agroforestryLandUseModelHectare to VariableType.Number,
              annualCarbon to VariableType.Number,
              applicationRestorableLand to VariableType.Number,
              carbonCertifications to VariableType.Select,
              carbonCapacity to VariableType.Number,
              clickUpLink to VariableType.Link,
              contactEmail to VariableType.Text,
              contactName to VariableType.Text,
              country to VariableType.Select,
              dealDescription to VariableType.Text,
              dealName to VariableType.Text,
              failureRisk to VariableType.Text,
              gisReportsLink to VariableType.Link,
              investmentThesis to VariableType.Text,
              landUseModelType to VariableType.Select,
              mangrovesLandUseModelHectare to VariableType.Number,
              maxCarbonAccumulation to VariableType.Number,
              methodologyNumber to VariableType.Select,
              minCarbonAccumulation to VariableType.Number,
              minProjectArea to VariableType.Number,
              monocultureLandUseHectare to VariableType.Number,
              nativeForestLandUseHectare to VariableType.Number,
              numSpecies to VariableType.Number,
              otherLandUseModelHectare to VariableType.Number,
              otherTimberLandUseHectare to VariableType.Number,
              perHectareEstimatedBudget to VariableType.Number,
              projectArea to VariableType.Number,
              projectHighlightPhoto to VariableType.Image,
              projectZoneFigure to VariableType.Image,
              projectType to VariableType.Select,
              riskTrackerLink to VariableType.Link,
              sdgList to VariableType.Select,
              silvopastureLandUseModelHectare to VariableType.Number,
              slackLink to VariableType.Link,
              standard to VariableType.Select,
              sustainableTimberLandUseHectare to VariableType.Number,
              tfRestorableLand to VariableType.Number,
              totalCarbon to VariableType.Number,
              totalExpansionPotential to VariableType.Number,
              totalVCU to VariableType.Number,
              verraLink to VariableType.Link,
              website to VariableType.Text,
              whatNeedsToBeTrue to VariableType.Text,
          )
        }

    return stableIds.mapValues { (stableId, type) -> insertStableVariable(stableId, type) }
  }

  protected fun clearCachedPermissions(updatedUserId: UserId = currentUser().userId) {
    if (updatedUserId == CurrentUserHolder.getCurrentUser()?.userId && this is RunsAsDatabaseUser) {
      user.clearCachedPermissions()
    }
  }

  /**
   * Asserts that a table (or a filtered subset of it) contains an expected set of records and
   * nothing else.
   *
   * You will usually want one of the other variants of this that require fewer parameters.
   *
   * @param table The table whose contents should be examined.
   * @param expected The records the table should contain. If the records' primary key fields are
   *   null, the primary keys of the table records will be ignored.
   * @param message Assertion failure message; defaults to the table name.
   * @param where Optional query condition to assert on a subset of a table's contents. Only rows
   *   matching the condition will be considered.
   * @param transform Function to apply to records from the database before comparing them to
   *   [expected]. Can be used to clear or hardwire specific fields that aren't relevant to the
   *   behavior being tested.
   */
  protected fun <R : TableRecord<R>> assertTableEquals(
      table: Table<R>,
      expected: Collection<R>,
      message: String = table.name,
      where: Condition? = null,
      transform: ((R) -> R)? = null,
  ) {
    val sampleExpected = expected.firstOrNull()

    // Wrap the expected and actual records in a jOOQ Result, which formats them in a nicer-looking
    // style than the default Collection.toString() formatting. This makes the assertion failure
    // messages easier to read.
    val expectedResult = dslContext.newResult(table)
    expectedResult.addAll(expected.sorted())

    // If the expected records have primary key values, then include primary keys in the records
    // that get pulled from the database. This is not the Kotlin data class copy(), but the one from
    // UpdatableRecord, which clears primary key fields.
    val includePrimaryKeys = sampleExpected != (sampleExpected as? UpdatableRecord<*>)?.copy()

    val actualResult = dslContext.newResult(table)
    actualResult.addAll(
        dslContext
            .selectFrom(table)
            .where(where)
            .fetch()
            .map { record: R ->
              val transformed = transform?.invoke(record) ?: record
              if (includePrimaryKeys || transformed !is UpdatableRecord<*>) {
                transformed
              } else {
                // This is UpdatableRecord.copy(). which clears primary key fields.
                @Suppress("UNCHECKED_CAST")
                transformed.copy() as R
              }
            }
            .sorted()
    )

    assertEquals(expectedResult, actualResult, message)
  }

  /**
   * Asserts that a table (or a filtered subset of it) contains an expected set of records and
   * nothing else.
   *
   * @param expected The records the table should contain. Must be non-empty. If the records'
   *   primary key fields are null, the primary keys of the table records will be ignored.
   * @param message Assertion failure message; defaults to the table name.
   * @param where Optional query condition to assert on a subset of a table's contents. Only rows
   *   matching the condition will be considered.
   * @param transform Function to apply to records from the database before comparing them to
   *   [expected]. Can be used to clear or hardwire specific fields that aren't relevant to the
   *   behavior being tested.
   */
  protected fun <R : TableRecord<R>> assertTableEquals(
      expected: Collection<R>,
      message: String? = null,
      where: Condition? = null,
      transform: ((R) -> R)? = null,
  ) {
    val table =
        expected.firstOrNull()?.table
            ?: throw IllegalArgumentException("Use assertTableEmpty to check for empty tables")

    assertTableEquals(
        table = table,
        expected = expected,
        message = message ?: table.name,
        where = where,
        transform = transform,
    )
  }

  /**
   * Asserts that a table (or a filtered subset of it) contains exactly one row.
   *
   * @param expected The single record that the table should contain. If its primary key field(s)
   *   are null, the primary keys of the table records will be ignored.
   * @param message Assertion failure message; defaults to the table name.
   * @param where Optional query condition to assert on a subset of a table's contents. Only rows
   *   matching the condition will be considered.
   * @param transform Function to apply to records from the database before comparing them to
   *   [expected]. Can be used to clear or hardwire specific fields that aren't relevant to the
   *   behavior being tested.
   */
  protected fun <R : TableRecord<R>> assertTableEquals(
      expected: R,
      message: String? = null,
      where: Condition? = null,
      transform: ((R) -> R)? = null,
  ) {
    assertTableEquals(
        expected = listOf(expected),
        message = message,
        where = where,
        transform = transform,
    )
  }

  /**
   * Asserts that a table (or a filtered subset of it) contains no rows.
   *
   * @param table The table whose contents should be examined.
   * @param message Assertion failure message; defaults to the table name.
   * @param where Optional query condition to assert on a subset of a table's contents. Only rows
   *   matching the condition will be considered.
   */
  protected fun <R : TableRecord<R>> assertTableEmpty(
      table: Table<R>,
      message: String = table.name,
      where: Condition? = null,
  ) {
    assertTableEquals(table = table, expected = emptySet(), message = message, where = where)
  }

  @Suppress("unused")
  class Inserted {
    val accessionIds = mutableListOf<AccessionId>()
    val activityIds = mutableListOf<ActivityId>()
    val applicationHistoryIds = mutableListOf<ApplicationHistoryId>()
    val applicationIds = mutableListOf<ApplicationId>()
    val automationIds = mutableListOf<AutomationId>()
    val bagsIds = mutableListOf<BagId>()
    val batchIds = mutableListOf<BatchId>()
    val biomassSpeciesIds = mutableListOf<BiomassSpeciesId>()
    val cohortIds = mutableListOf<CohortId>()
    val deliverableIds = mutableListOf<DeliverableId>()
    val deliveryIds = mutableListOf<DeliveryId>()
    val deviceIds = mutableListOf<DeviceId>()
    val disclaimerIds = mutableListOf<DisclaimerId>()
    val documentIds = mutableListOf<DocumentId>()
    val documentTemplateIds = mutableListOf<DocumentTemplateId>()
    val draftPlantingSiteIds = mutableListOf<DraftPlantingSiteId>()
    val eventIds = mutableListOf<EventId>()
    val facilityIds = mutableListOf<FacilityId>()
    val fileIds = mutableListOf<FileId>()
    val fundingEntityIds = mutableListOf<FundingEntityId>()
    val internalTagIds = mutableListOf<InternalTagId>()
    val moduleIds = mutableListOf<ModuleId>()
    val monitoringPlotHistoryIds = mutableListOf<MonitoringPlotHistoryId>()
    val monitoringPlotIds = mutableListOf<MonitoringPlotId>()
    val notificationIds = mutableListOf<NotificationId>()
    val observationIds = mutableListOf<ObservationId>()
    val organizationIds = mutableListOf<OrganizationId>()
    val participantIds = mutableListOf<ParticipantId>()
    val participantProjectSpeciesIds = mutableListOf<ParticipantProjectSpeciesId>()
    val projectMetricIds = mutableListOf<ProjectMetricId>()
    val plantingIds = mutableListOf<PlantingId>()
    val plantingSeasonIds = mutableListOf<PlantingSeasonId>()
    val plantingSiteHistoryIds = mutableListOf<PlantingSiteHistoryId>()
    val plantingSiteIds = mutableListOf<PlantingSiteId>()
    val plantingSiteNotificationIds = mutableListOf<PlantingSiteNotificationId>()
    val plantingSubzoneHistoryIds = mutableListOf<PlantingSubzoneHistoryId>()
    val plantingSubzoneIds = mutableListOf<PlantingSubzoneId>()
    val plantingZoneHistoryIds = mutableListOf<PlantingZoneHistoryId>()
    val plantingZoneIds = mutableListOf<PlantingZoneId>()
    val projectIds = mutableListOf<ProjectId>()
    val projectReportConfigIds = mutableListOf<ProjectReportConfigId>()
    val recordedTreeIds = mutableListOf<RecordedTreeId>()
    val reportIds = mutableListOf<ReportId>()
    val seedbankWithdrawalIds = mutableListOf<SeedbankWithdrawalId>()
    val seedFundReportIds = mutableListOf<SeedFundReportId>()
    val speciesIds = mutableListOf<SpeciesId>()
    val standardMetricIds = mutableListOf<StandardMetricId>()
    val subLocationIds = mutableListOf<SubLocationId>()
    val submissionDocumentIds = mutableListOf<SubmissionDocumentId>()
    val submissionIds = mutableListOf<SubmissionId>()
    val submissionSnapshotIds = mutableListOf<SubmissionSnapshotId>()
    val timeseriesIds = mutableListOf<TimeseriesId>()
    val uploadIds = mutableListOf<UploadId>()
    val userIds = mutableListOf<UserId>()
    val variableIds = mutableListOf<VariableId>()
    val variableManifestIds = mutableListOf<VariableManifestId>()
    val variableValueIds = mutableListOf<VariableValueId>()
    val variableWorkflowHistoryIds = mutableListOf<VariableWorkflowHistoryId>()
    val viabilityTestIds = mutableListOf<ViabilityTestId>()
    val viabilityTestResultIds = mutableListOf<ViabilityTestResultId>()
    val withdrawalIds = mutableListOf<WithdrawalId>()

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

    val cohortId
      get() = cohortIds.last()

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

    val fileId
      get() = fileIds.last()

    val fundingEntityId
      get() = fundingEntityIds.last()

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

    val participantId
      get() = participantIds.last()

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

    val plantingSubzoneHistoryId
      get() = plantingSubzoneHistoryIds.last()

    val plantingSubzoneId
      get() = plantingSubzoneIds.last()

    val plantingZoneHistoryId
      get() = plantingZoneHistoryIds.last()

    val plantingZoneId
      get() = plantingZoneIds.last()

    val projectId
      get() = projectIds.last()

    val projectMetricId
      get() = projectMetricIds.last()

    val projectReportConfigId
      get() = projectReportConfigIds.last()

    val recordedTreeId
      get() = recordedTreeIds.last()

    val reportId
      get() = reportIds.last()

    val seedFundReportId
      get() = seedFundReportIds.last()

    val speciesId
      get() = speciesIds.last()

    val standardMetricId
      get() = standardMetricIds.last()

    val subLocationId
      get() = subLocationIds.last()

    val submissionId
      get() = submissionIds.last()

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

    val withdrawalId
      get() = withdrawalIds.last()
  }

  class DockerPostgresDataSourceInitializer :
      ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
      if (!started) {
        postgresContainer.start()
        started = true
      }

      // Run tests using the AWS JDBC wrapper since we'll be using the wrapper in production.
      // The wrapper requires an extra element in the JDBC URL.
      val awsWrapperJdbcUrl =
          postgresContainer.jdbcUrl.replace(Regex("^jdbc:"), "jdbc:aws-wrapper:")

      TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
          applicationContext,
          "spring.datasource.url=$awsWrapperJdbcUrl",
          "spring.datasource.username=${postgresContainer.username}",
          "spring.datasource.password=${postgresContainer.password}",
      )
    }
  }

  companion object {
    init {
      // Work around non-thread-safe initialization of jOOQ constants by evaluating one symbol
      // from the generated Keys.kt file for each schema.
      ACCESSION_PKEY
      BATCHES_PKEY
      COHORTS_PKEY
      PLANTING_SITES_PKEY
      USERS_PKEY
      VARIABLES_PKEY
    }

    /**
     * Balena IDs are required to be unique but aren't generated by our database (we get them from
     * Balena's API). We need to generate test IDs that are unique across all threads in case the
     * tests here run concurrently.
     */
    @JvmStatic protected val nextBalenaId = AtomicLong(1L)

    private val imageName: DockerImageName =
        DockerImageName.parse("$POSTGRES_DOCKER_REPOSITORY:$POSTGRES_DOCKER_TAG")
            .asCompatibleSubstituteFor("postgres")

    val postgresContainer: PostgreSQLContainer<*> =
        PostgreSQLContainer(imageName)
            .withDatabaseName("terraware")
            .withExposedPorts(PostgreSQLContainer.POSTGRESQL_PORT)
            .withNetwork(Network.newNetwork())
            .withNetworkAliases("postgres")
    var started: Boolean = false
  }
}
