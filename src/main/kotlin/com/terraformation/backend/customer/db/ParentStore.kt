package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.accelerator.tables.references.APPLICATION_MODULES
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.NOTIFICATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_PROJECTS
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import jakarta.inject.Named
import java.time.ZoneId
import java.time.ZoneOffset
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Lookup methods to get the IDs of the parents of various objects.
 *
 * This is mostly called by [IndividualUser] to evaluate permissions on child objects in cases where
 * the children inherit permissions from parents. Putting all these lookups in one place reduces the
 * number of dependencies in [IndividualUser], and also gives us a clean place to introduce caching
 * if parent ID lookups in permission checks become a performance bottleneck.
 */
@Named
class ParentStore(private val dslContext: DSLContext) {
  fun getFacilityId(accessionId: AccessionId): FacilityId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.FACILITY_ID)

  fun getFacilityId(automationId: AutomationId): FacilityId? =
      fetchFieldById(automationId, AUTOMATIONS.ID, AUTOMATIONS.FACILITY_ID)

  fun getFacilityId(batchId: BatchId): FacilityId? =
      fetchFieldById(batchId, BATCHES.ID, BATCHES.FACILITY_ID)

  fun getFacilityId(deviceManagerId: DeviceManagerId): FacilityId? =
      fetchFieldById(deviceManagerId, DEVICE_MANAGERS.ID, DEVICE_MANAGERS.FACILITY_ID)

  fun getFacilityId(deviceId: DeviceId): FacilityId? =
      fetchFieldById(deviceId, DEVICES.ID, DEVICES.FACILITY_ID)

  fun getFacilityId(subLocationId: SubLocationId): FacilityId? =
      fetchFieldById(subLocationId, SUB_LOCATIONS.ID, SUB_LOCATIONS.FACILITY_ID)

  fun getFacilityId(viabilityTestId: ViabilityTestId): FacilityId? =
      fetchFieldById(viabilityTestId, VIABILITY_TESTS.ID, VIABILITY_TESTS.accessions.FACILITY_ID)

  fun getFacilityId(withdrawalId: WithdrawalId): FacilityId? =
      fetchFieldById(withdrawalId, WITHDRAWALS.ID, WITHDRAWALS.FACILITY_ID)

  fun getFundingEntityIds(projectId: ProjectId): List<FundingEntityId> =
      dslContext
          .select(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID)
          .from(FUNDING_ENTITY_PROJECTS)
          .where(FUNDING_ENTITY_PROJECTS.PROJECT_ID.eq(projectId))
          .orderBy(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID)
          .fetch(FUNDING_ENTITY_PROJECTS.FUNDING_ENTITY_ID.asNonNullable())

  fun getOrganizationId(accessionId: AccessionId): OrganizationId? {
    return fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.facilities.ORGANIZATION_ID)
  }

  fun getOrganizationId(activityId: ActivityId): OrganizationId? {
    return fetchFieldById(activityId, ACTIVITIES.ID, ACTIVITIES.projects.ORGANIZATION_ID)
  }

  fun getOrganizationId(applicationId: ApplicationId): OrganizationId? =
      fetchFieldById(applicationId, APPLICATIONS.ID, APPLICATIONS.projects.ORGANIZATION_ID)

  fun getOrganizationId(batchId: BatchId): OrganizationId? =
      fetchFieldById(batchId, BATCHES.ID, BATCHES.ORGANIZATION_ID)

  fun getOrganizationId(deliveryId: DeliveryId): OrganizationId? =
      fetchFieldById(deliveryId, DELIVERIES.ID, DELIVERIES.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(deviceManagerId: DeviceManagerId): OrganizationId? =
      fetchFieldById(
          deviceManagerId,
          DEVICE_MANAGERS.ID,
          DEVICE_MANAGERS.facilities.ORGANIZATION_ID,
      )

  fun getOrganizationId(draftPlantingSiteId: DraftPlantingSiteId): OrganizationId? =
      fetchFieldById(
          draftPlantingSiteId,
          DRAFT_PLANTING_SITES.ID,
          DRAFT_PLANTING_SITES.ORGANIZATION_ID,
      )

  fun getOrganizationId(facilityId: FacilityId): OrganizationId? =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.ORGANIZATION_ID)

  fun getOrganizationId(monitoringPlotId: MonitoringPlotId): OrganizationId? =
      fetchFieldById(monitoringPlotId, MONITORING_PLOTS.ID, MONITORING_PLOTS.ORGANIZATION_ID)

  fun getOrganizationId(observationId: ObservationId): OrganizationId? =
      fetchFieldById(observationId, OBSERVATIONS.ID, OBSERVATIONS.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(participantProjectSpeciesId: ParticipantProjectSpeciesId): OrganizationId? =
      fetchFieldById(
          participantProjectSpeciesId,
          PARTICIPANT_PROJECT_SPECIES.ID,
          PARTICIPANT_PROJECT_SPECIES.projects.ORGANIZATION_ID,
      )

  fun getOrganizationId(plantingId: PlantingId): OrganizationId? =
      fetchFieldById(plantingId, PLANTINGS.ID, PLANTINGS.plantingSites.ORGANIZATION_ID)

  fun getOrganizationId(plantingSiteId: PlantingSiteId): OrganizationId? =
      fetchFieldById(plantingSiteId, PLANTING_SITES.ID, PLANTING_SITES.ORGANIZATION_ID)

  fun getOrganizationId(plantingSubzoneId: SubstratumId): OrganizationId? =
      fetchFieldById(
          plantingSubzoneId,
          SUBSTRATA.ID,
          SUBSTRATA.plantingSites.ORGANIZATION_ID,
      )

  fun getOrganizationId(plantingZoneId: StratumId): OrganizationId? =
      fetchFieldById(
          plantingZoneId,
          STRATA.ID,
          STRATA.plantingSites.ORGANIZATION_ID,
      )

  fun getOrganizationId(projectId: ProjectId): OrganizationId? =
      fetchFieldById(projectId, PROJECTS.ID, PROJECTS.ORGANIZATION_ID)

  fun getOrganizationId(reportId: ReportId): OrganizationId? =
      fetchFieldById(reportId, REPORTS.ID, REPORTS.projects.ORGANIZATION_ID)

  fun getOrganizationId(seedFundReportId: SeedFundReportId): OrganizationId? =
      fetchFieldById(seedFundReportId, SEED_FUND_REPORTS.ID, SEED_FUND_REPORTS.ORGANIZATION_ID)

  fun getOrganizationId(speciesId: SpeciesId): OrganizationId? =
      fetchFieldById(speciesId, SPECIES.ID, SPECIES.ORGANIZATION_ID)

  fun getOrganizationId(submissionId: SubmissionId): OrganizationId? =
      fetchFieldById(submissionId, SUBMISSIONS.ID, SUBMISSIONS.projects.ORGANIZATION_ID)

  fun getOrganizationId(uploadId: UploadId): OrganizationId? =
      fetchFieldById(uploadId, UPLOADS.ID, UPLOADS.ORGANIZATION_ID)

  fun getPlantingSiteId(monitoringPlotId: MonitoringPlotId): PlantingSiteId? =
      fetchFieldById(monitoringPlotId, MONITORING_PLOTS.ID, MONITORING_PLOTS.PLANTING_SITE_ID)

  fun getPlantingSiteId(observationId: ObservationId): PlantingSiteId? =
      fetchFieldById(observationId, OBSERVATIONS.ID, OBSERVATIONS.PLANTING_SITE_ID)

  fun getUserId(notificationId: NotificationId): UserId? =
      fetchFieldById(notificationId, NOTIFICATIONS.ID, NOTIFICATIONS.USER_ID)

  fun getProjectId(accessionId: AccessionId): ProjectId? =
      fetchFieldById(accessionId, ACCESSIONS.ID, ACCESSIONS.PROJECT_ID)

  fun getProjectId(activityId: ActivityId): ProjectId? =
      fetchFieldById(activityId, ACTIVITIES.ID, ACTIVITIES.PROJECT_ID)

  fun getProjectId(batchId: BatchId): ProjectId? =
      fetchFieldById(batchId, BATCHES.ID, BATCHES.PROJECT_ID)

  fun getProjectId(deliveryId: DeliveryId): ProjectId? =
      fetchFieldById(deliveryId, DELIVERIES.ID, DELIVERIES.plantingSites.PROJECT_ID)

  fun getProjectId(draftPlantingSiteId: DraftPlantingSiteId): ProjectId? =
      fetchFieldById(draftPlantingSiteId, DRAFT_PLANTING_SITES.ID, DRAFT_PLANTING_SITES.PROJECT_ID)

  fun getProjectId(monitoringPlotId: MonitoringPlotId): ProjectId? =
      fetchFieldById(
          monitoringPlotId,
          MONITORING_PLOTS.ID,
          MONITORING_PLOTS.substrata.plantingSites.PROJECT_ID,
      )

  fun getProjectId(observationId: ObservationId): ProjectId? =
      fetchFieldById(observationId, OBSERVATIONS.ID, OBSERVATIONS.plantingSites.PROJECT_ID)

  fun getProjectId(plantingId: PlantingId): ProjectId? =
      fetchFieldById(plantingId, PLANTINGS.ID, PLANTINGS.plantingSites.PROJECT_ID)

  fun getProjectId(plantingSiteId: PlantingSiteId): ProjectId? =
      fetchFieldById(plantingSiteId, PLANTING_SITES.ID, PLANTING_SITES.PROJECT_ID)

  fun getProjectId(plantingSubzoneId: SubstratumId): ProjectId? =
      fetchFieldById(
          plantingSubzoneId,
          SUBSTRATA.ID,
          SUBSTRATA.plantingSites.PROJECT_ID,
      )

  fun getProjectId(plantingZoneId: StratumId): ProjectId? =
      fetchFieldById(plantingZoneId, STRATA.ID, STRATA.plantingSites.PROJECT_ID)

  fun getProjectId(reportId: ReportId): ProjectId? =
      fetchFieldById(reportId, REPORTS.ID, REPORTS.PROJECT_ID)

  fun getProjectId(seedFundReportId: SeedFundReportId): ProjectId? =
      fetchFieldById(seedFundReportId, SEED_FUND_REPORTS.ID, SEED_FUND_REPORTS.PROJECT_ID)

  fun getProjectId(submissionId: SubmissionId): ProjectId? =
      fetchFieldById(submissionId, SUBMISSIONS.ID, SUBMISSIONS.PROJECT_ID)

  fun getProjectId(viabilityTestId: ViabilityTestId): ProjectId? =
      fetchFieldById(viabilityTestId, VIABILITY_TESTS.ID, VIABILITY_TESTS.accessions.PROJECT_ID)

  fun getFacilityConnectionState(deviceId: DeviceId): FacilityConnectionState {
    return fetchFieldById(deviceId, DEVICES.ID, DEVICES.facilities.CONNECTION_STATE_ID)
        ?: throw DeviceNotFoundException(deviceId)
  }

  fun getFacilityName(accessionId: AccessionId): String {
    val facilityId = getFacilityId(accessionId) ?: throw AccessionNotFoundException(accessionId)
    return fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.NAME)
        ?: throw FacilityNotFoundException(facilityId)
  }

  fun getFacilityType(facilityId: FacilityId): FacilityType =
      fetchFieldById(facilityId, FACILITIES.ID, FACILITIES.TYPE_ID)
          ?: throw FacilityNotFoundException(facilityId)

  fun getUserId(draftPlantingSiteId: DraftPlantingSiteId): UserId? =
      fetchFieldById(draftPlantingSiteId, DRAFT_PLANTING_SITES.ID, DRAFT_PLANTING_SITES.CREATED_BY)

  fun getUserId(uploadId: UploadId): UserId? =
      fetchFieldById(uploadId, UPLOADS.ID, UPLOADS.CREATED_BY)

  fun getDeviceManagerId(userId: UserId): DeviceManagerId? =
      fetchFieldById(userId, DEVICE_MANAGERS.USER_ID, DEVICE_MANAGERS.ID)

  fun getEffectiveTimeZone(accessionId: AccessionId): ZoneId =
      fetchFieldById(
          accessionId,
          ACCESSIONS.ID,
          DSL.coalesce(
              ACCESSIONS.facilities.TIME_ZONE,
              ACCESSIONS.facilities.organizations.TIME_ZONE,
          ),
      ) ?: ZoneOffset.UTC

  fun getEffectiveTimeZone(activityId: ActivityId): ZoneId =
      fetchFieldById(activityId, ACTIVITIES.ID, ACTIVITIES.projects.organizations.TIME_ZONE)
          ?: ZoneOffset.UTC

  fun getEffectiveTimeZone(batchId: BatchId): ZoneId =
      fetchFieldById(
          batchId,
          BATCHES.ID,
          DSL.coalesce(BATCHES.facilities.TIME_ZONE, BATCHES.facilities.organizations.TIME_ZONE),
      ) ?: ZoneOffset.UTC

  fun getEffectiveTimeZone(facilityId: FacilityId): ZoneId =
      fetchFieldById(
          facilityId,
          FACILITIES.ID,
          DSL.coalesce(FACILITIES.TIME_ZONE, FACILITIES.organizations.TIME_ZONE),
      ) ?: ZoneOffset.UTC

  fun getEffectiveTimeZone(plantingSiteId: PlantingSiteId): ZoneId =
      fetchFieldById(
          plantingSiteId,
          PLANTING_SITES.ID,
          DSL.coalesce(PLANTING_SITES.TIME_ZONE, PLANTING_SITES.organizations.TIME_ZONE),
      ) ?: ZoneOffset.UTC

  fun exists(deviceManagerId: DeviceManagerId): Boolean =
      fetchFieldById(deviceManagerId, DEVICE_MANAGERS.ID, DSL.one()) != null

  fun exists(organizationId: OrganizationId, userId: UserId): Boolean =
      dslContext
          .selectOne()
          .from(ORGANIZATION_USERS)
          .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
          .and(ORGANIZATION_USERS.USER_ID.eq(userId))
          .fetch()
          .isNotEmpty

  fun exists(eventId: EventId, userId: UserId): Boolean =
      dslContext
          .selectOne()
          .from(EVENT_PROJECTS)
          .join(PROJECTS)
          .on(PROJECTS.ID.eq(EVENT_PROJECTS.PROJECT_ID))
          .join(ORGANIZATION_USERS)
          .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
          .where(ORGANIZATION_USERS.USER_ID.eq(userId))
          .and(EVENT_PROJECTS.EVENT_ID.eq(eventId))
          .fetch()
          .isNotEmpty

  fun exists(moduleId: ModuleId, userId: UserId): Boolean {
    val cohortModuleExists =
        dslContext
            .selectOne()
            .from(COHORT_MODULES)
            .join(PROJECTS)
            .on(PROJECTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
            .join(ORGANIZATION_USERS)
            .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
            .where(ORGANIZATION_USERS.USER_ID.eq(userId))
            .and(COHORT_MODULES.MODULE_ID.eq(moduleId))
            .fetch()
            .isNotEmpty
    val applicationModuleExists =
        dslContext
            .selectOne()
            .from(APPLICATION_MODULES)
            .join(APPLICATIONS)
            .on(APPLICATIONS.ID.eq(APPLICATION_MODULES.APPLICATION_ID))
            .join(PROJECTS)
            .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
            .join(ORGANIZATION_USERS)
            .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
            .where(ORGANIZATION_USERS.USER_ID.eq(userId))
            .and(APPLICATION_MODULES.MODULE_ID.eq(moduleId))
            .fetch()
            .isNotEmpty

    return cohortModuleExists || applicationModuleExists
  }

  fun exists(cohortId: CohortId, userId: UserId): Boolean =
      dslContext
          .selectOne()
          .from(COHORTS)
          .join(PROJECTS)
          .on(PROJECTS.COHORT_ID.eq(COHORTS.ID))
          .join(ORGANIZATION_USERS)
          .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
          .where(ORGANIZATION_USERS.USER_ID.eq(userId))
          .and(COHORTS.ID.eq(cohortId))
          .fetch()
          .isNotEmpty

  fun exists(fundingEntityId: FundingEntityId, userId: UserId): Boolean =
      dslContext
          .selectOne()
          .from(FUNDING_ENTITY_USERS)
          .where(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(fundingEntityId))
          .and(FUNDING_ENTITY_USERS.USER_ID.eq(userId))
          .fetch()
          .isNotEmpty

  /**
   * Returns true if an organization has one or more projects that are either in the accelerator or
   * have applied for the accelerator.
   */
  fun hasAcceleratorOrApplicationProjects(organizationId: OrganizationId?): Boolean =
      organizationId != null &&
          dslContext.fetchExists(
              DSL.selectOne()
                  .from(PROJECTS)
                  .leftJoin(APPLICATIONS)
                  .on(PROJECTS.ID.eq(APPLICATIONS.PROJECT_ID))
                  .where(PROJECTS.PHASE_ID.isNotNull.or(APPLICATIONS.ID.isNotNull))
                  .and(PROJECTS.ORGANIZATION_ID.eq(organizationId))
          )

  fun isProjectInAccelerator(projectId: ProjectId?): Boolean =
      projectId != null &&
          (dslContext.fetchExists(
              PROJECT_ACCELERATOR_DETAILS,
              PROJECT_ACCELERATOR_DETAILS.PROJECT_ID.eq(projectId),
          ) || isProjectInCohort(projectId))

  fun isProjectInCohort(projectId: ProjectId?): Boolean =
      projectId != null &&
          dslContext.fetchExists(
              PROJECTS,
              PROJECTS.ID.eq(projectId),
              PROJECTS.COHORT_ID.isNotNull,
          )

  /**
   * Looks up a database row by an ID and returns the value of one of the columns, or null if no row
   * had the given ID.
   */
  private fun <C, P, R : Record> fetchFieldById(
      id: C,
      idField: TableField<R, C>,
      fieldToFetch: Field<P>,
  ): P? {
    return dslContext
        .select(fieldToFetch)
        .from(idField.table)
        .where(idField.eq(id))
        .fetchOne(fieldToFetch)
  }
}
