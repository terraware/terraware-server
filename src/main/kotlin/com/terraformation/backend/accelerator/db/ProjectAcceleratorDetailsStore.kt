package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ParticipantProjectFileNamingUpdatedEvent
import com.terraformation.backend.accelerator.model.MetricProgressModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.accelerator.model.TRACKED_ACCUMULATED_METRICS
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.records.ProjectLandUseModelTypesRecord
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_LAND_USE_MODEL_TYPES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_REPORT_SYSTEM_METRICS
import jakarta.inject.Named
import java.net.URI
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class ProjectAcceleratorDetailsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  /**
   * Returns the accelerator details for a project. If the project doesn't have any details yet,
   * returns a model with just the non-accelerator-specific fields populated.
   */
  fun fetchOneById(
      projectId: ProjectId,
      variableValuesModel: ProjectAcceleratorVariableValuesModel,
  ): ProjectAcceleratorDetailsModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    return fetchOneByIdOrNull(projectId, variableValuesModel)
        ?: throw ProjectNotFoundException(projectId)
  }

  /**
   * Returns the accelerator details for every project. If the project doesn't have any details yet,
   * returns a model with just the non-accelerator-specific fields populated.
   */
  fun fetch(
      condition: Condition,
      filterFn: (ProjectId) -> Boolean = { projectId ->
        currentUser().canReadProjectAcceleratorDetails(projectId)
      },
      projectVariableValues: (ProjectId) -> ProjectAcceleratorVariableValuesModel,
  ): List<ProjectAcceleratorDetailsModel> {
    return dslContext
        .select(
            PROJECT_ACCELERATOR_DETAILS.asterisk(),
            PROJECTS.ID,
            COHORTS.ID,
            COHORTS.NAME,
            COHORTS.PHASE_ID,
            PARTICIPANTS.ID,
            PARTICIPANTS.NAME,
            projectProgressMultiset,
        )
        .from(PROJECTS)
        .leftJoin(PROJECT_ACCELERATOR_DETAILS)
        .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
        .leftJoin(PARTICIPANTS)
        .on(PARTICIPANTS.ID.eq(PROJECTS.PARTICIPANT_ID))
        .leftJoin(COHORTS)
        .on(COHORTS.ID.eq(PARTICIPANTS.COHORT_ID))
        .where(condition)
        .filter { filterFn(it[PROJECTS.ID]!!) }
        .map {
          ProjectAcceleratorDetailsModel.of(
              it,
              projectProgressMultiset,
              projectVariableValues(it[PROJECTS.ID]!!),
          )
        }
  }

  fun update(
      projectId: ProjectId,
      variableValues: ProjectAcceleratorVariableValuesModel,
      applyFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel,
  ) {
    requirePermissions { updateProjectAcceleratorDetails(projectId) }

    val existing = fetchOneById(projectId, variableValues)
    val updated = applyFunc(existing)

    val dropboxFolderPath: String?
    val googleFolderUrl: URI?

    if (currentUser().canUpdateProjectDocumentSettings(projectId)) {
      dropboxFolderPath = updated.dropboxFolderPath
      googleFolderUrl = updated.googleFolderUrl
    } else {
      dropboxFolderPath = existing.dropboxFolderPath
      googleFolderUrl = existing.googleFolderUrl
    }

    dslContext.transaction { _ ->
      with(PROJECT_ACCELERATOR_DETAILS) {
        dslContext
            .insertInto(this)
            .set(ANNUAL_CARBON, updated.annualCarbon)
            .set(APPLICATION_REFORESTABLE_LAND, updated.applicationReforestableLand)
            .set(CARBON_CAPACITY, updated.carbonCapacity)
            .set(CONFIRMED_REFORESTABLE_LAND, updated.confirmedReforestableLand)
            .set(DEAL_DESCRIPTION, updated.dealDescription)
            .set(DEAL_NAME, updated.dealName)
            .set(DEAL_STAGE_ID, updated.dealStage)
            .set(DROPBOX_FOLDER_PATH, dropboxFolderPath)
            .set(FAILURE_RISK, updated.failureRisk)
            .set(FILE_NAMING, updated.fileNaming)
            .set(GOOGLE_FOLDER_URL, googleFolderUrl)
            .set(HUBSPOT_URL, updated.hubSpotUrl)
            .set(INVESTMENT_THESIS, updated.investmentThesis)
            .set(MAX_CARBON_ACCUMULATION, updated.maxCarbonAccumulation)
            .set(MIN_CARBON_ACCUMULATION, updated.minCarbonAccumulation)
            .set(NUM_COMMUNITIES, updated.numCommunities)
            .set(NUM_NATIVE_SPECIES, updated.numNativeSpecies)
            .set(PER_HECTARE_BUDGET, updated.perHectareBudget)
            .set(PIPELINE_ID, updated.pipeline)
            .set(PLANTING_SITES_CQL, updated.plantingSitesCql)
            .set(PROJECT_BOUNDARIES_CQL, updated.projectBoundariesCql)
            .set(PROJECT_ID, projectId)
            .set(TOTAL_CARBON, updated.totalCarbon)
            .set(TOTAL_EXPANSION_POTENTIAL, updated.totalExpansionPotential)
            .set(WHAT_NEEDS_TO_BE_TRUE, updated.whatNeedsToBeTrue)
            .onConflict(PROJECT_ID)
            .doUpdate()
            .set(ANNUAL_CARBON, updated.annualCarbon)
            .set(APPLICATION_REFORESTABLE_LAND, updated.applicationReforestableLand)
            .set(CARBON_CAPACITY, updated.carbonCapacity)
            .set(CONFIRMED_REFORESTABLE_LAND, updated.confirmedReforestableLand)
            .set(DEAL_DESCRIPTION, updated.dealDescription)
            .set(DEAL_NAME, updated.dealName)
            .set(DEAL_STAGE_ID, updated.dealStage)
            .set(DROPBOX_FOLDER_PATH, dropboxFolderPath)
            .set(FAILURE_RISK, updated.failureRisk)
            .set(FILE_NAMING, updated.fileNaming)
            .set(GOOGLE_FOLDER_URL, googleFolderUrl)
            .set(HUBSPOT_URL, updated.hubSpotUrl)
            .set(INVESTMENT_THESIS, updated.investmentThesis)
            .set(MAX_CARBON_ACCUMULATION, updated.maxCarbonAccumulation)
            .set(MIN_CARBON_ACCUMULATION, updated.minCarbonAccumulation)
            .set(NUM_COMMUNITIES, updated.numCommunities)
            .set(NUM_NATIVE_SPECIES, updated.numNativeSpecies)
            .set(PER_HECTARE_BUDGET, updated.perHectareBudget)
            .set(PIPELINE_ID, updated.pipeline)
            .set(PLANTING_SITES_CQL, updated.plantingSitesCql)
            .set(PROJECT_BOUNDARIES_CQL, updated.projectBoundariesCql)
            .set(TOTAL_CARBON, updated.totalCarbon)
            .set(TOTAL_EXPANSION_POTENTIAL, updated.totalExpansionPotential)
            .set(WHAT_NEEDS_TO_BE_TRUE, updated.whatNeedsToBeTrue)
            .execute()
      }

      with(PROJECTS) {
        dslContext
            .update(PROJECTS)
            .set(COUNTRY_CODE, updated.countryCode)
            .set(MODIFIED_BY, currentUser().userId)
            .set(MODIFIED_TIME, clock.instant())
            .where(ID.eq(projectId))
            .execute()
      }

      if (existing.landUseModelTypes != updated.landUseModelTypes) {
        with(PROJECT_LAND_USE_MODEL_TYPES) {
          dslContext.deleteFrom(this).where(PROJECT_ID.eq(projectId)).execute()

          dslContext
              .insertInto(this)
              .set(updated.landUseModelTypes.map { ProjectLandUseModelTypesRecord(projectId, it) })
              .execute()
        }
      }

      if (existing.fileNaming != updated.fileNaming) {
        eventPublisher.publishEvent(ParticipantProjectFileNamingUpdatedEvent(projectId))
      }
    }
  }

  private fun fetchOneByIdOrNull(
      projectId: ProjectId,
      variableValues: ProjectAcceleratorVariableValuesModel,
  ): ProjectAcceleratorDetailsModel? {
    return fetch(PROJECTS.ID.eq(projectId)) { variableValues }.firstOrNull()
  }

  private val projectProgressMultiset: Field<List<MetricProgressModel>>
    get() {
      val progressField =
          DSL.if_(
              PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID.eq(SystemMetric.SpeciesPlanted),
              DSL.max(PUBLISHED_REPORT_SYSTEM_METRICS.VALUE),
              DSL.sum(PUBLISHED_REPORT_SYSTEM_METRICS.VALUE).cast(Int::class.java),
          )
      return DSL.multiset(
              DSL.select(
                      PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID,
                      progressField,
                  )
                  .from(PUBLISHED_REPORT_SYSTEM_METRICS)
                  .join(PUBLISHED_REPORTS)
                  .on(PUBLISHED_REPORT_SYSTEM_METRICS.REPORT_ID.eq(PUBLISHED_REPORTS.REPORT_ID))
                  .where(PUBLISHED_REPORTS.PROJECT_ID.eq(PROJECTS.ID))
                  .and(
                      PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID.`in`(
                          TRACKED_ACCUMULATED_METRICS
                      )
                  )
                  .groupBy(PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID)
                  .orderBy(PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID)
          )
          .convertFrom { results ->
            results.map { record ->
              MetricProgressModel(
                  metric = record[PUBLISHED_REPORT_SYSTEM_METRICS.SYSTEM_METRIC_ID]!!,
                  progress = record[progressField] ?: 0,
              )
            }
          }
    }
}
