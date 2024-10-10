package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.event.ParticipantProjectFileNamingUpdatedEvent
import com.terraformation.backend.accelerator.model.ProjectAcceleratorDetailsModel
import com.terraformation.backend.accelerator.model.ProjectAcceleratorVariableValuesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_ACCELERATOR_DETAILS
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.records.ProjectLandUseModelTypesRecord
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_LAND_USE_MODEL_TYPES
import jakarta.inject.Named
import java.net.URI
import java.time.InstantSource
import org.jooq.DSLContext
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
  fun fetchOneById(projectId: ProjectId): ProjectAcceleratorDetailsModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    return fetchOneByIdOrNull(projectId) ?: throw ProjectNotFoundException(projectId)
  }

  fun fetchOneById(
      projectId: ProjectId,
      variableValuesModel: ProjectAcceleratorVariableValuesModel
  ): ProjectAcceleratorDetailsModel {
    requirePermissions { readProjectAcceleratorDetails(projectId) }

    return fetchOneByIdOrNull(projectId, variableValuesModel)
        ?: throw ProjectNotFoundException(projectId)
  }

  fun update(
      projectId: ProjectId,
      applyFunc: (ProjectAcceleratorDetailsModel) -> ProjectAcceleratorDetailsModel
  ) {
    requirePermissions { updateProjectAcceleratorDetails(projectId) }

    val existing =
        fetchOneByIdOrNull(projectId) ?: ProjectAcceleratorDetailsModel(projectId = projectId)
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
            .set(PROJECT_ID, projectId)
            .set(PROJECT_LEAD, updated.projectLead)
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
            .set(PROJECT_LEAD, updated.projectLead)
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

  private fun fetchOneByIdOrNull(projectId: ProjectId): ProjectAcceleratorDetailsModel? {
    val landUseModelTypesMultiset =
        DSL.multiset(
                DSL.select(PROJECT_LAND_USE_MODEL_TYPES.LAND_USE_MODEL_TYPE_ID.asNonNullable())
                    .from(PROJECT_LAND_USE_MODEL_TYPES)
                    .where(PROJECT_LAND_USE_MODEL_TYPES.PROJECT_ID.eq(PROJECTS.ID)),
            )
            .convertFrom { result -> result.map { it.value1() }.toSet() }

    return dslContext
        .select(
            COUNTRIES.REGION_ID,
            landUseModelTypesMultiset,
            PROJECT_ACCELERATOR_DETAILS.asterisk(),
            PROJECTS.COUNTRY_CODE,
            PROJECTS.ID,
        )
        .from(PROJECTS)
        .leftJoin(PROJECT_ACCELERATOR_DETAILS)
        .on(PROJECTS.ID.eq(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID))
        .leftJoin(COUNTRIES)
        .on(PROJECTS.COUNTRY_CODE.eq(COUNTRIES.CODE))
        .where(PROJECTS.ID.eq(projectId))
        .fetchOne { ProjectAcceleratorDetailsModel.of(it, landUseModelTypesMultiset) }
  }

  private fun fetchOneByIdOrNull(
      projectId: ProjectId,
      variableValues: ProjectAcceleratorVariableValuesModel
  ): ProjectAcceleratorDetailsModel? {
    return dslContext
        .select(
            PROJECT_ACCELERATOR_DETAILS.asterisk(),
        )
        .from(PROJECT_ACCELERATOR_DETAILS)
        .where(PROJECT_ACCELERATOR_DETAILS.PROJECT_ID.eq(projectId))
        .fetchOne { ProjectAcceleratorDetailsModel.of(it, variableValues) }
  }
}
