package com.terraformation.backend.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.SpringShutdownListener
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.ActivityStatus
import com.terraformation.backend.db.accelerator.ActivityType
import com.terraformation.backend.db.accelerator.ApplicationHistoryId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.DealStage
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.EventStatus
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.InternalInterest
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.Pipeline
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.SubmissionSnapshotId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.daos.ActivitiesDao
import com.terraformation.backend.db.accelerator.tables.daos.ActivityMediaFilesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationHistoriesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ApplicationsDao
import com.terraformation.backend.db.accelerator.tables.daos.AutoCalculatedIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.CommonIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.CommonIndicatorsDao
import com.terraformation.backend.db.accelerator.tables.daos.DefaultVotersDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableProjectDueDatesDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverableVariablesDao
import com.terraformation.backend.db.accelerator.tables.daos.DeliverablesDao
import com.terraformation.backend.db.accelerator.tables.daos.EventProjectsDao
import com.terraformation.backend.db.accelerator.tables.daos.EventsDao
import com.terraformation.backend.db.accelerator.tables.daos.ModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ParticipantProjectSpeciesDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectAcceleratorDetailsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectIndicatorsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectModulesDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectOverallScoresDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectReportConfigsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectScoresDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVoteDecisionsDao
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportAchievementsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportAutoCalculatedIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportAutoCalculatedIndicatorsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportChallengesDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportCommonIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportCommonIndicatorsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportPhotosDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportProjectIndicatorTargetsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportProjectIndicatorsDao
import com.terraformation.backend.db.accelerator.tables.daos.ReportsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionDocumentsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionSnapshotsDao
import com.terraformation.backend.db.accelerator.tables.daos.SubmissionsDao
import com.terraformation.backend.db.accelerator.tables.daos.UserInternalInterestsDao
import com.terraformation.backend.db.accelerator.tables.pojos.ActivitiesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ActivityMediaFilesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationHistoriesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ApplicationsRow
import com.terraformation.backend.db.accelerator.tables.pojos.AutoCalculatedIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.CommonIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.CommonIndicatorsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableProjectDueDatesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverableVariablesRow
import com.terraformation.backend.db.accelerator.tables.pojos.DeliverablesRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventProjectsRow
import com.terraformation.backend.db.accelerator.tables.pojos.EventsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectAcceleratorDetailsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectIndicatorsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectModulesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectOverallScoresRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectReportConfigsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectScoresRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVoteDecisionsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportAchievementsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportAutoCalculatedIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportAutoCalculatedIndicatorsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportChallengesRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportCommonIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportCommonIndicatorsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportPhotosRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportProjectIndicatorTargetsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportProjectIndicatorsRow
import com.terraformation.backend.db.accelerator.tables.pojos.ReportsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionDocumentsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionSnapshotsRow
import com.terraformation.backend.db.accelerator.tables.pojos.SubmissionsRow
import com.terraformation.backend.db.accelerator.tables.pojos.UserInternalInterestsRow
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_OBSERVATIONS
import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.EmailNotificationCategory
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileBatchId
import com.terraformation.backend.db.default_schema.FileBatchStatus
import com.terraformation.backend.db.default_schema.FileBatchType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GbifTaxonId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.GriisResourceId
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
import com.terraformation.backend.db.default_schema.SavedTableViewId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.SplatAnnotationId
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
import com.terraformation.backend.db.default_schema.WcvpTaxonId
import com.terraformation.backend.db.default_schema.keys.USERS_PKEY
import com.terraformation.backend.db.default_schema.tables.daos.AutomationsDao
import com.terraformation.backend.db.default_schema.tables.daos.BirdnetResultsDao
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.CountrySubdivisionsDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.daos.DevicesDao
import com.terraformation.backend.db.default_schema.tables.daos.DisclaimersDao
import com.terraformation.backend.db.default_schema.tables.daos.FacilitiesDao
import com.terraformation.backend.db.default_schema.tables.daos.FileBatchesDao
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.IdentifierSequencesDao
import com.terraformation.backend.db.default_schema.tables.daos.InternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.NotificationsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationInternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationManagedLocationTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationMediaFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectInternalUsersDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectLandUseModelTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectReportSettingsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.daos.SavedTableViewsDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportFilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportPhotosDao
import com.terraformation.backend.db.default_schema.tables.daos.SeedFundReportsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesEcosystemTypesDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesGrowthFormsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesPlantMaterialSourcingMethodsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.SpeciesSuccessionalGroupsDao
import com.terraformation.backend.db.default_schema.tables.daos.SplatAnnotationsDao
import com.terraformation.backend.db.default_schema.tables.daos.SplatsDao
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeZonesDao
import com.terraformation.backend.db.default_schema.tables.daos.TimeseriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.daos.UserDisclaimersDao
import com.terraformation.backend.db.default_schema.tables.daos.UserEmailNotificationCategoriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UserGlobalRolesDao
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.pojos.BirdnetResultsRow
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.pojos.DisclaimersRow
import com.terraformation.backend.db.default_schema.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.default_schema.tables.pojos.FileBatchesRow
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectInternalUsersRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectLandUseModelTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectReportSettingsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.pojos.SavedTableViewsRow
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportsRow
import com.terraformation.backend.db.default_schema.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.default_schema.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserDisclaimersRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserEmailNotificationCategoriesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UserGlobalRolesRow
import com.terraformation.backend.db.default_schema.tables.records.BotanicalCountriesRecord
import com.terraformation.backend.db.default_schema.tables.records.CountryBotanicalCountriesRecord
import com.terraformation.backend.db.default_schema.tables.records.ExternalDatasetImportsRecord
import com.terraformation.backend.db.default_schema.tables.records.GriisResourcesRecord
import com.terraformation.backend.db.default_schema.tables.records.GriisTaxaRecord
import com.terraformation.backend.db.default_schema.tables.records.ProjectSpeciesRecord
import com.terraformation.backend.db.default_schema.tables.records.SpeciesProblemsRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpDistributionsRecord
import com.terraformation.backend.db.default_schema.tables.records.WcvpTaxaRecord
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_DISTRIBUTIONS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAMES
import com.terraformation.backend.db.default_schema.tables.references.GBIF_NAME_WORDS
import com.terraformation.backend.db.default_schema.tables.references.GBIF_TAXA
import com.terraformation.backend.db.default_schema.tables.references.GBIF_VERNACULAR_NAMES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_GROWTH_FORMS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PLANT_MATERIAL_SOURCING_METHODS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_SUCCESSIONAL_GROUPS
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ADDITIONAL_FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATION_MEDIA
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
import com.terraformation.backend.db.funder.tables.daos.PublishedActivityObservationMediaFilesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedActivityObservationsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedAutoCalculatedIndicatorBaselinesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedAutoCalculatedIndicatorTargetsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedCommonIndicatorBaselinesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedCommonIndicatorTargetsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectCarbonCertsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectDetailsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectIndicatorBaselinesDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectIndicatorTargetsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectLandUseDao
import com.terraformation.backend.db.funder.tables.daos.PublishedProjectSdgDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportAutoCalculatedIndicatorsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportCommonIndicatorsDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportPhotosDao
import com.terraformation.backend.db.funder.tables.daos.PublishedReportProjectIndicatorsDao
import com.terraformation.backend.db.funder.tables.pojos.FundingEntitiesRow
import com.terraformation.backend.db.funder.tables.pojos.FundingEntityProjectsRow
import com.terraformation.backend.db.funder.tables.pojos.FundingEntityUsersRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivitiesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivityMediaFilesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivityObservationMediaFilesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedActivityObservationsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedAutoCalculatedIndicatorBaselinesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedAutoCalculatedIndicatorTargetsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedCommonIndicatorBaselinesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedCommonIndicatorTargetsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectCarbonCertsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectDetailsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectIndicatorBaselinesRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectIndicatorTargetsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectLandUseRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedProjectSdgRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportAutoCalculatedIndicatorsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportCommonIndicatorsRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportPhotosRow
import com.terraformation.backend.db.funder.tables.pojos.PublishedReportProjectIndicatorsRow
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_CHALLENGES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.BatchSubstrate
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
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalPhotosRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.nursery.tables.records.BatchQuantityHistoryRecord
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
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
import com.terraformation.backend.db.seedbank.tables.records.AccessionCollectorsRecord
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.ObservedPlotCoordinatesId
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonNotificationPage
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSiteNotificationId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.RecordedPlantId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SoilType
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.keys.PLANTING_SITES_PKEY
import com.terraformation.backend.db.tracking.tables.daos.DeliveriesDao
import com.terraformation.backend.db.tracking.tables.daos.DraftPlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.DraftStrataDao
import com.terraformation.backend.db.tracking.tables.daos.DraftSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotOverlapsDao
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassDetailsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassQuadratDetailsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationBiomassSpeciesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationDependentSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationMediaFilesDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotConditionsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotResultsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationRequestedSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationSiteResultsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationStratumResultsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationSubstratumResultsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservedPlotCoordinatesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingDateRequestSpeciesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingDateRequestsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonAllocatedSpeciesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonNotificationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonSpeciesTargetsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSeasonsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteNotificationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitePopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSiteSpeciesTargetsDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.PlantingsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedPlantsDao
import com.terraformation.backend.db.tracking.tables.daos.RecordedTreesDao
import com.terraformation.backend.db.tracking.tables.daos.ScheduledPlantingDateSpeciesDao
import com.terraformation.backend.db.tracking.tables.daos.ScheduledPlantingDatesDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedPlantingSiteHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedPlantingSitesDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedStrataDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedStratumHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedSubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.SimplifiedSubstratumHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.StrataDao
import com.terraformation.backend.db.tracking.tables.daos.StratumHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.StratumPopulationsDao
import com.terraformation.backend.db.tracking.tables.daos.StratumSpeciesTargetsDao
import com.terraformation.backend.db.tracking.tables.daos.SubstrataDao
import com.terraformation.backend.db.tracking.tables.daos.SubstratumHistoriesDao
import com.terraformation.backend.db.tracking.tables.daos.SubstratumPopulationsDao
import com.terraformation.backend.db.tracking.tables.pojos.DeliveriesRow
import com.terraformation.backend.db.tracking.tables.pojos.DraftPlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.DraftStrataRow
import com.terraformation.backend.db.tracking.tables.pojos.DraftSubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotOverlapsRow
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassQuadratDetailsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationBiomassSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationDependentSubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationMediaFilesRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationRequestedSubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSiteResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationStratumResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSubstratumResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotCoordinatesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingDateRequestSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingDateRequestsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonAllocatedSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonNotificationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonSpeciesTargetsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteNotificationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteSpeciesTargetsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedTreesRow
import com.terraformation.backend.db.tracking.tables.pojos.ScheduledPlantingDateSpeciesRow
import com.terraformation.backend.db.tracking.tables.pojos.ScheduledPlantingDatesRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedPlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedPlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedStrataRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedStratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedSubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.SimplifiedSubstratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumSpeciesTargetsRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_ADDITIONAL_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.documentproducer.model.StableIds
import com.terraformation.backend.point
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.species.db.GbifImporterTest
import com.terraformation.backend.species.db.GbifStore
import com.terraformation.backend.splat.CoordinateModel
import com.terraformation.backend.splat.toMultiPointField
import com.terraformation.backend.splat.toPointField
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.util.removeDiacritics
import jakarta.ws.rs.NotFoundException
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestExecutionListeners
import org.springframework.test.context.transaction.InheritedTransactionRemover
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Superclass for tests that make use of a database. You will usually want to subclass
 * [DatabaseTest] or [ControllerIntegrationTest] instead of this.
 *
 * Base class for database-backed tests. Subclass this to get a fully-configured database with a
 * [DSLContext] and a set of jOOQ DAO objects ready to use. The database is run in a Docker
 * container, which cuts down on the chance that tests will behave differently from one development
 * environment to the next. On developer machines, the container is kept running between test runs
 * to avoid the cost of starting it and applying migrations each time; see [TestDatabaseContainer].
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
@ContextConfiguration(initializers = [TestDatabaseContainer.DataSourceInitializer::class])
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

  @Autowired
  @Suppress("SpringJavaInjectionPointsAutowiringInspection") // Spurious IntelliJ warning
  lateinit var transactionManager: PlatformTransactionManager

  /** IDs of entities that have been inserted using the `insert` helper methods during this test. */
  val inserted = InsertedDatabaseIds()

  /** Clock that's used to generate timestamps in the various insert methods. */
  open val clock = TestClock()

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
  protected val autoCalculatedIndicatorTargetsDao: AutoCalculatedIndicatorTargetsDao by lazyDao()
  protected val automationsDao: AutomationsDao by lazyDao()
  protected val bagsDao: BagsDao by lazyDao()
  protected val batchDetailsHistoryDao: BatchDetailsHistoryDao by lazyDao()
  protected val batchDetailsHistorySubLocationsDao: BatchDetailsHistorySubLocationsDao by lazyDao()
  protected val batchesDao: BatchesDao by lazyDao()
  protected val batchPhotosDao: BatchPhotosDao by lazyDao()
  protected val batchQuantityHistoryDao: BatchQuantityHistoryDao by lazyDao()
  protected val batchSubLocationsDao: BatchSubLocationsDao by lazyDao()
  protected val batchWithdrawalsDao: BatchWithdrawalsDao by lazyDao()
  protected val birdnetResultsDao: BirdnetResultsDao by lazyDao()
  protected val commonIndicatorsDao: CommonIndicatorsDao by lazyDao()
  protected val commonIndicatorTargetsDao: CommonIndicatorTargetsDao by lazyDao()
  protected val countriesDao: CountriesDao by lazyDao()
  protected val countrySubdivisionsDao: CountrySubdivisionsDao by lazyDao()
  protected val defaultVotersDao: DefaultVotersDao by lazyDao()
  protected val deliverableDocumentsDao: DeliverableDocumentsDao by lazyDao()
  protected val deliverableProjectDueDatesDao: DeliverableProjectDueDatesDao by lazyDao()
  protected val deliverablesDao: DeliverablesDao by lazyDao()
  protected val deliverableVariablesDao: DeliverableVariablesDao by lazyDao()
  protected val deliveriesDao: DeliveriesDao by lazyDao()
  protected val observationDependentSubstrataDao: ObservationDependentSubstrataDao by lazyDao()
  protected val deviceManagersDao: DeviceManagersDao by lazyDao()
  protected val devicesDao: DevicesDao by lazyDao()
  protected val deviceTemplatesDao: DeviceTemplatesDao by lazyDao()
  protected val disclaimersDao: DisclaimersDao by lazyDao()
  protected val documentSavedVersionsDao: DocumentSavedVersionsDao by lazyDao()
  protected val documentsDao: DocumentsDao by lazyDao()
  protected val draftPlantingSitesDao: DraftPlantingSitesDao by lazyDao()
  protected val draftStrataDao: DraftStrataDao by lazyDao()
  protected val draftSubstrataDao: DraftSubstrataDao by lazyDao()
  protected val eventProjectsDao: EventProjectsDao by lazyDao()
  protected val eventsDao: EventsDao by lazyDao()
  protected val facilitiesDao: FacilitiesDao by lazyDao()
  protected val fileBatchesDao: FileBatchesDao by lazyDao()
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
  protected val observationMediaFilesDao: ObservationMediaFilesDao by lazyDao()
  protected val observationPlotConditionsDao: ObservationPlotConditionsDao by lazyDao()
  protected val observationPlotResultsDao: ObservationPlotResultsDao by lazyDao()
  protected val observationPlotsDao: ObservationPlotsDao by lazyDao()
  protected val observationRequestedSubstrataDao: ObservationRequestedSubstrataDao by lazyDao()
  protected val observationsDao: ObservationsDao by lazyDao()
  protected val observationSiteResultsDao: ObservationSiteResultsDao by lazyDao()
  protected val observationStratumResultsDao: ObservationStratumResultsDao by lazyDao()
  protected val observationSubstratumResultsDao: ObservationSubstratumResultsDao by lazyDao()
  protected val observedPlotCoordinatesDao: ObservedPlotCoordinatesDao by lazyDao()
  protected val organizationInternalTagsDao: OrganizationInternalTagsDao by lazyDao()
  protected val organizationManagedLocationTypesDao: OrganizationManagedLocationTypesDao by
      lazyDao()
  protected val organizationMediaFilesDao: OrganizationMediaFilesDao by lazyDao()
  protected val organizationReportSettingsDao: OrganizationReportSettingsDao by lazyDao()
  protected val organizationsDao: OrganizationsDao by lazyDao()
  protected val organizationUsersDao: OrganizationUsersDao by lazyDao()
  protected val participantProjectSpeciesDao: ParticipantProjectSpeciesDao by lazyDao()
  protected val plantingDateRequestsDao: PlantingDateRequestsDao by lazyDao()
  protected val plantingDateRequestSpeciesDao: PlantingDateRequestSpeciesDao by lazyDao()
  protected val plantingsDao: PlantingsDao by lazyDao()
  protected val plantingSeasonsDao: PlantingSeasonsDao by lazyDao()
  protected val plantingSeasonAllocatedSpeciesDao: PlantingSeasonAllocatedSpeciesDao by lazyDao()
  protected val plantingSeasonNotificationsDao: PlantingSeasonNotificationsDao by lazyDao()
  protected val plantingSeasonSpeciesTargetsDao: PlantingSeasonSpeciesTargetsDao by lazyDao()
  protected val plantingSiteHistoriesDao: PlantingSiteHistoriesDao by lazyDao()
  protected val plantingSiteNotificationsDao: PlantingSiteNotificationsDao by lazyDao()
  protected val plantingSitePopulationsDao: PlantingSitePopulationsDao by lazyDao()
  protected val plantingSiteSpeciesTargetsDao: PlantingSiteSpeciesTargetsDao by lazyDao()
  protected val plantingSitesDao: PlantingSitesDao by lazyDao()
  protected val projectAcceleratorDetailsDao: ProjectAcceleratorDetailsDao by lazyDao()
  protected val projectIndicatorsDao: ProjectIndicatorsDao by lazyDao()
  protected val projectIndicatorTargetsDao: ProjectIndicatorTargetsDao by lazyDao()
  protected val projectInternalUsersDao: ProjectInternalUsersDao by lazyDao()
  protected val projectLandUseModelTypesDao: ProjectLandUseModelTypesDao by lazyDao()
  protected val projectModulesDao: ProjectModulesDao by lazyDao()
  protected val projectOverallScoresDao: ProjectOverallScoresDao by lazyDao()
  protected val projectReportConfigsDao: ProjectReportConfigsDao by lazyDao()
  protected val projectReportSettingsDao: ProjectReportSettingsDao by lazyDao()
  protected val projectScoresDao: ProjectScoresDao by lazyDao()
  protected val projectsDao: ProjectsDao by lazyDao()
  protected val projectVoteDecisionDao: ProjectVoteDecisionsDao by lazyDao()
  protected val projectVotesDao: ProjectVotesDao by lazyDao()
  protected val publishedActivitiesDao: PublishedActivitiesDao by lazyDao()
  protected val publishedActivityObservationMediaFilesDao:
      PublishedActivityObservationMediaFilesDao by
      lazyDao()
  protected val publishedActivityObservationsDao: PublishedActivityObservationsDao by lazyDao()
  protected val publishedActivityMediaFilesDao: PublishedActivityMediaFilesDao by lazyDao()
  protected val publishedAutoCalculatedIndicatorBaselinesDao:
      PublishedAutoCalculatedIndicatorBaselinesDao by
      lazyDao()
  protected val publishedAutoCalculatedIndicatorTargetsDao:
      PublishedAutoCalculatedIndicatorTargetsDao by
      lazyDao()
  protected val publishedCommonIndicatorBaselinesDao: PublishedCommonIndicatorBaselinesDao by
      lazyDao()
  protected val publishedCommonIndicatorTargetsDao: PublishedCommonIndicatorTargetsDao by lazyDao()
  protected val publishedProjectCarbonCertsDao: PublishedProjectCarbonCertsDao by lazyDao()
  protected val publishedProjectDetailsDao: PublishedProjectDetailsDao by lazyDao()
  protected val publishedProjectIndicatorBaselinesDao: PublishedProjectIndicatorBaselinesDao by
      lazyDao()
  protected val publishedProjectLandUseDao: PublishedProjectLandUseDao by lazyDao()
  protected val publishedProjectSdgDao: PublishedProjectSdgDao by lazyDao()
  protected val publishedReportAutoCalculatedIndicatorsDao:
      PublishedReportAutoCalculatedIndicatorsDao by
      lazyDao()
  protected val publishedReportCommonIndicatorsDao: PublishedReportCommonIndicatorsDao by lazyDao()
  protected val publishedReportPhotosDao: PublishedReportPhotosDao by lazyDao()
  protected val publishedReportProjectIndicatorsDao: PublishedReportProjectIndicatorsDao by
      lazyDao()
  protected val publishedProjectIndicatorTargetsDao: PublishedProjectIndicatorTargetsDao by
      lazyDao()
  protected val recordedPlantsDao: RecordedPlantsDao by lazyDao()
  protected val recordedTreesDao: RecordedTreesDao by lazyDao()
  protected val reportAchievementsDao: ReportAchievementsDao by lazyDao()
  protected val reportAutoCalculatedIndicatorsDao: ReportAutoCalculatedIndicatorsDao by lazyDao()
  protected val reportAutoCalculatedIndicatorTargetsDao: ReportAutoCalculatedIndicatorTargetsDao by
      lazyDao()
  protected val reportChallengesDao: ReportChallengesDao by lazyDao()
  protected val reportCommonIndicatorTargetsDao: ReportCommonIndicatorTargetsDao by lazyDao()
  protected val reportCommonIndicatorsDao: ReportCommonIndicatorsDao by lazyDao()
  protected val reportPhotosDao: ReportPhotosDao by lazyDao()
  protected val reportProjectIndicatorTargetsDao: ReportProjectIndicatorTargetsDao by lazyDao()
  protected val reportProjectIndicatorsDao: ReportProjectIndicatorsDao by lazyDao()
  protected val reportsDao: ReportsDao by lazyDao()
  protected val savedTableViewsDao: SavedTableViewsDao by lazyDao()
  protected val scheduledPlantingDatesDao: ScheduledPlantingDatesDao by lazyDao()
  protected val scheduledPlantingDateSpeciesDao: ScheduledPlantingDateSpeciesDao by lazyDao()
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
  protected val simplifiedPlantingSiteHistoriesDao: SimplifiedPlantingSiteHistoriesDao by lazyDao()
  protected val simplifiedPlantingSitesDao: SimplifiedPlantingSitesDao by lazyDao()
  protected val simplifiedStrataDao: SimplifiedStrataDao by lazyDao()
  protected val simplifiedStratumHistoriesDao: SimplifiedStratumHistoriesDao by lazyDao()
  protected val simplifiedSubstrataDao: SimplifiedSubstrataDao by lazyDao()
  protected val simplifiedSubstratumHistoriesDao: SimplifiedSubstratumHistoriesDao by lazyDao()
  protected val splatAnnotationsDao: SplatAnnotationsDao by lazyDao()
  protected val splatsDao: SplatsDao by lazyDao()
  protected val stratumHistoriesDao: StratumHistoriesDao by lazyDao()
  protected val stratumPopulationsDao: StratumPopulationsDao by lazyDao()
  protected val stratumSpeciesTargetsDao: StratumSpeciesTargetsDao by lazyDao()
  protected val strataDao: StrataDao by lazyDao()
  protected val subLocationsDao: SubLocationsDao by lazyDao()
  protected val submissionsDao: SubmissionsDao by lazyDao()
  protected val submissionDocumentsDao: SubmissionDocumentsDao by lazyDao()
  protected val submissionSnapshotsDao: SubmissionSnapshotsDao by lazyDao()
  protected val substratumHistoriesDao: SubstratumHistoriesDao by lazyDao()
  protected val substratumPopulationsDao: SubstratumPopulationsDao by lazyDao()
  protected val substrataDao: SubstrataDao by lazyDao()
  protected val thumbnailsDao: ThumbnailsDao by lazyDao()
  protected val timeseriesDao: TimeseriesDao by lazyDao()
  protected val timeZonesDao: TimeZonesDao by lazyDao()
  protected val uploadProblemsDao: UploadProblemsDao by lazyDao()
  protected val uploadsDao: UploadsDao by lazyDao()
  protected val userDisclaimersDao: UserDisclaimersDao by lazyDao()
  protected val userEmailNotificationCategoriesDao: UserEmailNotificationCategoriesDao by lazyDao()
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
      botanicalCountryCode: String? = null,
      countryCode: String? = null,
      countrySubdivisionCode: String? = null,
      createdBy: UserId = inserted.userId,
      timeZone: ZoneId? = null,
  ): OrganizationId {
    return with(ORGANIZATIONS) {
      dslContext
          .insertInto(ORGANIZATIONS)
          .set(BOTANICAL_COUNTRY_CODE, botanicalCountryCode)
          .set(COUNTRY_CODE, countryCode)
          .set(COUNTRY_SUBDIVISION_CODE, countrySubdivisionCode)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, clock.instant())
          .set(NAME, name)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, clock.instant())
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
      createdBy: UserId = inserted.userId,
      type: FacilityType = FacilityType.SeedBank,
      maxIdleMinutes: Int = 30,
      lastTimeseriesTime: Instant? = null,
      idleAfterTime: Instant? = null,
      idleSinceTime: Instant? = null,
      lastNotificationDate: LocalDate? = null,
      nextNotificationTime: Instant = clock.instant(),
      timeZone: ZoneId? = null,
      buildStartedDate: LocalDate? = null,
      buildCompletedDate: LocalDate? = null,
      operationStartedDate: LocalDate? = null,
      capacity: Int? = null,
      facilityNumber: Int = nextFacilityNumber,
      location: Point? = null,
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
          .set(CREATED_TIME, clock.instant())
          .set(DESCRIPTION, description)
          .set(FACILITY_NUMBER, facilityNumber)
          .set(IDLE_AFTER_TIME, idleAfterTime)
          .set(IDLE_SINCE_TIME, idleSinceTime)
          .set(LAST_NOTIFICATION_DATE, lastNotificationDate)
          .set(LAST_TIMESERIES_TIME, lastTimeseriesTime)
          .set(LOCATION, location)
          .set(MAX_IDLE_MINUTES, maxIdleMinutes)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, clock.instant())
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      description: String? = null,
      phase: AcceleratorPhase? = null,
      botanicalCountryCode: String? = null,
      countryCode: String? = null,
  ): ProjectId {
    val row =
        ProjectsRow(
            botanicalCountryCode = botanicalCountryCode,
            countryCode = countryCode,
            createdBy = createdBy,
            createdTime = createdTime,
            description = description,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = name,
            organizationId = organizationId,
            phaseId = phase,
        )

    projectsDao.insert(row)

    return row.id!!.also { inserted.projectIds.add(it) }
  }

  protected fun insertProjectAcceleratorDetails(
      annualCarbon: Number? = null,
      applicationReforestableLand: Number? = null,
      carbonCapacity: Number? = null,
      confirmedReforestableLand: Number? = null,
      dealDescription: String? = null,
      dealName: String? = null,
      dealStage: DealStage? = null,
      dropboxFolderPath: String? = null,
      failureRisk: String? = null,
      fileNaming: String? = null,
      googleFolderUrl: Any? = null,
      hubSpotUrl: Any? = null,
      investmentThesis: String? = null,
      logframeUrl: Any? = null,
      maxCarbonAccumulation: Number? = null,
      minCarbonAccumulation: Number? = null,
      numCommunities: Int? = null,
      numNativeSpecies: Int? = null,
      perHectareBudget: Number? = null,
      pipeline: Pipeline? = null,
      plantingSitesCql: String? = null,
      projectBoundariesCql: String? = null,
      projectId: ProjectId = inserted.projectId,
      totalCarbon: Number? = null,
      totalExpansionPotential: Number? = null,
      whatNeedsToBeTrue: String? = null,
  ): ProjectAcceleratorDetailsRow {
    val row =
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

    projectAcceleratorDetailsDao.insert(row)

    return row
  }

  protected fun insertProjectInternalUser(
      projectId: ProjectId = inserted.projectId,
      userId: UserId = inserted.userId,
      role: ProjectInternalRole? = null,
      roleName: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
  ): ProjectInternalUsersRow {
    val row =
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

    projectInternalUsersDao.insert(row)

    return row
  }

  protected fun insertPublishedProjectDetails(
      projectId: ProjectId = inserted.projectId,
      accumulationRate: Number? = null,
      annualCarbon: Number? = null,
      countryCode: String? = null,
      dealDescription: String? = null,
      dealName: String? = null,
      methodologyNumber: String? = null,
      minProjectArea: Number? = null,
      numNativeSpecies: Int? = null,
      perHectareEstimatedBudget: Number? = null,
      projectArea: Number? = null,
      projectHighlightPhotoValueId: VariableValueId? = null,
      projectZoneFigureValueId: VariableValueId? = null,
      standard: String? = null,
      tfReforestableLand: Number? = null,
      totalExpansionPotential: Number? = null,
      totalVcu: Number? = null,
      verraLink: String? = null,
      sdgList: Set<SustainableDevelopmentGoal> = emptySet(),
      carbonCertifications: Set<CarbonCertification> = emptySet(),
      landUseModelHectares: Map<LandUseModelType, Number?> = emptyMap(),
      publishedBy: UserId = inserted.userId,
      publishedTime: Instant = clock.instant(),
  ): PublishedProjectDetailsRow {
    val row =
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

    publishedProjectDetailsDao.insert(row)
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

    return row
  }

  protected fun insertPublishedProjectSdg(
      projectId: ProjectId = inserted.projectId,
      sdgNumber: Int,
  ) {
    val row =
        PublishedProjectSdgRow(
            projectId = projectId,
            sdgNumber = sdgNumber,
        )
    publishedProjectSdgDao.insert(row)
  }

  protected fun insertPublishedProjectCarbonCert(
      projectId: ProjectId = inserted.projectId,
      carbonCertification: String,
  ) {
    val row =
        PublishedProjectCarbonCertsRow(
            projectId = projectId,
            carbonCertification = carbonCertification,
        )
    publishedProjectCarbonCertsDao.insert(row)
  }

  protected fun insertPublishedProjectLandHectare(
      projectId: ProjectId = inserted.projectId,
      landUseModelType: LandUseModelType,
      landUseHectares: Number? = null,
  ) {
    val row =
        PublishedProjectLandUseRow(
            projectId = projectId,
            landUseModelTypeId = landUseModelType,
            landUseModelHectares = landUseHectares?.toBigDecimal(),
        )
    publishedProjectLandUseDao.insert(row)
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

  protected fun insertProjectIndicator(
      active: Boolean = true,
      category: IndicatorCategory = IndicatorCategory.ProjectObjectives,
      classId: IndicatorClass = IndicatorClass.NotCumulative,
      description: String? = null,
      frequency: IndicatorFrequency? = null,
      isPublishable: Boolean = true,
      level: IndicatorLevel = IndicatorLevel.Goal,
      name: String = "Indicator name",
      notes: String? = null,
      precision: Int = 0,
      primaryDataSource: String? = null,
      projectId: ProjectId = inserted.projectId,
      refId: String = "1.1",
      tfOwner: String? = "Carbon",
      unit: String? = null,
  ): ProjectIndicatorId {
    val row =
        ProjectIndicatorsRow(
            active = active,
            categoryId = category,
            classId = classId,
            description = description,
            frequencyId = frequency,
            isPublishable = isPublishable,
            levelId = level,
            name = name,
            notes = notes,
            precision = precision,
            primaryDataSource = primaryDataSource,
            projectId = projectId,
            refId = refId,
            tfOwner = tfOwner,
            unit = unit,
        )

    projectIndicatorsDao.insert(row)

    return row.id!!.also { inserted.projectIndicatorIds.add(it) }
  }

  protected fun insertProjectOverallScore(
      projectId: ProjectId = inserted.projectId,
      detailsUrl: URI? = null,
      overallScore: Double? = null,
      summary: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      phase: AcceleratorPhase = AcceleratorPhase.Phase0DueDiligence,
      category: ScoreCategory = ScoreCategory.Legal,
      score: Int? = null,
      qualitative: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
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
      createdTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      balenaModifiedTime: Instant = clock.instant(),
      createdTime: Instant = clock.instant(),
      facilityId: FacilityId? = null,
      refreshedTime: Instant = clock.instant(),
      userId: UserId? = if (facilityId != null) inserted.userId else null,
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
      createdBy: UserId = inserted.userId,
  ): AutomationId {
    return with(AUTOMATIONS) {
      dslContext
          .insertInto(AUTOMATIONS)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, clock.instant())
          .set(DEVICE_ID, deviceId)
          .set(FACILITY_ID, facilityId)
          .set(LOWER_THRESHOLD, lowerThreshold)
          .set(MODIFIED_BY, createdBy)
          .set(MODIFIED_TIME, clock.instant())
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedTime: Instant = createdTime,
      organizationId: OrganizationId = inserted.organizationId,
      deletedTime: Instant? = null,
      checkedTime: Instant? = null,
      initialScientificName: String = scientificName,
      commonName: String? = null,
      commonNameDatasetDate: LocalDate? = null,
      commonNameDatasetType: ExternalDatasetType? = commonNameDatasetDate?.let {
        ExternalDatasetType.GBIF
      },
      familyName: String? = null,
      familyNameDatasetDate: LocalDate? = null,
      familyNameDatasetType: ExternalDatasetType? = familyNameDatasetDate?.let {
        ExternalDatasetType.GBIF
      },
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
              .set(COMMON_NAME_DATASET_DATE, commonNameDatasetDate)
              .set(COMMON_NAME_DATASET_TYPE_ID, commonNameDatasetType)
              .set(CONSERVATION_CATEGORY_ID, conservationCategory)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, createdTime)
              .set(DELETED_BY, if (deletedTime != null) createdBy else null)
              .set(DELETED_TIME, deletedTime)
              .set(FAMILY_NAME, familyName)
              .set(FAMILY_NAME_DATASET_DATE, familyNameDatasetDate)
              .set(FAMILY_NAME_DATASET_TYPE_ID, familyNameDatasetType)
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

  fun insertProjectSpecies(
      calculatedNativity: SpeciesNativity? = null,
      calculatedNativityDatasetDate: LocalDate? = calculatedNativity?.let { LocalDate.EPOCH },
      calculatedNativityDatasetType: ExternalDatasetType? = calculatedNativity?.let {
        ExternalDatasetType.GRIIS
      },
      organizationId: OrganizationId = inserted.organizationId,
      projectId: ProjectId? = inserted.projectId,
      overriddenNativityId: SpeciesNativity? = null,
      overriddenBy: UserId? = overriddenNativityId?.let { inserted.userId },
      overriddenJustification: String? = overriddenNativityId?.let { "Justification" },
      overriddenTime: Instant? = overriddenNativityId?.let { clock.instant() },
      pendingNativity: SpeciesNativity? = null,
      pendingNativityDatasetDate: LocalDate? = pendingNativity?.let { LocalDate.EPOCH },
      pendingNativityDatasetType: ExternalDatasetType? = pendingNativity?.let {
        ExternalDatasetType.GRIIS
      },
      speciesId: SpeciesId = inserted.speciesId,
  ) {
    ProjectSpeciesRecord(
            calculatedNativityDatasetDate = calculatedNativityDatasetDate,
            calculatedNativityDatasetTypeId = calculatedNativityDatasetType,
            calculatedNativityId = calculatedNativity,
            organizationId = organizationId,
            overriddenBy = overriddenBy,
            overriddenJustification = overriddenJustification,
            overriddenNativityId = overriddenNativityId,
            overriddenTime = overriddenTime,
            pendingNativityDatasetDate = pendingNativityDatasetDate,
            pendingNativityDatasetTypeId = pendingNativityDatasetType,
            pendingNativityId = pendingNativity,
            projectId = projectId,
            speciesId = speciesId,
        )
        .attach(dslContext)
        .insert()
  }

  fun insertSpeciesProblem(
      speciesId: SpeciesId = inserted.speciesId,
      type: SpeciesProblemType = SpeciesProblemType.NameNotFound,
      suggestedValue: String? = null,
      createdTime: Instant = clock.instant(),
  ): SpeciesProblemId {
    val record =
        SpeciesProblemsRecord(
                createdTime = createdTime,
                fieldId = SpeciesProblemField.ScientificName,
                speciesId = speciesId,
                suggestedValue = suggestedValue,
                typeId = type,
            )
            .attach(dslContext)

    record.insert()

    return record.id!!.also { inserted.speciesProblemIds.add(it) }
  }

  fun insertSubmission(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      cookiesConsentedTime: Instant? = if (cookiesConsented != null) clock.instant() else null,
      createdTime: Instant = clock.instant(),
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
      acceptedOn: Instant = clock.instant(),
  ) {
    userDisclaimersDao.insert(
        UserDisclaimersRow(
            userId = userId,
            disclaimerId = disclaimerId,
            acceptedOn = acceptedOn,
        )
    )
  }

  fun insertUserEmailNotificationCategory(
      category: EmailNotificationCategory,
      userId: UserId = inserted.userId,
  ) {
    userEmailNotificationCategoriesDao.insert(
        UserEmailNotificationCategoriesRow(
            emailNotificationCategoryId = category,
            userId = userId,
        )
    )
  }

  fun insertUserInternalInterest(
      internalInterest: InternalInterest,
      userId: UserId = inserted.userId,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      userId: UserId = inserted.userId,
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
      userId: UserId = inserted.userId,
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
      userId: UserId = inserted.userId,
      organizationId: OrganizationId = inserted.organizationId,
      role: Role = Role.Contributor,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      userId: UserId = inserted.userId,
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
      createdBy: UserId = inserted.userId,
  ): SubLocationId {
    val insertedId =
        with(SUB_LOCATIONS) {
          dslContext
              .insertInto(SUB_LOCATIONS)
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, clock.instant())
              .set(FACILITY_ID, facilityId)
              .set(MODIFIED_BY, createdBy)
              .set(MODIFIED_TIME, clock.instant())
              .set(NAME, name)
              .returning(ID)
              .fetchOne(ID)!!
        }

    return insertedId.also { inserted.subLocationIds.add(it) }
  }

  private var nextStorageUrlNumber = 1

  fun insertFile(
      fileName: String = "fileName",
      contentType: String = "image/jpeg",
      size: Long = 1,
      capturedLocalTime: LocalDateTime? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      fileBatchId: FileBatchId? = null,
      geolocation: Point? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      storageUrl: Any = "http://dummy/${nextStorageUrlNumber++}",
  ): FileId {
    val row =
        FilesRow(
            capturedLocalTime = capturedLocalTime,
            contentType = contentType,
            createdBy = createdBy,
            createdTime = createdTime,
            fileBatchId = fileBatchId,
            fileName = fileName,
            geolocation = geolocation,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            size = size,
            storageUrl = URI("$storageUrl"),
        )

    filesDao.insert(row)

    return row.id!!.also { inserted.fileIds.add(it) }
  }

  fun insertFileBatch(
      batchStatus: FileBatchStatus = FileBatchStatus.Uploading,
      batchType: FileBatchType = FileBatchType.Splat,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
  ): FileBatchId {
    val row =
        FileBatchesRow(
            batchStatusId = batchStatus,
            batchTypeId = batchType,
            createdBy = createdBy,
            createdTime = createdTime,
        )

    fileBatchesDao.insert(row)

    return row.id!!.also { inserted.fileBatchIds.add(it) }
  }

  private var nextUploadNumber = 1

  fun insertUpload(
      type: UploadType = UploadType.SpeciesCSV,
      fileName: String = "${nextUploadNumber}.csv",
      storageUrl: URI = URI.create("file:///${nextUploadNumber}.csv"),
      contentType: String = "text/csv",
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      userId: UserId = inserted.userId,
      type: NotificationType = NotificationType.FacilityIdle,
      organizationId: OrganizationId? = null,
      title: String = "",
      body: String = "",
      localUrl: URI = URI.create(""),
      createdTime: Instant = clock.instant(),
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

  fun insertSavedTableView(
      organizationId: OrganizationId? = null,
      tableName: String = "species",
      name: String = "View ${nextSavedTableViewNumber++}",
      settings: JSONB = JSONB.valueOf("{}"),
      userId: UserId = inserted.userId,
      baseView: String? = null,
  ): SavedTableViewId {
    val row =
        SavedTableViewsRow(
            baseView = baseView,
            name = name,
            organizationId = organizationId,
            settings = settings,
            tableName = tableName,
            userId = userId,
        )

    savedTableViewsDao.insert(row)

    return row.id!!.also { inserted.savedTableViewIds.add(it) }
  }

  private var nextSavedTableViewNumber = 1

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
      createdBy: UserId = row.createdBy ?: inserted.userId,
      createdTime: Instant = row.createdTime ?: clock.instant(),
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

  private var nextAccessionCollectorPosition = 0

  fun insertAccessionCollector(
      accessionId: AccessionId = inserted.accessionId,
      organizationId: OrganizationId = inserted.organizationId,
      position: Int = nextAccessionCollectorPosition++,
      name: String = "collector",
  ) {
    AccessionCollectorsRecord(
            accessionId = accessionId,
            name = name,
            organizationId = organizationId,
            position = position,
        )
        .attach(dslContext)
        .insert()
  }

  private var nextBagNumber = 1

  fun insertBag(
      accessionId: AccessionId = inserted.accessionId,
      bagNumber: String = "${nextBagNumber++}",
  ): BagId {
    val row = BagsRow(bagNumber = bagNumber, accessionId = accessionId)

    bagsDao.insert(row)

    return row.id!!.also { inserted.bagsIds.add(it) }
  }

  fun insertViabilityTest(
      accessionId: AccessionId = inserted.accessionId,
      testType: ViabilityTestType? = ViabilityTestType.Lab,
      seedTypeId: ViabilityTestSeedType? = null,
      substrateId: ViabilityTestSubstrate? = null,
      treatmentId: SeedTreatment? = null,
      seedsSown: Int? = null,
      notes: String? = null,
      staffResponsible: String? = null,
      totalSeedsGerminated: Int? = null,
      totalPercentGerminated: Int? = null,
      startDate: LocalDate? = null,
      endDate: LocalDate? = null,
      seedsCompromised: Int? = null,
      seedsEmpty: Int? = null,
      seedsFilled: Int? = null,
  ): ViabilityTestId {
    val row =
        ViabilityTestsRow(
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

    viabilityTestsDao.insert(row)

    return row.id!!.also { inserted.viabilityTestIds.add(it) }
  }

  private var nextViabilityTestResultsNumber: Int = 1

  fun insertViabilityTestResult(
      viabilityTestId: ViabilityTestId = inserted.viabilityTestId,
      recordingDate: LocalDate = LocalDate.EPOCH,
      seedsGerminated: Int = nextViabilityTestResultsNumber++,
  ): ViabilityTestResultId {
    val row =
        ViabilityTestResultsRow(
            testId = viabilityTestId,
            recordingDate = recordingDate,
            seedsGerminated = seedsGerminated,
        )

    viabilityTestResultsDao.insert(row)

    return row.id!!.also { inserted.viabilityTestResultIds.add(it) }
  }

  private var nextBatchNumber: Int = 1

  fun insertBatch(
      accessionId: AccessionId? = null,
      activeGrowthQuantity: Int = 0,
      addedDate: LocalDate = LocalDate.EPOCH,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      facilityId: FacilityId = inserted.facilityId,
      germinatingQuantity: Int = 0,
      germinationStartedDate: LocalDate? = null,
      hardeningOffQuantity: Int = 0,
      insertStageDetails: Boolean = true,
      notes: String? = null,
      organizationId: OrganizationId = inserted.organizationId,
      projectId: ProjectId? = null,
      readyQuantity: Int = 0,
      readyByDate: LocalDate? = null,
      seedsSownDate: LocalDate? = null,
      speciesId: SpeciesId = inserted.speciesId,
      substrateId: BatchSubstrate? = null,
      substrateNotes: String? = null,
      treatmentId: SeedTreatment? = null,
      treatmentNotes: String? = null,
      version: Int = 1,
      batchNumber: String = "${nextBatchNumber++}",
      germinationRate: Int? = null,
      totalGerminated: Int? = null,
      totalGerminationCandidates: Int? = null,
      lossRate: Int? =
          if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0) 0
          else null,
      totalLost: Int? =
          if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0) 0
          else null,
      totalLossCandidates: Int? =
          if (activeGrowthQuantity > 0 || hardeningOffQuantity > 0 || readyQuantity > 0)
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

    val row =
        BatchesRow(
            accessionId = accessionId,
            addedDate = addedDate,
            batchNumber = batchNumber,
            createdBy = createdBy,
            createdTime = createdTime,
            facilityId = facilityId,
            germinationStartedDate = germinationStartedDate,
            germinationRate = effectiveGerminationRate,
            totalGerminated = totalGerminated,
            totalGerminationCandidates = totalGerminationCandidates,
            latestObservedTime = createdTime,
            lossRate = effectiveLossRate,
            totalLost = totalLost,
            totalLossCandidates = totalLossCandidates,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            notes = notes,
            organizationId = organizationId,
            projectId = projectId,
            readyByDate = readyByDate,
            seedsSownDate = seedsSownDate,
            speciesId = speciesId,
            substrateId = substrateId,
            substrateNotes = substrateNotes,
            treatmentId = treatmentId,
            treatmentNotes = treatmentNotes,
            version = version,
        )

    batchesDao.insert(row)
    if (insertStageDetails) {
      mapOf(
              BatchStage.Germinating to germinatingQuantity,
              BatchStage.ActiveGrowth to activeGrowthQuantity,
              BatchStage.HardeningOff to hardeningOffQuantity,
              BatchStage.Ready to readyQuantity,
          )
          .forEach { (stage, quantity) ->
            insertBatchStageDetails(batchId = row.id!!, stage = stage, quantity = quantity)
          }
    }

    return row.id!!.also { inserted.batchIds.add(it) }
  }

  fun insertBatchStageDetails(
      batchId: BatchId = inserted.batchId,
      stage: BatchStage = BatchStage.Germinating,
      quantity: Int = 0,
      latestObservedQuantity: Int = quantity,
      countedQuantity: Int? = null,
      countedSeedlingDays: Double? = null,
      openSeedlingDays: Double? = null,
      timeInStageAsOf: Instant? = null,
      timeInStageCleared: Boolean? = null,
  ) {
    with(BATCH_STAGE_DETAILS) {
      dslContext
          .insertInto(this)
          .set(BATCH_ID, batchId)
          .set(STAGE_ID, stage)
          .set(QUANTITY, quantity)
          .set(LATEST_OBSERVED_QUANTITY, latestObservedQuantity)
          .set(COUNTED_QUANTITY, countedQuantity)
          .set(COUNTED_SEEDLING_DAYS, countedSeedlingDays)
          .set(OPEN_SEEDLING_DAYS, openSeedlingDays)
          .set(TIME_IN_STAGE_AS_OF, timeInStageAsOf)
          .set(TIME_IN_STAGE_CLEARED, timeInStageCleared)
          .execute()
    }
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

  fun insertBatchQuantityHistory(
      accessionId: AccessionId? = null,
      activeGrowthQuantity: Int = 0,
      batchId: BatchId = inserted.batchId,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      effectiveTime: Instant? = null,
      germinatingQuantity: Int = 0,
      hardeningOffQuantity: Int = 0,
      historyType: BatchQuantityHistoryType = BatchQuantityHistoryType.Observed,
      notes: String? = null,
      readyQuantity: Int = 0,
      version: Int = 1,
      withdrawalId: WithdrawalId? = null,
  ): BatchQuantityHistoryId {
    val record =
        BatchQuantityHistoryRecord(
                accessionId = accessionId,
                activeGrowthQuantity = activeGrowthQuantity,
                batchId = batchId,
                createdBy = createdBy,
                createdTime = createdTime,
                effectiveTime = effectiveTime,
                germinatingQuantity = germinatingQuantity,
                hardeningOffQuantity = hardeningOffQuantity,
                historyTypeId = historyType,
                notes = notes,
                readyQuantity = readyQuantity,
                version = version,
                withdrawalId = withdrawalId,
            )
            .attach(dslContext)

    record.insert()

    return record.id!!.also { inserted.batchQuantityHistoryIds.add(it) }
  }

  fun insertNurseryWithdrawal(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      destinationFacilityId: FacilityId? = null,
      facilityId: FacilityId = inserted.facilityId,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      plantingSeasonId: PlantingSeasonId? = null,
      purpose: WithdrawalPurpose = WithdrawalPurpose.Other,
      scheduledPlantingDateRequestId: ScheduledPlantingDateId? = null,
      undoesWithdrawalId: WithdrawalId? = null,
      withdrawnDate: LocalDate = LocalDate.EPOCH,
  ): WithdrawalId {
    val row =
        WithdrawalsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            destinationFacilityId = destinationFacilityId,
            facilityId = facilityId,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSeasonId = plantingSeasonId,
            purposeId = purpose,
            scheduledPlantingDateRequestId = scheduledPlantingDateRequestId,
            undoesWithdrawalId = undoesWithdrawalId,
            withdrawnDate = withdrawnDate,
        )

    nurseryWithdrawalsDao.insert(row)

    return row.id!!.also { inserted.withdrawalIds.add(it) }
  }

  fun insertNurseryWithdrawalPhoto(
      fileId: FileId = inserted.fileId,
      withdrawalId: WithdrawalId = inserted.withdrawalId,
  ) {
    val row =
        WithdrawalPhotosRow(
            fileId = fileId,
            withdrawalId = withdrawalId,
        )

    withdrawalPhotosDao.insert(row)
  }

  fun insertSeedbankWithdrawal(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      updatedTime: Instant = clock.instant(),
      withdrawnBy: UserId = inserted.userId,
      date: LocalDate = LocalDate.EPOCH,
      accessionId: AccessionId = inserted.accessionId,
      purpose: SeedbankWithdrawalPurpose? = null,
      destination: String? = null,
      staffResponsible: String? = null,
      notes: String? = null,
      viabilityTestId: ViabilityTestId? = null,
      withdrawnGrams: BigDecimal? = null,
      withdrawnQuantity: BigDecimal? = null,
      withdrawnUnitsId: SeedQuantityUnits = SeedQuantityUnits.Grams,
      estimatedCount: Long? = null,
      estimatedWeightQuantity: BigDecimal? = null,
      estimatedWeightUnitsId: SeedQuantityUnits? = null,
      batchId: BatchId? = inserted.batchId,
  ): SeedbankWithdrawalId {
    val row =
        SeedbankWithdrawalsRow(
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

    withdrawalsDao.insert(row)

    return row.id!!.also { inserted.seedbankWithdrawalIds.add(it) }
  }

  fun insertBatchWithdrawal(
      activeGrowthQuantityWithdrawn: Int = 0,
      batchId: BatchId = inserted.batchId,
      destinationBatchId: BatchId? = null,
      germinatingQuantityWithdrawn: Int = 0,
      hardeningOffQuantityWithdrawn: Int = 0,
      readyQuantityWithdrawn: Int = 0,
      withdrawalId: WithdrawalId = inserted.withdrawalId,
  ) {
    val row =
        BatchWithdrawalsRow(
            activeGrowthQuantityWithdrawn = activeGrowthQuantityWithdrawn,
            batchId = batchId,
            destinationBatchId = destinationBatchId,
            germinatingQuantityWithdrawn = germinatingQuantityWithdrawn,
            hardeningOffQuantityWithdrawn = hardeningOffQuantityWithdrawn,
            readyQuantityWithdrawn = readyQuantityWithdrawn,
            withdrawalId = withdrawalId,
        )

    batchWithdrawalsDao.insert(row)
  }

  var nextPlantingSeasonNumber: Int = 1

  fun insertPlantingSeason(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      endDate: LocalDate = LocalDate.EPOCH.plusDays(1),
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "Season ${nextPlantingSeasonNumber++}",
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      startDate: LocalDate = LocalDate.EPOCH,
      status: PlantingSeasonStatus = PlantingSeasonStatus.Upcoming,
  ): PlantingSeasonId {
    val row =
        PlantingSeasonsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            endDate = endDate,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            plantingSiteId = plantingSiteId,
            startDate = startDate,
            statusId = status,
        )

    plantingSeasonsDao.insert(row)

    return row.id!!.also { inserted.plantingSeasonIds.add(it) }
  }

  fun insertPlantingSeasonAllocatedSpecies(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      plantingSeasonId: PlantingSeasonId = inserted.plantingSeasonId,
      quantity: Int = 1,
      speciesId: SpeciesId = inserted.speciesId,
  ) {
    val row =
        PlantingSeasonAllocatedSpeciesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSeasonId = plantingSeasonId,
            quantity = quantity,
            speciesId = speciesId,
        )

    plantingSeasonAllocatedSpeciesDao.insert(row)
  }

  fun insertPlantingSeasonNotification(
      plantingSeasonId: PlantingSeasonId = inserted.plantingSeasonId,
      pageId: PlantingSeasonNotificationPage = PlantingSeasonNotificationPage.InventoryPlanning,
      lastDismissedEventLogId: EventLogId,
  ) {
    val row =
        PlantingSeasonNotificationsRow(
            plantingSeasonId = plantingSeasonId,
            pageId = pageId,
            lastDismissedEventLogId = lastDismissedEventLogId,
        )

    plantingSeasonNotificationsDao.insert(row)
  }

  fun insertPlantingSeasonSpeciesTarget(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      plantingSeasonId: PlantingSeasonId = inserted.plantingSeasonId,
      quantity: Int = 1,
      speciesId: SpeciesId = inserted.speciesId,
      substratumId: SubstratumId = inserted.substratumId,
  ) {
    val row =
        PlantingSeasonSpeciesTargetsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSeasonId = plantingSeasonId,
            quantity = quantity,
            speciesId = speciesId,
            substratumId = substratumId,
        )

    plantingSeasonSpeciesTargetsDao.insert(row)
  }

  fun insertPlantingSeasonScheduledDate(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      date: LocalDate = LocalDate.EPOCH,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      plantingSeasonId: PlantingSeasonId = inserted.plantingSeasonId,
  ): ScheduledPlantingDateId {
    val row =
        ScheduledPlantingDatesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            date = date,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSeasonId = plantingSeasonId,
        )

    scheduledPlantingDatesDao.insert(row)

    return row.id!!.also { inserted.scheduledPlantingDateIds.add(it) }
  }

  fun insertScheduledPlantingDateSpecies(
      scheduledPlantingDateId: ScheduledPlantingDateId = inserted.scheduledPlantingDateId,
      speciesId: SpeciesId = inserted.speciesId,
      substratumId: SubstratumId = inserted.substratumId,
      quantity: Int = 1,
  ) {
    val row =
        ScheduledPlantingDateSpeciesRow(
            scheduledPlantingDateId = scheduledPlantingDateId,
            speciesId = speciesId,
            substratumId = substratumId,
            quantity = quantity,
        )

    scheduledPlantingDateSpeciesDao.insert(row)
  }

  fun insertPlantingDateRequest(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      date: LocalDate = LocalDate.EPOCH,
      manuallyFulfilled: Boolean = false,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      notes: String? = null,
      scheduledPlantingDateId: ScheduledPlantingDateId = inserted.scheduledPlantingDateId,
      status: PlantingDateRequestStatus = PlantingDateRequestStatus.Pending,
  ) {
    val row =
        PlantingDateRequestsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            date = date,
            manuallyFulfilled = manuallyFulfilled,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            notes = notes,
            scheduledPlantingDateId = scheduledPlantingDateId,
            statusId = status,
        )

    plantingDateRequestsDao.insert(row)
  }

  fun insertPlantingDateRequestSpecies(
      scheduledPlantingDateId: ScheduledPlantingDateId = inserted.scheduledPlantingDateId,
      quantity: Int = 1,
      speciesId: SpeciesId = inserted.speciesId,
      substratumId: SubstratumId = inserted.substratumId,
  ) {
    val row =
        PlantingDateRequestSpeciesRow(
            scheduledPlantingDateId = scheduledPlantingDateId,
            quantity = quantity,
            speciesId = speciesId,
            substratumId = substratumId,
        )

    plantingDateRequestSpeciesDao.insert(row)
  }

  var nextPlantingSiteNumber: Int = 1
  private lateinit var lastPlantingSitesRow: PlantingSitesRow

  fun insertPlantingSite(
      areaHa: BigDecimal? = null,
      x: Number? = 0,
      y: Number? = if (x != null) 0 else null,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      exclusion: Geometry? = null,
      gridOrigin: Geometry? = null,
      organizationId: OrganizationId = inserted.organizationId,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "Site ${nextPlantingSiteNumber++}",
      timeZone: ZoneId? = null,
      projectId: ProjectId? = null,
      countryCode: String? = null,
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

    val row =
        PlantingSitesRow(
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

    plantingSitesDao.insert(row)
    lastPlantingSitesRow = row

    val plantingSiteId = row.id!!
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

    return row.id!!.also {
      inserted.plantingSiteHistoryIds.add(it)
      inserted.plantingSiteHistoryIdsByPlantingSiteId
          .getOrPut(plantingSiteId) { mutableListOf() }
          .add(it)
    }
  }

  fun insertPlantingSiteNotification(
      number: Int = 1,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      sentTime: Instant = clock.instant(),
      type: NotificationType,
  ): PlantingSiteNotificationId {
    val row =
        PlantingSiteNotificationsRow(
            notificationNumber = number,
            notificationTypeId = type,
            plantingSiteId = plantingSiteId,
            sentTime = sentTime,
        )

    plantingSiteNotificationsDao.insert(row)

    return row.id!!.also { inserted.plantingSiteNotificationIds.add(it) }
  }

  fun insertSimplifiedPlantingSite(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      boundary: Geometry = lastPlantingSitesRow.boundary ?: point(1),
      exclusion: Geometry? = lastPlantingSitesRow.exclusion,
  ) {
    val row =
        SimplifiedPlantingSitesRow(
            plantingSiteId = plantingSiteId,
            boundary = boundary,
            exclusion = exclusion,
        )

    simplifiedPlantingSitesDao.insert(row)
  }

  fun insertSimplifiedPlantingSiteHistory(
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      boundary: Geometry = lastPlantingSitesRow.boundary ?: point(1),
      exclusion: Geometry? = lastPlantingSitesRow.exclusion,
  ) {
    val row =
        SimplifiedPlantingSiteHistoriesRow(
            plantingSiteHistoryId = plantingSiteHistoryId,
            boundary = boundary,
            exclusion = exclusion,
        )

    simplifiedPlantingSiteHistoriesDao.insert(row)
  }

  fun insertSimplifiedStratum(
      stratumId: StratumId = inserted.stratumId,
      boundary: Geometry = lastStrataRow.boundary ?: point(1),
  ) {
    val row =
        SimplifiedStrataRow(
            stratumId = stratumId,
            boundary = boundary,
        )

    simplifiedStrataDao.insert(row)
  }

  fun insertSimplifiedStratumHistory(
      stratumHistoryId: StratumHistoryId = inserted.stratumHistoryId,
      boundary: Geometry = lastStrataRow.boundary ?: point(1),
  ) {
    val row =
        SimplifiedStratumHistoriesRow(
            stratumHistoryId = stratumHistoryId,
            boundary = boundary,
        )

    simplifiedStratumHistoriesDao.insert(row)
  }

  fun insertSimplifiedSubstratum(
      substratumId: SubstratumId = inserted.substratumId,
      boundary: Geometry = lastSubstrataRow.boundary ?: point(1),
  ) {
    val row =
        SimplifiedSubstrataRow(
            substratumId = substratumId,
            boundary = boundary,
        )

    simplifiedSubstrataDao.insert(row)
  }

  fun insertSimplifiedSubstratumHistory(
      substratumHistoryId: SubstratumHistoryId = inserted.substratumHistoryId,
      boundary: Geometry = lastSubstrataRow.boundary ?: point(1),
  ) {
    val row =
        SimplifiedSubstratumHistoriesRow(
            substratumHistoryId = substratumHistoryId,
            boundary = boundary,
        )

    simplifiedSubstratumHistoriesDao.insert(row)
  }

  private var nextStratumNumber: Int = 1
  private lateinit var lastStrataRow: StrataRow

  fun insertStratum(
      areaHa: BigDecimal = BigDecimal.TEN,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          rectangle(
              width.toDouble() * MONITORING_PLOT_SIZE,
              height.toDouble() * MONITORING_PLOT_SIZE,
              x.toDouble() * MONITORING_PLOT_SIZE,
              y.toDouble() * MONITORING_PLOT_SIZE,
          ),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      initialPlantingDensity: BigDecimal? = null,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "S${nextStratumNumber++}",
      plantErrorMargin: BigDecimal = StratumModel.DEFAULT_ERROR_MARGIN,
      plantNumPermanentPlots: Int = StratumModel.DEFAULT_NUM_PERMANENT_PLOTS,
      plantNumTemporaryPlots: Int = StratumModel.DEFAULT_NUM_TEMPORARY_PLOTS,
      plantStudentsT: BigDecimal = StratumModel.DEFAULT_STUDENTS_T,
      plantVariance: BigDecimal = StratumModel.DEFAULT_VARIANCE,
      biomassErrorMargin: BigDecimal = plantErrorMargin,
      biomassNumPermanentPlots: Int = plantNumPermanentPlots,
      biomassNumTemporaryPlots: Int = plantNumTemporaryPlots,
      biomassStudentsT: BigDecimal = plantStudentsT,
      biomassVariance: BigDecimal = plantVariance,
      stableId: Any = name,
      targetPlantDensity: BigDecimal? = null,
      insertHistory: Boolean = true,
      boundaryModifiedBy: UserId = modifiedBy,
      boundaryModifiedTime: Instant = modifiedTime,
  ): StratumId {
    val row =
        StrataRow(
            areaHa = areaHa,
            biomassErrorMargin = biomassErrorMargin,
            biomassNumPermanentPlots = biomassNumPermanentPlots,
            biomassNumTemporaryPlots = biomassNumTemporaryPlots,
            biomassStudentsT = biomassStudentsT,
            biomassVariance = biomassVariance,
            boundary = boundary,
            boundaryModifiedBy = boundaryModifiedBy,
            boundaryModifiedTime = boundaryModifiedTime,
            createdBy = createdBy,
            createdTime = createdTime,
            initialPlantingDensity = initialPlantingDensity,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            plantErrorMargin = plantErrorMargin,
            plantNumPermanentPlots = plantNumPermanentPlots,
            plantNumTemporaryPlots = plantNumTemporaryPlots,
            plantStudentsT = plantStudentsT,
            plantVariance = plantVariance,
            plantingSiteId = plantingSiteId,
            stableId = StableId("$stableId"),
            targetPlantDensity = targetPlantDensity,
        )

    strataDao.insert(row)
    lastStrataRow = row

    val stratumId = row.id!!
    inserted.stratumIds.add(stratumId)

    if (insertHistory && inserted.plantingSiteHistoryIds.isNotEmpty()) {
      insertStratumHistory()
    }

    return stratumId
  }

  fun insertStratumHistory(
      areaHa: BigDecimal = lastStrataRow.areaHa!!,
      boundary: Geometry = lastStrataRow.boundary!!,
      name: String = lastStrataRow.name!!,
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      stratumId: StratumId = inserted.stratumId,
      stableId: Any = lastStrataRow.stableId!!,
  ): StratumHistoryId {
    val row =
        StratumHistoriesRow(
            areaHa = areaHa,
            boundary = boundary,
            name = name,
            plantingSiteHistoryId = plantingSiteHistoryId,
            stratumId = stratumId,
            stableId = StableId("$stableId"),
        )

    stratumHistoriesDao.insert(row)

    return row.id!!.also {
      inserted.stratumHistoryIds.add(it)
      inserted.stratumHistoryIdsByStratumId.getOrPut(stratumId) { mutableListOf() }.add(it)
    }
  }

  private var nextSubstratumNumber: Int = 1
  private lateinit var lastSubstrataRow: SubstrataRow

  fun insertSubstratum(
      areaHa: BigDecimal = BigDecimal.ONE,
      x: Number = 0,
      y: Number = 0,
      width: Number = 3,
      height: Number = 2,
      boundary: Geometry =
          rectangle(
              width.toDouble() * MONITORING_PLOT_SIZE,
              height.toDouble() * MONITORING_PLOT_SIZE,
              x.toDouble() * MONITORING_PLOT_SIZE,
              y.toDouble() * MONITORING_PLOT_SIZE,
          ),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      observedTime: Instant? = null,
      plantingCompletedTime: Instant? = null,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      stratumId: StratumId = inserted.stratumId,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "${nextSubstratumNumber++}",
      fullName: String = "${lastStrataRow.name}-$name",
      stableId: Any = fullName,
      insertHistory: Boolean = true,
  ): SubstratumId {
    val row =
        SubstrataRow(
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
            stratumId = stratumId,
            stableId = StableId("$stableId"),
        )

    substrataDao.insert(row)
    lastSubstrataRow = row

    val substratumId = row.id!!
    inserted.substratumIds.add(substratumId)

    if (insertHistory && inserted.stratumHistoryIds.isNotEmpty()) {
      insertSubstratumHistory()
    }

    return substratumId
  }

  fun insertSubstratumHistory(
      areaHa: BigDecimal = lastSubstrataRow.areaHa!!,
      boundary: Geometry = lastSubstrataRow.boundary!!,
      fullName: String = lastSubstrataRow.fullName!!,
      name: String = lastSubstrataRow.name!!,
      substratumId: SubstratumId? = inserted.substratumId,
      stratumHistoryId: StratumHistoryId = inserted.stratumHistoryId,
      stableId: Any = lastSubstrataRow.stableId!!,
  ): SubstratumHistoryId {
    val row =
        SubstratumHistoriesRow(
            areaHa = areaHa,
            boundary = boundary,
            fullName = fullName,
            name = name,
            stratumHistoryId = stratumHistoryId,
            substratumId = substratumId,
            stableId = StableId("$stableId"),
        )

    substratumHistoriesDao.insert(row)

    return row.id!!.also {
      inserted.substratumHistoryIds.add(it)
      if (substratumId != null) {
        inserted.substratumHistoryIdsBySubstratumId
            .getOrPut(substratumId) { mutableListOf() }
            .add(it)
      }
    }
  }

  private var nextModuleNumber: Int = 1

  fun insertModule(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      name: String = "Module $nextModuleNumber",
      position: Int = nextModuleNumber,
      overview: String? = null,
      preparationMaterials: String? = null,
      liveSessionDescription: String? = null,
      workshopDescription: String? = null,
      oneOnOneSessionDescription: String? = null,
      additionalResources: String? = null,
      phase: AcceleratorPhase = AcceleratorPhase.Phase1FeasibilityStudy,
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
      x: Number = 0,
      y: Number = 0,
      elevationMeters: BigDecimal? = null,
      sizeMeters: Int = MONITORING_PLOT_SIZE_INT,
      boundary: Polygon =
          rectanglePolygon(
              sizeMeters,
              sizeMeters,
              x.toDouble() * sizeMeters.toDouble(),
              y.toDouble() * sizeMeters.toDouble(),
          ),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      isAdHoc: Boolean = false,
      isAvailable: Boolean = true,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      organizationId: OrganizationId = inserted.organizationId,
      permanentIndex: Int? = null,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      substratumId: SubstratumId? = inserted.substratumIds.lastOrNull(),
      plotNumber: Long =
          IdentifierGenerator(clock, dslContext)
              .generateNumericIdentifier(organizationId, NumericIdentifierType.PlotNumber),
      insertHistory: Boolean = true,
  ): MonitoringPlotId {
    val row =
        MonitoringPlotsRow(
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
            substratumId = substratumId,
            plotNumber = plotNumber,
            sizeMeters = sizeMeters,
        )

    monitoringPlotsDao.insert(row)
    lastMonitoringPlotsRow = row

    val monitoringPlotId = row.id!!
    inserted.monitoringPlotIds.add(monitoringPlotId)

    if (insertHistory && inserted.plantingSiteHistoryIds.isNotEmpty()) {
      insertMonitoringPlotHistory(substratumId = substratumId)
    }

    return monitoringPlotId
  }

  fun insertMonitoringPlotHistory(
      createdBy: UserId = lastMonitoringPlotsRow.createdBy!!,
      createdTime: Instant = lastMonitoringPlotsRow.createdTime!!,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      substratumId: SubstratumId? =
          if (inserted.substratumIds.isNotEmpty()) inserted.substratumId else null,
      substratumHistoryId: SubstratumHistoryId? =
          if (substratumId != null) inserted.substratumHistoryId else null,
  ): MonitoringPlotHistoryId {
    val row =
        MonitoringPlotHistoriesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            monitoringPlotId = monitoringPlotId,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = plantingSiteId,
            substratumHistoryId = substratumHistoryId,
            substratumId = substratumId,
        )

    monitoringPlotHistoriesDao.insert(row)

    return row.id!!.also {
      inserted.monitoringPlotHistoryIds.add(it)
      inserted.monitoringPlotHistoryIdsByMonitoringPlotId
          .getOrPut(monitoringPlotId) { mutableListOf() }
          .add(it)
    }
  }

  fun insertMonitoringPlotOverlap(
      overlapsPlotId: MonitoringPlotId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
  ) {
    monitoringPlotOverlapsDao.insert(MonitoringPlotOverlapsRow(monitoringPlotId, overlapsPlotId))
  }

  fun insertDisclaimer(
      content: String = "Disclaimer",
      effectiveOn: Instant = clock.instant(),
  ): DisclaimerId {
    val row =
        DisclaimersRow(
            content = content,
            effectiveOn = effectiveOn,
        )

    disclaimersDao.insert(row)

    return row.id!!.also { inserted.disclaimerIds.add(it) }
  }

  private var nextDraftPlantingSiteNumber = 1

  fun insertDraftPlantingSite(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      boundary: Geometry? = null,
      boundarySource: String? = null,
      description: String? = null,
      exclusion: Geometry? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      name: String = "Draft site ${nextDraftPlantingSiteNumber++}",
      organizationId: OrganizationId = inserted.organizationId,
      projectId: ProjectId? = null,
      siteEditStep: String? = null,
      siteType: DraftPlantingSiteType = DraftPlantingSiteType.Detailed,
      timeZone: ZoneId? = null,
  ): DraftPlantingSiteId {
    val row =
        DraftPlantingSitesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            boundary = boundary,
            boundarySource = boundarySource,
            description = description,
            exclusion = exclusion,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            name = name,
            organizationId = organizationId,
            projectId = projectId,
            siteEditStep = siteEditStep,
            siteTypeId = siteType,
            timeZone = timeZone,
        )

    draftPlantingSitesDao.insert(row)

    return row.id!!.also { inserted.draftPlantingSiteIds.add(it) }
  }

  private var nextDraftStratumNumber = 1

  fun insertDraftStratum(
      draftPlantingSiteId: DraftPlantingSiteId = inserted.draftPlantingSiteId,
      name: String = "Stratum ${nextDraftStratumNumber++}",
      boundary: Geometry = rectangle(100),
      initialPlantingDensity: BigDecimal = BigDecimal(1500),
  ): DraftStratumId {
    val row =
        DraftStrataRow(
            boundary = boundary,
            draftPlantingSiteId = draftPlantingSiteId,
            initialPlantingDensity = initialPlantingDensity,
            name = name,
        )

    draftStrataDao.insert(row)

    return row.id!!.also { inserted.draftStratumIds.add(it) }
  }

  private var nextDraftSubstratumNumber = 1

  fun insertDraftSubstratum(
      draftStratumId: DraftStratumId = inserted.draftStratumId,
      name: String = "Substratum ${nextDraftSubstratumNumber++}",
      boundary: Geometry = rectangle(100),
  ): DraftSubstratumId {
    val row =
        DraftSubstrataRow(
            boundary = boundary,
            draftStratumId = draftStratumId,
            name = name,
        )

    draftSubstrataDao.insert(row)

    return row.id!!.also { inserted.draftSubstratumIds.add(it) }
  }

  fun insertDelivery(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      reassignedFromDeliveryId: DeliveryId? = null,
      withdrawalId: WithdrawalId = inserted.withdrawalId,
  ): DeliveryId {
    val rowWithDetails =
        DeliveriesRow(
            createdBy = createdBy,
            createdTime = createdTime,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
            plantingSiteId = plantingSiteId,
            reassignedFromDeliveryId = reassignedFromDeliveryId,
            withdrawalId = withdrawalId,
        )

    deliveriesDao.insert(rowWithDetails)

    return rowWithDetails.id!!.also { inserted.deliveryIds.add(it) }
  }

  fun insertPlanting(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      deliveryId: DeliveryId = inserted.deliveryId,
      numPlants: Int = 1,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      plantingTypeId: PlantingType = PlantingType.Delivery,
      substratumId: SubstratumId? = inserted.substratumIds.lastOrNull(),
      speciesId: SpeciesId = inserted.speciesId,
  ): PlantingId {
    val row =
        PlantingsRow(
            createdBy = createdBy,
            createdTime = createdTime,
            deliveryId = deliveryId,
            numPlants = numPlants,
            plantingSiteId = plantingSiteId,
            plantingTypeId = plantingTypeId,
            substratumId = substratumId,
            speciesId = speciesId,
        )

    plantingsDao.insert(row)

    return row.id!!.also { inserted.plantingIds.add(it) }
  }

  fun insertPlantingSitePopulation(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
  ) {
    plantingSitePopulationsDao.insert(
        PlantingSitePopulationsRow(
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
            totalPlants = totalPlants,
        )
    )
  }

  fun insertPlantingSiteSpeciesTarget(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      speciesId: SpeciesId = inserted.speciesId,
      targetPlants: Long? = null,
  ) {
    plantingSiteSpeciesTargetsDao.insert(
        PlantingSiteSpeciesTargetsRow(
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
            targetPlants = targetPlants,
        )
    )
  }

  fun insertSubstratumPopulation(
      substratumId: SubstratumId = inserted.substratumId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
  ) {
    substratumPopulationsDao.insert(
        SubstratumPopulationsRow(
            substratumId = substratumId,
            speciesId = speciesId,
            totalPlants = totalPlants,
        )
    )
  }

  fun insertStratumPopulation(
      stratumId: StratumId = inserted.stratumId,
      speciesId: SpeciesId = inserted.speciesId,
      totalPlants: Int = 1,
  ) {
    stratumPopulationsDao.insert(
        StratumPopulationsRow(
            stratumId = stratumId,
            speciesId = speciesId,
            totalPlants = totalPlants,
        )
    )
  }

  fun insertStratumSpeciesTarget(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      stratumId: StratumId = inserted.stratumId,
      speciesId: SpeciesId = inserted.speciesId,
  ) {
    stratumSpeciesTargetsDao.insert(
        StratumSpeciesTargetsRow(
            plantingSiteId = plantingSiteId,
            stratumId = stratumId,
            speciesId = speciesId,
        )
    )
  }

  fun insertSeedFundReport(
      body: String = """{"version":"1","organizationName":"org"}""",
      lockedBy: UserId? = null,
      lockedTime: Instant? = lockedBy?.let { clock.instant() },
      organizationId: OrganizationId = inserted.organizationId,
      projectId: ProjectId? = null,
      projectName: String? = null,
      quarter: Int = 1,
      submittedBy: UserId? = null,
      submittedTime: Instant? = submittedBy?.let { clock.instant() },
      status: SeedFundReportStatus =
          when {
            lockedBy != null -> SeedFundReportStatus.Locked
            submittedBy != null -> SeedFundReportStatus.Submitted
            else -> SeedFundReportStatus.New
          },
      year: Int = 1970,
  ): SeedFundReportId {
    val projectNameWithDefault =
        projectName ?: projectId?.let { projectsDao.fetchOneById(it)?.name }

    val row =
        SeedFundReportsRow(
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

    seedFundReportsDao.insert(row)

    return row.id!!.also { inserted.seedFundReportIds.add(it) }
  }

  fun insertCommonIndicator(
      active: Boolean = true,
      category: IndicatorCategory = IndicatorCategory.ProjectObjectives,
      classId: IndicatorClass = IndicatorClass.NotCumulative,
      description: String? = null,
      frequency: IndicatorFrequency? = null,
      isPublishable: Boolean = true,
      level: IndicatorLevel = IndicatorLevel.Goal,
      name: String = "Indicator name",
      notes: String? = null,
      precision: Int = 0,
      primaryDataSource: String? = null,
      refId: String = "1.1",
      tfOwner: String? = "Carbon",
      unit: String? = null,
  ): CommonIndicatorId {
    val row =
        CommonIndicatorsRow(
            active = active,
            categoryId = category,
            classId = classId,
            description = description,
            frequencyId = frequency,
            isPublishable = isPublishable,
            levelId = level,
            name = name,
            notes = notes,
            precision = precision,
            primaryDataSource = primaryDataSource,
            refId = refId,
            tfOwner = tfOwner,
            unit = unit,
        )

    commonIndicatorsDao.insert(row)

    return row.id!!.also { inserted.commonIndicatorIds.add(it) }
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
      projectId: ProjectId = inserted.projectId,
      reportingStartDate: LocalDate = LocalDate.EPOCH,
      reportingEndDate: LocalDate = reportingStartDate.plusDays(1),
  ): ProjectReportConfigId {
    val row =
        ProjectReportConfigsRow(
            projectId = projectId,
            reportingStartDate = reportingStartDate,
            reportingEndDate = reportingEndDate,
        )
    projectReportConfigsDao.insert(row)

    return row.id!!.also { inserted.projectReportConfigIds.add(it) }
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
      createdTime: Instant = clock.instant(),
      endDate: LocalDate = LocalDate.of(2023, 1, 31),
      isAdHoc: Boolean = false,
      observationType: ObservationType = ObservationType.Monitoring,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      startDate: LocalDate = LocalDate.of(2023, 1, 1),
      completedTime: Instant? = null,
      state: ObservationState =
          if (completedTime != null) {
            ObservationState.Completed
          } else {
            ObservationState.InProgress
          },
      plantingSiteHistoryId: PlantingSiteHistoryId? =
          if (state != ObservationState.Upcoming) {
            inserted.plantingSiteHistoryId
          } else {
            null
          },
      upcomingNotificationSentTime: Instant? = null,
  ): ObservationId {
    val row =
        ObservationsRow(
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

    observationsDao.insert(row)

    return row.id!!.also { inserted.observationIds.add(it) }
  }

  fun insertObservationBiomassDetails(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      description: String? = null,
      forestType: BiomassForestType = BiomassForestType.Terrestrial,
      smallTreesCountLow: Int = 0,
      smallTreesCountHigh: Int = 0,
      herbaceousCoverPercent: Int = 0,
      soilAssessment: String = "Soil Assessment",
      soilType: SoilType? = null,
      waterDepthCm: Int? = null,
      salinityPpt: BigDecimal? = null,
      ph: BigDecimal? = null,
      tideId: MangroveTide? = null,
      tideTime: Instant? = null,
      nextTreeNumber: Int = 1,
  ) {
    val row =
        ObservationBiomassDetailsRow(
            description = description,
            forestTypeId = forestType,
            herbaceousCoverPercent = herbaceousCoverPercent,
            monitoringPlotId = monitoringPlotId,
            nextTreeNumber = nextTreeNumber,
            observationId = observationId,
            ph = ph,
            salinityPpt = salinityPpt,
            smallTreesCountHigh = smallTreesCountHigh,
            smallTreesCountLow = smallTreesCountLow,
            soilAssessment = soilAssessment,
            soilTypeId = soilType,
            tideId = tideId,
            tideTime = tideTime,
            waterDepthCm = waterDepthCm,
        )

    observationBiomassDetailsDao.insert(row)
  }

  fun insertObservationBiomassSpecies(
      observationId: ObservationId = inserted.observationId,
      speciesId: SpeciesId? = null,
      scientificName: String? = null,
      commonName: String? = null,
      isInvasive: Boolean = false,
      isThreatened: Boolean = false,
  ): BiomassSpeciesId {
    val row =
        ObservationBiomassSpeciesRow(
            observationId = observationId,
            speciesId = speciesId,
            scientificName = scientificName,
            commonName = commonName,
            isInvasive = isInvasive,
            isThreatened = isThreatened,
        )

    observationBiomassSpeciesDao.insert(row)

    return row.id!!.also { inserted.biomassSpeciesIds.add(it) }
  }

  fun insertObservationBiomassAdditionalSpecies(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      biomassSpeciesId: BiomassSpeciesId = inserted.biomassSpeciesId,
  ) {
    with(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES) {
      dslContext
          .insertInto(this)
          .set(OBSERVATION_ID, observationId)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .execute()
    }
  }

  fun insertObservationBiomassQuadratDetails(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      position: ObservationPlotPosition = ObservationPlotPosition.SouthwestCorner,
      description: String? = null,
  ) {
    val row =
        ObservationBiomassQuadratDetailsRow(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            positionId = position,
            description = description,
        )

    observationBiomassQuadratDetailsDao.insert(row)
  }

  fun insertObservationBiomassQuadratSpecies(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      position: ObservationPlotPosition = ObservationPlotPosition.SouthwestCorner,
      biomassSpeciesId: BiomassSpeciesId? = inserted.biomassSpeciesId,
      abundanceCount: Int = 0,
  ) {
    with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
      dslContext
          .insertInto(this)
          .set(OBSERVATION_ID, observationId)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(POSITION_ID, position)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(ABUNDANCE_COUNT, abundanceCount)
          .execute()
    }
  }

  fun insertObservationMediaFile(
      caption: String? = null,
      fileId: FileId = inserted.fileId,
      isOriginal: Boolean = true,
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      position: ObservationPlotPosition? = ObservationPlotPosition.SouthwestCorner,
      type: ObservationMediaType = ObservationMediaType.Plot,
  ) {
    val row =
        ObservationMediaFilesRow(
            caption = caption,
            fileId = fileId,
            isOriginal = isOriginal,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            positionId = position,
            typeId = type,
        )

    observationMediaFilesDao.insert(row)
  }

  fun insertOrganizationMediaFile(
      fileId: FileId = inserted.fileId,
      organizationId: OrganizationId = inserted.organizationId,
      caption: String? = null,
  ): FileId {
    dslContext
        .insertInto(ORGANIZATION_MEDIA_FILES)
        .set(ORGANIZATION_MEDIA_FILES.FILE_ID, fileId)
        .set(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID, organizationId)
        .set(ORGANIZATION_MEDIA_FILES.CAPTION, caption)
        .execute()
    return fileId
  }

  fun insertSplat(
      fileId: FileId = inserted.fileId,
      assetStatus: AssetStatus = AssetStatus.Ready,
      originPosition: CoordinateModel? = null,
      cameraPosition: CoordinateModel? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      organizationId: OrganizationId = inserted.organizationId,
      splatStorageUrl: URI = URI("s3://bucket/splat"),
      needsAttention: Boolean = false,
      groundColor: String? = null,
      groundPlane: List<CoordinateModel>? = null,
      sceneBounds: CoordinateModel? = null,
      skyColor: String? = null,
      averageCameraHeight: BigDecimal? = null,
  ) {
    with(SPLATS) {
      dslContext
          .insertInto(SPLATS)
          .set(ASSET_STATUS_ID, assetStatus)
          .set(CAMERA_POSITION, cameraPosition.toPointField())
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(FILE_ID, fileId)
          .set(GROUND_COLOR, groundColor)
          .set(GROUND_PLANE, groundPlane.toMultiPointField())
          .set(NEEDS_ATTENTION, needsAttention)
          .set(ORGANIZATION_ID, organizationId)
          .set(ORIGIN_POSITION, originPosition.toPointField())
          .set(SCENE_BOUNDS, sceneBounds.toPointField())
          .set(SKY_COLOR, skyColor)
          .set(AVERAGE_CAMERA_HEIGHT, averageCameraHeight)
          .set(SPLAT_STORAGE_URL, splatStorageUrl)
          .execute()
    }
  }

  fun insertSplatAdditionalFile(
      splatFileId: FileId = inserted.fileId,
      fileId: FileId = inserted.fileId,
      type: String = "frames",
  ) {
    with(SPLAT_ADDITIONAL_FILES) {
      dslContext
          .insertInto(SPLAT_ADDITIONAL_FILES)
          .set(SPLAT_FILE_ID, splatFileId)
          .set(FILE_ID, fileId)
          .set(TYPE, type)
          .execute()
    }
  }

  fun insertBirdnetResult(
      fileId: FileId = inserted.fileId,
      assetStatus: AssetStatus = AssetStatus.Ready,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      resultsStorageUrl: URI? = null,
      errorMessage: String? = null,
      completedTime: Instant? = null,
  ) {
    val row =
        BirdnetResultsRow(
            fileId = fileId,
            assetStatusId = assetStatus,
            createdBy = createdBy,
            createdTime = createdTime,
            resultsStorageUrl = resultsStorageUrl,
            errorMessage = errorMessage,
            completedTime = completedTime,
        )

    birdnetResultsDao.insert(row)
  }

  private val nextAnnotationNumber = mutableMapOf<FileId, Int>()

  fun insertSplatAnnotation(
      fileId: FileId = inserted.fileId,
      title: String = "Annotation ${nextAnnotationNumber.getOrDefault(fileId, 1)}",
      bodyText: String? = null,
      label: String? = null,
      position: CoordinateModel = CoordinateModel(1.0, 2.0, 3.0),
      cameraPosition: CoordinateModel? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
  ): SplatAnnotationId {
    val id =
        with(SPLAT_ANNOTATIONS) {
          dslContext
              .insertInto(SPLAT_ANNOTATIONS)
              .set(FILE_ID, fileId)
              .set(TITLE, title)
              .set(BODY_TEXT, bodyText)
              .set(LABEL, label)
              .set(POSITION, position.toPointField())
              .set(CAMERA_POSITION, cameraPosition.toPointField())
              .set(CREATED_BY, createdBy)
              .set(CREATED_TIME, createdTime)
              .set(MODIFIED_BY, modifiedBy)
              .set(MODIFIED_TIME, modifiedTime)
              .returning(ID)
              .fetchOne(ID)!!
        }

    nextAnnotationNumber[fileId] = nextAnnotationNumber.getOrDefault(fileId, 1) + 1

    return id.also { inserted.splatAnnotationIds.add(it) }
  }

  fun insertSplatAnnotationMedia(
      splatAnnotationId: SplatAnnotationId = inserted.splatAnnotationId,
      fileId: FileId = inserted.fileId,
      position: Int = 0,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
  ) {
    with(SPLAT_ANNOTATION_MEDIA) {
      dslContext
          .insertInto(SPLAT_ANNOTATION_MEDIA)
          .set(SPLAT_ANNOTATION_ID, splatAnnotationId)
          .set(FILE_ID, fileId)
          .set(POSITION, position)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .execute()
    }
  }

  fun insertObservationPlot(
      completedBy: UserId? = null,
      completedTime: Instant? = if (completedBy != null) clock.instant() else null,
      claimedBy: UserId? = completedBy,
      claimedTime: Instant? = if (claimedBy != null) clock.instant() else null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      isPermanent: Boolean = false,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      monitoringPlotHistoryId: MonitoringPlotHistoryId =
          inserted.monitoringPlotHistoryIdsByMonitoringPlotId[monitoringPlotId]?.last()
              ?: inserted.monitoringPlotHistoryId,
      observationId: ObservationId = inserted.observationId,
      notes: String? = null,
      observedTime: Instant? = null,
      statusId: ObservationPlotStatus =
          when {
            completedTime != null -> ObservationPlotStatus.Completed
            claimedTime != null -> ObservationPlotStatus.Claimed
            else -> ObservationPlotStatus.Unclaimed
          },
  ) {
    val row =
        ObservationPlotsRow(
            claimedBy = claimedBy,
            claimedTime = claimedTime,
            completedBy = completedBy,
            completedTime = completedTime,
            createdBy = createdBy,
            createdTime = createdTime,
            isPermanent = isPermanent,
            observationId = observationId,
            notes = notes,
            observedTime = observedTime,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            monitoringPlotHistoryId = monitoringPlotHistoryId,
            monitoringPlotId = monitoringPlotId,
            statusId = statusId,
        )

    observationPlotsDao.insert(row)
  }

  fun insertObservationPlotCondition(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      condition: ObservableCondition = ObservableCondition.AnimalDamage,
  ) {
    val row =
        ObservationPlotConditionsRow(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            conditionId = condition,
        )

    observationPlotConditionsDao.insert(row)
  }

  fun insertObservationPlotResult(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      monitoringPlotHistoryId: MonitoringPlotHistoryId =
          inserted.monitoringPlotHistoryIdsByMonitoringPlotId[monitoringPlotId]!!.last(),
      totalLive: Int = 0,
      totalDead: Int = 0,
      totalExisting: Int = 0,
      permanentLive: Int = 0,
      survivalRate: Int? = null,
      plantDensity: Int? = null,
      needsRecalculation: Boolean = false,
  ) {
    observationPlotResultsDao.insert(
        ObservationPlotResultsRow(
            observationId = observationId,
            monitoringPlotId = monitoringPlotId,
            monitoringPlotHistoryId = monitoringPlotHistoryId,
            totalLive = totalLive,
            totalDead = totalDead,
            totalExisting = totalExisting,
            permanentLive = permanentLive,
            survivalRate = survivalRate,
            plantDensity = plantDensity,
            needsRecalculation = needsRecalculation,
        )
    )
  }

  fun insertObservationRequestedSubstratum(
      observationId: ObservationId = inserted.observationId,
      substratumId: SubstratumId = inserted.substratumId,
  ) {
    observationRequestedSubstrataDao.insert(
        ObservationRequestedSubstrataRow(observationId, substratumId)
    )
  }

  protected fun insertObservationDependentSubstrata(
      observationId: ObservationId = inserted.observationId,
      substratumHistoryId: SubstratumHistoryId = inserted.substratumHistoryId,
      dependsOnObservationId: ObservationId = observationId,
      dependsOnSubstratumHistoryId: SubstratumHistoryId = substratumHistoryId,
  ) {
    observationDependentSubstrataDao.insert(
        ObservationDependentSubstrataRow(
            observationId = observationId,
            substratumHistoryId = substratumHistoryId,
            dependsOnObservationId = dependsOnObservationId,
            dependsOnSubstratumHistoryId = dependsOnSubstratumHistoryId,
        )
    )
  }

  fun insertObservationSiteResult(
      observationId: ObservationId = inserted.observationId,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId = inserted.plantingSiteHistoryId,
      totalLive: Int = 0,
      totalDead: Int = 0,
      totalExisting: Int = 0,
      permanentLive: Int = 0,
      survivalRate: Int? = null,
      survivalRateArea: Number? = null,
      survivalRateStdDev: Int? = null,
      plantDensity: Int? = null,
      plantDensityStdDev: Int? = null,
      needsRecalculation: Boolean = false,
  ) {
    observationSiteResultsDao.insert(
        ObservationSiteResultsRow(
            needsRecalculation = needsRecalculation,
            observationId = observationId,
            permanentLive = permanentLive,
            plantDensity = plantDensity,
            plantDensityStdDev = plantDensityStdDev,
            plantingSiteHistoryId = plantingSiteHistoryId,
            plantingSiteId = plantingSiteId,
            survivalRate = survivalRate,
            survivalRateArea = survivalRateArea?.toBigDecimal(),
            survivalRateStdDev = survivalRateStdDev,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
        )
    )
  }

  fun insertObservationStratumResult(
      observationId: ObservationId = inserted.observationId,
      stratumId: StratumId? = inserted.stratumId,
      stratumHistoryId: StratumHistoryId = inserted.stratumHistoryId,
      totalLive: Int = 0,
      totalDead: Int = 0,
      totalExisting: Int = 0,
      permanentLive: Int = 0,
      survivalRate: Int? = null,
      survivalRateArea: Number? = null,
      survivalRateStdDev: Int? = null,
      plantDensity: Int? = null,
      plantDensityStdDev: Int? = null,
      needsRecalculation: Boolean = false,
  ) {
    observationStratumResultsDao.insert(
        ObservationStratumResultsRow(
            needsRecalculation = needsRecalculation,
            observationId = observationId,
            permanentLive = permanentLive,
            plantDensity = plantDensity,
            plantDensityStdDev = plantDensityStdDev,
            stratumHistoryId = stratumHistoryId,
            stratumId = stratumId,
            survivalRate = survivalRate,
            survivalRateArea = survivalRateArea?.toBigDecimal(),
            survivalRateStdDev = survivalRateStdDev,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
        )
    )
  }

  fun insertObservationSubstratumResult(
      observationId: ObservationId = inserted.observationId,
      substratumId: SubstratumId? = inserted.substratumId,
      substratumHistoryId: SubstratumHistoryId = inserted.substratumHistoryId,
      totalLive: Int = 0,
      totalDead: Int = 0,
      totalExisting: Int = 0,
      permanentLive: Int = 0,
      survivalRate: Int? = null,
      survivalRateArea: Number? = null,
      survivalRateStdDev: Int? = null,
      plantDensity: Int? = null,
      plantDensityStdDev: Int? = null,
      needsRecalculation: Boolean = false,
  ) {
    observationSubstratumResultsDao.insert(
        ObservationSubstratumResultsRow(
            needsRecalculation = needsRecalculation,
            observationId = observationId,
            permanentLive = permanentLive,
            plantDensity = plantDensity,
            plantDensityStdDev = plantDensityStdDev,
            substratumHistoryId = substratumHistoryId,
            substratumId = substratumId,
            survivalRate = survivalRate,
            survivalRateArea = survivalRateArea?.toBigDecimal(),
            survivalRateStdDev = survivalRateStdDev,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
        )
    )
  }

  fun insertObservedCoordinates(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      gpsCoordinates: Point = point(1),
      position: ObservationPlotPosition = ObservationPlotPosition.NortheastCorner,
  ): ObservedPlotCoordinatesId {
    val row =
        ObservedPlotCoordinatesRow(
            gpsCoordinates = gpsCoordinates,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            positionId = position,
        )

    observedPlotCoordinatesDao.insert(row)

    return row.id!!
  }

  fun insertObservedPlotSpeciesTotals(
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      monitoringPlotHistoryId: MonitoringPlotHistoryId =
          inserted.monitoringPlotHistoryIdsByMonitoringPlotId[monitoringPlotId]!!.last(),
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
      permanentLive: Int = 0,
      survivalRate: Int? = null,
      biomassSpeciesId: BiomassSpeciesId? = null,
  ) {
    with(OBSERVED_PLOT_SPECIES_TOTALS) {
      dslContext
          .insertInto(this)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(OBSERVATION_ID, observationId)
          .set(MONITORING_PLOT_HISTORY_ID, monitoringPlotHistoryId)
          .set(MONITORING_PLOT_ID, monitoringPlotId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(PERMANENT_LIVE, permanentLive)
          .set(SURVIVAL_RATE, survivalRate)
          .execute()
    }
  }

  fun insertObservedSubstratumSpeciesTotals(
      observationId: ObservationId = inserted.observationId,
      substratumId: SubstratumId? = inserted.substratumId,
      substratumHistoryId: SubstratumHistoryId =
          substratumId?.let { inserted.substratumHistoryIdsBySubstratumId[it]?.last() }
              ?: inserted.substratumHistoryId,
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
      permanentLive: Int = 0,
      biomassSpeciesId: BiomassSpeciesId? = null,
  ) {
    with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
      dslContext
          .insertInto(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(OBSERVATION_ID, observationId)
          .set(SUBSTRATUM_HISTORY_ID, substratumHistoryId)
          .set(SUBSTRATUM_ID, substratumId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(PERMANENT_LIVE, permanentLive)
          .execute()
    }
  }

  fun insertObservedStratumSpeciesTotals(
      observationId: ObservationId = inserted.observationId,
      stratumId: StratumId? = inserted.stratumId,
      stratumHistoryId: StratumHistoryId =
          stratumId?.let { inserted.stratumHistoryIdsByStratumId[it]?.last() }
              ?: inserted.stratumHistoryId,
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
      permanentLive: Int = 0,
      biomassSpeciesId: BiomassSpeciesId? = null,
  ) {
    with(OBSERVED_STRATUM_SPECIES_TOTALS) {
      dslContext
          .insertInto(OBSERVED_STRATUM_SPECIES_TOTALS)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(OBSERVATION_ID, observationId)
          .set(STRATUM_HISTORY_ID, stratumHistoryId)
          .set(STRATUM_ID, stratumId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(PERMANENT_LIVE, permanentLive)
          .execute()
    }
  }

  fun insertObservedSiteSpeciesTotals(
      observationId: ObservationId = inserted.observationId,
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId,
      plantingSiteHistoryId: PlantingSiteHistoryId =
          inserted.plantingSiteHistoryIdsByPlantingSiteId[plantingSiteId]?.last()
              ?: inserted.plantingSiteHistoryId,
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
      permanentLive: Int = 0,
      biomassSpeciesId: BiomassSpeciesId? = null,
  ) {
    with(OBSERVED_SITE_SPECIES_TOTALS) {
      dslContext
          .insertInto(OBSERVED_SITE_SPECIES_TOTALS)
          .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(OBSERVATION_ID, observationId)
          .set(PLANTING_SITE_HISTORY_ID, plantingSiteHistoryId)
          .set(PLANTING_SITE_ID, plantingSiteId)
          .set(CERTAINTY_ID, certainty)
          .set(SPECIES_ID, speciesId)
          .set(SPECIES_NAME, speciesName)
          .set(TOTAL_LIVE, totalLive)
          .set(TOTAL_DEAD, totalDead)
          .set(TOTAL_EXISTING, totalExisting)
          .set(PERMANENT_LIVE, permanentLive)
          .execute()
    }
  }

  fun insertStratumT0TempDensity(
      stratumId: StratumId = inserted.stratumId,
      speciesId: SpeciesId = inserted.speciesId,
      stratumDensity: BigDecimal = BigDecimal.valueOf(10),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
  ) {
    with(STRATUM_T0_TEMP_DENSITIES) {
      dslContext
          .insertInto(this)
          .set(STRATUM_ID, stratumId)
          .set(SPECIES_ID, speciesId)
          .set(STRATUM_DENSITY, stratumDensity)
          .set(CREATED_BY, createdBy)
          .set(CREATED_TIME, createdTime)
          .set(MODIFIED_BY, modifiedBy)
          .set(MODIFIED_TIME, modifiedTime)
          .execute()
    }
  }

  fun insertPlotT0Density(
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      speciesId: SpeciesId = inserted.speciesId,
      plotDensity: BigDecimal = BigDecimal.valueOf(10),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
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
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      observationId: ObservationId = inserted.observationId,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
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
      observationId: ObservationId = inserted.observationId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      biomassSpeciesId: BiomassSpeciesId? = inserted.biomassSpeciesId,
      treeNumber: Int? = null,
      trunkNumber: Int? = null,
      treeGrowthForm: TreeGrowthForm = TreeGrowthForm.Tree,
      isDead: Boolean = false,
      boleHeightM: BigDecimal? = null,
      diameterAtBreastHeightCm: BigDecimal? = null,
      pointOfMeasurementM: BigDecimal? = null,
      heightM: BigDecimal? = null,
      shrubDiameterCm: Int? = null,
      treeCrownDiameterCm: Int? = null,
      description: String? = null,
  ): RecordedTreeId {
    val effectiveTreeNumber = treeNumber ?: nextTreeNumber.getOrDefault(observationId, 1)
    val effectiveTrunkNumber =
        trunkNumber ?: nextTrunkNumber.getOrDefault(observationId to effectiveTreeNumber, 1)
    val row =
        RecordedTreesRow(
            biomassSpeciesId = biomassSpeciesId,
            boleHeightM = boleHeightM,
            description = description,
            diameterAtBreastHeightCm = diameterAtBreastHeightCm,
            heightM = heightM,
            isDead = isDead,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            pointOfMeasurementM = pointOfMeasurementM,
            shrubDiameterCm = shrubDiameterCm,
            treeCrownDiameterCm = treeCrownDiameterCm,
            treeGrowthFormId = treeGrowthForm,
            treeNumber = effectiveTreeNumber,
            trunkNumber = effectiveTrunkNumber,
        )

    recordedTreesDao.insert(row)
    nextTreeNumber[observationId] = effectiveTreeNumber + 1
    nextTrunkNumber[observationId to effectiveTreeNumber] = effectiveTrunkNumber + 1

    return row.id!!.also { inserted.recordedTreeIds.add(it) }
  }

  fun insertRecordedPlant(
      gpsCoordinates: Point = point(1),
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      observationId: ObservationId = inserted.observationId,
      speciesId: SpeciesId? = null,
      speciesName: String? = null,
      certainty: RecordedSpeciesCertainty =
          when {
            speciesId != null -> RecordedSpeciesCertainty.Known
            speciesName != null -> RecordedSpeciesCertainty.Other
            else -> RecordedSpeciesCertainty.Unknown
          },
      status: RecordedPlantStatus = RecordedPlantStatus.Live,
  ): RecordedPlantId {
    val row =
        RecordedPlantsRow(
            certaintyId = certainty,
            gpsCoordinates = gpsCoordinates,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            speciesId = speciesId,
            speciesName = speciesName,
            statusId = status,
        )

    recordedPlantsDao.insert(row)

    return row.id!!.also { inserted.recordedPlantIds.add(it) }
  }

  protected fun insertReport(
      configId: ProjectReportConfigId = inserted.projectReportConfigId,
      projectId: ProjectId = inserted.projectId,
      quarter: ReportQuarter? = ReportQuarter.Q1,
      status: ReportStatus = ReportStatus.NotSubmitted,
      startDate: LocalDate = LocalDate.EPOCH,
      endDate: LocalDate = LocalDate.EPOCH.plusDays(1),
      highlights: String? = null,
      internalComment: String? = null,
      feedback: String? = null,
      additionalComments: String? = null,
      financialSummaries: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
      submittedBy: UserId? =
          if (status != ReportStatus.NotSubmitted && status != ReportStatus.NotNeeded) {
            inserted.userId
          } else {
            null
          },
      submittedTime: Instant? = submittedBy?.let { clock.instant() },
      upcomingNotificationSentTime: Instant? = null,
  ): ReportId {
    val row =
        ReportsRow(
            configId = configId,
            projectId = projectId,
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

    reportsDao.insert(row)

    return row.id!!.also { inserted.reportIds.add(it) }
  }

  private val nextReportAchievementPosition = mutableMapOf<ReportId, Int>()

  protected fun insertReportAchievement(
      reportId: ReportId = inserted.reportId,
      position: Int = nextReportAchievementPosition.getOrDefault(reportId, 0),
      achievement: String = "Achievement $position",
  ) {
    val row =
        ReportAchievementsRow(
            reportId = reportId,
            position = position,
            achievement = achievement,
        )

    reportAchievementsDao.insert(row)

    nextReportAchievementPosition[reportId] = position + 1
  }

  private val nextReportChallengePosition = mutableMapOf<ReportId, Int>()

  protected fun insertReportChallenge(
      reportId: ReportId = inserted.reportId,
      position: Int = nextReportChallengePosition.getOrDefault(reportId, 0),
      challenge: String = "Challenge $position",
      mitigationPlan: String = "Mitigation Plan $position",
  ) {
    val row =
        ReportChallengesRow(
            reportId = reportId,
            position = position,
            challenge = challenge,
            mitigationPlan = mitigationPlan,
        )

    reportChallengesDao.insert(row)

    nextReportChallengePosition[reportId] = position + 1
  }

  protected fun insertReportPhoto(
      reportId: ReportId = inserted.reportId,
      fileId: FileId = inserted.fileId,
      caption: String? = null,
      deleted: Boolean = false,
  ) {
    val row =
        ReportPhotosRow(
            reportId = reportId,
            fileId = fileId,
            caption = caption,
            deleted = deleted,
        )

    reportPhotosDao.insert(row)
  }

  protected fun insertReportProjectIndicator(
      reportId: ReportId = inserted.reportId,
      indicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      value: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
  ) {
    val row =
        ReportProjectIndicatorsRow(
            reportId = reportId,
            projectIndicatorId = indicatorId,
            value = value?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportProjectIndicatorsDao.insert(row)
  }

  protected fun insertReportCommonIndicator(
      reportId: ReportId = inserted.reportId,
      indicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      value: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
  ) {
    val row =
        ReportCommonIndicatorsRow(
            reportId = reportId,
            commonIndicatorId = indicatorId,
            value = value?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportCommonIndicatorsDao.insert(row)
  }

  protected fun insertReportAutoCalculatedIndicator(
      reportId: ReportId = inserted.reportId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.SeedsCollected,
      systemValue: Number? = null,
      systemTime: Instant? = systemValue?.let { clock.instant() },
      overrideValue: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
  ) {
    val row =
        ReportAutoCalculatedIndicatorsRow(
            reportId = reportId,
            autoCalculatedIndicatorId = indicator,
            systemValue = systemValue?.toBigDecimal(),
            systemTime = systemTime,
            overrideValue = overrideValue?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
            modifiedBy = modifiedBy,
            modifiedTime = modifiedTime,
        )

    reportAutoCalculatedIndicatorsDao.insert(row)
  }

  protected fun insertReportProjectIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      projectIndicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      year: Int = 1970,
      target: Number? = null,
  ) {
    val row =
        ReportProjectIndicatorTargetsRow(
            projectId = projectId,
            projectIndicatorId = projectIndicatorId,
            year = year,
            target = target?.toBigDecimal(),
        )

    reportProjectIndicatorTargetsDao.insert(row)
  }

  protected fun insertReportCommonIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      commonIndicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      year: Int = 1970,
      target: Number? = null,
  ) {
    val row =
        ReportCommonIndicatorTargetsRow(
            projectId = projectId,
            commonIndicatorId = commonIndicatorId,
            year = year,
            target = target?.toBigDecimal(),
        )

    reportCommonIndicatorTargetsDao.insert(row)
  }

  protected fun insertReportAutoCalculatedIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.SeedsCollected,
      year: Int = 1970,
      target: Number? = null,
  ) {
    val row =
        ReportAutoCalculatedIndicatorTargetsRow(
            projectId = projectId,
            autoCalculatedIndicatorId = indicator,
            year = year,
            target = target?.toBigDecimal(),
        )

    reportAutoCalculatedIndicatorTargetsDao.insert(row)
  }

  protected fun insertPublishedProjectIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      projectIndicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      year: Int = 2025,
      target: Number? = null,
  ) {
    val row =
        PublishedProjectIndicatorTargetsRow(
            projectId = projectId,
            projectIndicatorId = projectIndicatorId,
            year = year,
            target = target?.toBigDecimal(),
        )

    publishedProjectIndicatorTargetsDao.insert(row)
  }

  protected fun insertPublishedCommonIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      commonIndicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      year: Int = 2025,
      target: Number? = null,
  ) {
    val row =
        PublishedCommonIndicatorTargetsRow(
            projectId = projectId,
            commonIndicatorId = commonIndicatorId,
            year = year,
            target = target?.toBigDecimal(),
        )

    publishedCommonIndicatorTargetsDao.insert(row)
  }

  protected fun insertPublishedAutoCalculatedIndicatorTarget(
      projectId: ProjectId = inserted.projectId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.SeedsCollected,
      year: Int = 2025,
      target: Number? = null,
  ) {
    val row =
        PublishedAutoCalculatedIndicatorTargetsRow(
            projectId = projectId,
            autoCalculatedIndicatorId = indicator,
            year = year,
            target = target?.toBigDecimal(),
        )

    publishedAutoCalculatedIndicatorTargetsDao.insert(row)
  }

  protected fun insertProjectIndicatorBaselineTarget(
      projectId: ProjectId = inserted.projectId,
      projectIndicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        ProjectIndicatorTargetsRow(
            projectId = projectId,
            projectIndicatorId = projectIndicatorId,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    projectIndicatorTargetsDao.insert(row)
  }

  protected fun insertCommonIndicatorBaselineTarget(
      projectId: ProjectId = inserted.projectId,
      commonIndicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        CommonIndicatorTargetsRow(
            projectId = projectId,
            commonIndicatorId = commonIndicatorId,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    commonIndicatorTargetsDao.insert(row)
  }

  protected fun insertAutoCalculatedIndicatorBaselineTarget(
      projectId: ProjectId = inserted.projectId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.SeedsCollected,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        AutoCalculatedIndicatorTargetsRow(
            projectId = projectId,
            autoCalculatedIndicatorId = indicator,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    autoCalculatedIndicatorTargetsDao.insert(row)
  }

  protected fun insertPublishedProjectIndicatorBaseline(
      projectId: ProjectId = inserted.projectId,
      projectIndicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        PublishedProjectIndicatorBaselinesRow(
            projectId = projectId,
            projectIndicatorId = projectIndicatorId,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    publishedProjectIndicatorBaselinesDao.insert(row)
  }

  protected fun insertPublishedCommonIndicatorBaseline(
      projectId: ProjectId = inserted.projectId,
      commonIndicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        PublishedCommonIndicatorBaselinesRow(
            projectId = projectId,
            commonIndicatorId = commonIndicatorId,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    publishedCommonIndicatorBaselinesDao.insert(row)
  }

  protected fun insertPublishedAutoCalculatedIndicatorBaseline(
      projectId: ProjectId = inserted.projectId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.SeedsCollected,
      baseline: Number? = null,
      endTarget: Number? = null,
  ) {
    val row =
        PublishedAutoCalculatedIndicatorBaselinesRow(
            projectId = projectId,
            autoCalculatedIndicatorId = indicator,
            baseline = baseline?.toBigDecimal(),
            endTarget = endTarget?.toBigDecimal(),
        )

    publishedAutoCalculatedIndicatorBaselinesDao.insert(row)
  }

  protected fun insertPublishedReport(
      reportId: ReportId = inserted.reportId,
      projectId: ProjectId = inserted.projectId,
      startDate: LocalDate = LocalDate.of(2025, 1, 1),
      endDate: LocalDate = LocalDate.of(2025, 3, 31),
      quarter: ReportQuarter? = ReportQuarter.Q1,
      publishedBy: UserId = inserted.userId,
      publishedTime: Instant = clock.instant(),
      additionalComments: String? = null,
      financialSummaries: String? = null,
      highlights: String? = null,
  ) {
    with(PUBLISHED_REPORTS) {
      dslContext
          .insertInto(PUBLISHED_REPORTS)
          .set(REPORT_ID, reportId)
          .set(PROJECT_ID, projectId)
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
      reportId: ReportId = inserted.reportId,
      fileId: FileId = inserted.fileId,
      caption: String? = null,
  ) {
    val row =
        PublishedReportPhotosRow(
            reportId = reportId,
            fileId = fileId,
            caption = caption,
        )

    publishedReportPhotosDao.insert(row)
  }

  protected fun insertPublishedReportProjectIndicator(
      reportId: ReportId = inserted.reportId,
      indicatorId: ProjectIndicatorId = inserted.projectIndicatorId,
      value: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
  ) {
    val row =
        PublishedReportProjectIndicatorsRow(
            reportId = reportId,
            projectIndicatorId = indicatorId,
            value = value?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
        )

    publishedReportProjectIndicatorsDao.insert(row)
  }

  protected fun insertPublishedReportCommonIndicator(
      reportId: ReportId = inserted.reportId,
      indicatorId: CommonIndicatorId = inserted.commonIndicatorId,
      value: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
  ) {
    val row =
        PublishedReportCommonIndicatorsRow(
            reportId = reportId,
            commonIndicatorId = indicatorId,
            value = value?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
        )

    publishedReportCommonIndicatorsDao.insert(row)
  }

  protected fun insertPublishedReportAutoCalculatedIndicator(
      reportId: ReportId = inserted.reportId,
      indicator: AutoCalculatedIndicator = AutoCalculatedIndicator.Seedlings,
      value: Number? = null,
      projectsComments: String? = null,
      progressNotes: String? = null,
      status: ReportIndicatorStatus? = null,
      supportingDocumentUrl: URI? = null,
  ) {
    val row =
        PublishedReportAutoCalculatedIndicatorsRow(
            reportId = reportId,
            autoCalculatedIndicatorId = indicator,
            value = value?.toBigDecimal(),
            projectsComments = projectsComments,
            progressNotes = progressNotes,
            statusId = status,
            supportingDocumentUrl = supportingDocumentUrl,
        )

    publishedReportAutoCalculatedIndicatorsDao.insert(row)
  }

  protected fun insertActivity(
      activityDate: LocalDate = LocalDate.EPOCH,
      activityType: ActivityType = ActivityType.Planting,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      description: String? = "Activity",
      isHighlight: Boolean = false,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = createdTime,
      projectId: ProjectId = inserted.projectId,
      verifiedBy: UserId? = null,
      verifiedTime: Instant? = if (verifiedBy != null) clock.instant() else null,
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

  protected fun insertActivityObservation(
      activityId: ActivityId = inserted.activityId,
      observationId: ObservationId = inserted.observationId,
  ) {
    with(ACTIVITY_OBSERVATIONS) {
      dslContext
          .insertInto(this)
          .set(ACTIVITY_ID, activityId)
          .set(OBSERVATION_ID, observationId)
          .execute()
    }
  }

  protected fun insertPublishedActivity(
      activityId: ActivityId = inserted.activityId,
      activityDate: LocalDate = LocalDate.EPOCH,
      activityType: ActivityType = ActivityType.Planting,
      description: String = "Activity",
      isHighlight: Boolean = false,
      projectId: ProjectId = inserted.projectId,
      publishedBy: UserId = inserted.userId,
      publishedTime: Instant = clock.instant(),
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

  protected fun insertPublishedActivityObservation(
      activityId: ActivityId = inserted.activityId,
      observationId: ObservationId = inserted.observationId,
      livePlants: Int? = 100,
      plantDensity: Int? = 20,
      survivalRate: Int? = 90,
  ) {
    val row =
        PublishedActivityObservationsRow(
            activityId = activityId,
            observationId = observationId,
            livePlants = livePlants,
            plantDensity = plantDensity,
            survivalRate = survivalRate,
        )

    publishedActivityObservationsDao.insert(row)
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

  protected fun insertPublishedActivityObservationMediaFile(
      activityId: ActivityId = inserted.activityId,
      fileId: FileId = inserted.fileId,
      monitoringPlotId: MonitoringPlotId = inserted.monitoringPlotId,
      position: ObservationPlotPosition? = ObservationPlotPosition.SouthwestCorner,
      type: ObservationMediaType = ObservationMediaType.Plot,
  ) {
    val row =
        PublishedActivityObservationMediaFilesRow(
            activityId = activityId,
            fileId = fileId,
            monitoringPlotId = monitoringPlotId,
            positionId = position,
            typeId = type,
        )

    publishedActivityObservationMediaFilesDao.insert(row)
  }

  private var nextInternalTagNumber = 1

  protected fun insertInternalTag(
      name: String = "Tag ${nextInternalTagNumber++}",
      description: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      modifiedBy: UserId = inserted.userId,
      modifiedTime: Instant = clock.instant(),
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

  fun insertParticipantProjectSpecies(
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
      feedback: String? = null,
      internalComment: String? = null,
      modifiedBy: UserId = createdBy,
      modifiedTime: Instant = clock.instant(),
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

  private val nextProjectModuleStartDates = mutableMapOf<ProjectId, LocalDate>()

  fun insertProjectModule(
      projectId: ProjectId = inserted.projectId,
      moduleId: ModuleId = inserted.moduleId,
      title: String = "Module 1",
      startDate: LocalDate = nextProjectModuleStartDates.getOrPut(projectId) { LocalDate.EPOCH },
      endDate: LocalDate = startDate.plusDays(6),
  ) {
    val row =
        ProjectModulesRow(
            endDate = endDate,
            moduleId = moduleId,
            projectId = projectId,
            startDate = startDate,
            title = title,
        )

    nextProjectModuleStartDates[projectId] = endDate.plusDays(1)
    projectModulesDao.insert(row)
  }

  fun insertEvent(
      moduleId: ModuleId = inserted.moduleId,
      eventStatus: EventStatus = EventStatus.NotStarted,
      eventType: EventType = EventType.Workshop,
      meetingUrl: Any? = null,
      slidesUrl: Any? = null,
      recordingUrl: Any? = null,
      revision: Int = 1,
      startTime: Instant = clock.instant().plusSeconds(3600),
      endTime: Instant = startTime.plusSeconds(3600),
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      phase: AcceleratorPhase = AcceleratorPhase.Phase0DueDiligence,
      user: UserId = inserted.userId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      phase: AcceleratorPhase = AcceleratorPhase.Phase0DueDiligence,
      voteOption: VoteOption? = null,
      modifiedTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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
      createdBy: UserId = inserted.userId,
      createdTime: Instant = clock.instant(),
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

  private var nextBotanicalCountryLevel3Code = 1

  fun insertBotanicalCountry(
      boundary: Geometry = rectangle(1),
      level1Code: Int = 3,
      level2Code: Int = 555,
      level3Code: String = "${nextBotanicalCountryLevel3Code++}",
      name: String = "country",
  ): String {
    val record =
        BotanicalCountriesRecord(
                boundary = boundary,
                level1Code = level1Code,
                level2Code = level2Code,
                level3Code = level3Code,
                name = name,
            )
            .attach(dslContext)

    record.insert()

    inserted.botanicalCountryCodes.add(level3Code)

    return level3Code
  }

  fun insertCountryBotanicalCountry(
      botanicalCountryCode: String = inserted.botanicalCountryCode,
      countryCode: String = "US",
  ) {
    CountryBotanicalCountriesRecord(countryCode, botanicalCountryCode).attach(dslContext).insert()
  }

  fun insertGbifTaxon(
      scientificName: String,
      commonNames: Collection<Pair<String, String?>> = emptyList(),
      familyName: String = scientificName.substringBefore(' '),
      threatStatus: String? = null,
      fullScientificName: String = scientificName,
      acceptedNameUsageId: GbifTaxonId? = null,
      taxonomicStatus: String = GbifStore.TAXONOMIC_STATUS_ACCEPTED,
  ): GbifTaxonId {
    val taxonId = GbifTaxonId(nextGbifTaxonId.getAndIncrement())

    fun insertName(name: String, isScientific: Boolean) {
      val nameId =
          dslContext
              .insertInto(GBIF_NAMES)
              .set(GBIF_NAMES.TAXON_ID, taxonId)
              .set(GBIF_NAMES.NAME, name)
              .set(GBIF_NAMES.IS_SCIENTIFIC, isScientific)
              .returning(GBIF_NAMES.ID)
              .fetchOne(GBIF_NAMES.ID)!!

      name.split(' ').forEach { word ->
        dslContext
            .insertInto(GBIF_NAME_WORDS)
            .set(GBIF_NAME_WORDS.GBIF_NAME_ID, nameId)
            .set(GBIF_NAME_WORDS.WORD, word.removeDiacritics().lowercase())
            .execute()
      }
    }

    dslContext
        .insertInto(GBIF_TAXA)
        .set(GBIF_TAXA.TAXON_ID, taxonId)
        .set(GBIF_TAXA.SCIENTIFIC_NAME, fullScientificName)
        .set(GBIF_TAXA.FAMILY, familyName)
        .set(GBIF_TAXA.TAXON_RANK, "species")
        .set(GBIF_TAXA.TAXONOMIC_STATUS, taxonomicStatus)
        .set(GBIF_TAXA.ACCEPTED_NAME_USAGE_ID, acceptedNameUsageId)
        .execute()

    insertName(scientificName, true)

    commonNames.forEach { (name, language) ->
      dslContext
          .insertInto(GBIF_VERNACULAR_NAMES)
          .set(GBIF_VERNACULAR_NAMES.TAXON_ID, taxonId)
          .set(GBIF_VERNACULAR_NAMES.VERNACULAR_NAME, name)
          .set(GBIF_VERNACULAR_NAMES.LANGUAGE, language)
          .execute()

      insertName(name, false)
    }

    if (threatStatus != null) {
      dslContext
          .insertInto(GBIF_DISTRIBUTIONS)
          .set(GBIF_DISTRIBUTIONS.TAXON_ID, taxonId)
          .set(GBIF_DISTRIBUTIONS.THREAT_STATUS, threatStatus)
          .execute()
    }

    return taxonId
  }

  private var nextGriisResourceSuffix: Int = 1

  fun insertGriisResource(
      resourceName: String = "resource${nextGriisResourceSuffix++}",
      countryCode: String = "US",
      publicationDate: LocalDate = LocalDate.EPOCH,
      updatedTime: Instant = clock.instant(),
  ): GriisResourceId {
    val record =
        GriisResourcesRecord(
                id = null,
                resourceName = resourceName,
                countryCode = countryCode,
                publicationDate = publicationDate,
                updatedTime = updatedTime,
            )
            .attach(dslContext)

    record.insert()

    return record.id!!.also { inserted.griisResourceIds.add(it) }
  }

  private var nextGriisTaxonId: Long = 1

  fun insertGriisTaxon(
      acceptedNameUsage: String? = null,
      establishmentMeans: String? = null,
      griisResourceId: GriisResourceId = inserted.griisResourceId,
      habitat: String? = null,
      isInvasive: Boolean = false,
      occurrenceStatus: String? = "present",
      scientificName: String = "Species name",
      speciesNativity: SpeciesNativity =
          if (isInvasive) SpeciesNativity.Invasive else SpeciesNativity.Unknown,
      taxonId: Long = nextGriisTaxonId++,
      taxonomicStatus: String = "accepted",
      taxonRank: String = "species",
  ) {
    GriisTaxaRecord(
            acceptedNameUsage = acceptedNameUsage,
            establishmentMeans = establishmentMeans,
            griisResourceId = griisResourceId,
            habitat = habitat,
            isInvasive = isInvasive,
            occurrenceStatus = occurrenceStatus,
            scientificName = scientificName,
            speciesNativityId = speciesNativity,
            taxonId = taxonId,
            taxonomicStatus = taxonomicStatus,
            taxonRank = taxonRank,
        )
        .attach(dslContext)
        .insert()
  }

  private var nextWcvpTaxonId: Long = 1

  fun insertWcvpTaxon(
      taxonId: Any = nextWcvpTaxonId++,
      acceptedNameUsageId: Any? = null,
      family: String = "family",
      genus: String = "genus",
      infraspecificEpithet: String? = "infra",
      originalNameUsageId: Any? = null,
      parentNameUsageId: Any? = null,
      scientificName: String = "Scientific name",
      specificEpithet: String? = "specific",
      taxonRank: String = "Species",
      taxonomicStatus: String = "Accepted",
  ): WcvpTaxonId {
    val taxonIdWrapper = WcvpTaxonId("$taxonId")

    WcvpTaxaRecord(
            acceptedNameUsageId = acceptedNameUsageId?.let { WcvpTaxonId("$it") },
            family = family,
            genus = genus,
            infraspecificEpithet = infraspecificEpithet,
            originalNameUsageId = originalNameUsageId?.let { WcvpTaxonId("$it") },
            parentNameUsageId = parentNameUsageId?.let { WcvpTaxonId("$it") },
            scientificName = scientificName,
            specificEpithet = specificEpithet,
            taxonId = taxonIdWrapper,
            taxonomicStatus = taxonomicStatus,
            taxonRank = taxonRank,
        )
        .attach(dslContext)
        .insert()

    return taxonIdWrapper.also { inserted.wcvpTaxonIds.add(it) }
  }

  fun insertWcvpDistribution(
      botanicalCountryCode: String? = null,
      establishmentMeans: String? = null,
      level3Code: String = "COD",
      occurrenceStatus: String? = null,
      speciesNativity: SpeciesNativity = SpeciesNativity.Introduced,
      taxonId: WcvpTaxonId = inserted.wcvpTaxonId,
      threatStatus: String? = null,
  ) {
    WcvpDistributionsRecord(
            botanicalCountryCode = botanicalCountryCode,
            establishmentMeans = establishmentMeans,
            level3Code = level3Code,
            occurrenceStatus = occurrenceStatus,
            speciesNativityId = speciesNativity,
            taxonId = taxonId,
            threatStatus = threatStatus,
        )
        .attach(dslContext)
        .insert()
  }

  fun insertExternalDatasetImport(
      type: ExternalDatasetType = ExternalDatasetType.GRIIS,
      importedTime: Instant = clock.instant(),
      lastPublicationDate: LocalDate? = LocalDate.of(2026, 1, 1),
  ) {
    ExternalDatasetImportsRecord(
            externalDatasetTypeId = type,
            importedTime = importedTime,
            lastPublicationDate = lastPublicationDate,
        )
        .attach(dslContext)
        .insert()
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

  protected fun clearCachedPermissions(updatedUserId: UserId = inserted.userId) {
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

  companion object {
    init {
      // Work around non-thread-safe initialization of jOOQ constants by evaluating one symbol
      // from the generated Keys.kt file for each schema.
      ACCESSION_PKEY
      BATCHES_PKEY
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

    /**
     * Taxon IDs are defined in the GBIF dataset, not allocated locally by the database, but they
     * are required to be unique in our data model. Use taxon IDs that are unique across threads to
     * avoid database deadlocks if multiple of these tests are running concurrently and try to
     * insert the same taxon ID at the same time.
     *
     * This starts at a high number to avoid colliding with the taxon IDs in [GbifImporterTest].
     */
    val nextGbifTaxonId = AtomicLong(1000000)
  }
}
