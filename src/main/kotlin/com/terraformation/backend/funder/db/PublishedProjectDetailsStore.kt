package com.terraformation.backend.funder.db

import com.terraformation.backend.accelerator.model.CarbonCertification
import com.terraformation.backend.accelerator.model.SustainableDevelopmentGoal
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.LandUseModelType
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_CARBON_CERTS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_DETAILS
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_LAND_USE
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_PROJECT_SDG
import com.terraformation.backend.funder.event.FunderProjectProfilePublishedEvent
import com.terraformation.backend.funder.model.FunderProjectDetailsModel
import com.terraformation.backend.funder.model.PublishedProjectNameModel
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class PublishedProjectDetailsStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {

  fun fetchAll(): List<PublishedProjectNameModel> {
    return with(PUBLISHED_PROJECT_DETAILS) {
      dslContext.select(PROJECT_ID, DEAL_NAME).from(this).fetch { PublishedProjectNameModel.of(it) }
    }
  }

  fun fetchOneById(projectId: ProjectId): FunderProjectDetailsModel? {
    // convert the list to a set
    val sdgList =
        with(PUBLISHED_PROJECT_SDG) {
          dslContext
              .select(SDG_NUMBER.asNonNullable())
              .from(this)
              .where(PROJECT_ID.eq(projectId))
              .fetch(SDG_NUMBER.asNonNullable())
              .map {
                SustainableDevelopmentGoal.bySdgNumber[it]
                    ?: throw IllegalArgumentException("Unknown goal $it")
              }
              .toSet()
        }
    val carbonCerts =
        with(PUBLISHED_PROJECT_CARBON_CERTS) {
          dslContext
              .select(CARBON_CERTIFICATION.asNonNullable())
              .from(this)
              .where(PROJECT_ID.eq(projectId))
              .fetch(CARBON_CERTIFICATION.asNonNullable())
              .mapNotNull { CarbonCertification.forDisplayName(it) }
              .toSet()
        }
    val landUseModelMap =
        with(PUBLISHED_PROJECT_LAND_USE) {
          dslContext
              .select(LAND_USE_MODEL_TYPE_ID, LAND_USE_MODEL_HECTARES)
              .from(this)
              .where(PROJECT_ID.eq(projectId))
              .fetchMap(
                  LAND_USE_MODEL_TYPE_ID.asNonNullable(),
                  LAND_USE_MODEL_HECTARES.asNonNullable(),
              )
        }

    return with(PUBLISHED_PROJECT_DETAILS) {
      dslContext.selectFrom(this).where(PROJECT_ID.eq(projectId)).fetchOne()?.let {
        FunderProjectDetailsModel.of(it, carbonCerts, sdgList, landUseModelMap)
      }
    }
  }

  fun publish(project: FunderProjectDetailsModel) {
    val now = clock.instant()
    val userId = currentUser().userId
    dslContext.transaction { _ ->
      with(PUBLISHED_PROJECT_DETAILS) {
        dslContext
            .insertInto(this)
            .set(PROJECT_ID, project.projectId)
            .set(ACCUMULATION_RATE, project.accumulationRate)
            .set(ANNUAL_CARBON, project.annualCarbon)
            .set(TF_REFORESTABLE_LAND, project.confirmedReforestableLand)
            .set(COUNTRY_CODE, project.countryCode)
            .set(DEAL_DESCRIPTION, project.dealDescription)
            .set(DEAL_NAME, project.dealName)
            .set(METHODOLOGY_NUMBER, project.methodologyNumber)
            .set(MIN_PROJECT_AREA, project.minProjectArea)
            .set(NUM_NATIVE_SPECIES, project.numNativeSpecies)
            .set(PER_HECTARE_ESTIMATED_BUDGET, project.perHectareBudget)
            .set(PROJECT_AREA, project.projectArea)
            .set(PROJECT_HIGHLIGHT_PHOTO_VALUE_ID, project.projectHighlightPhotoValueId?.value)
            .set(PROJECT_ZONE_FIGURE_VALUE_ID, project.projectZoneFigureValueId?.value)
            .set(STANDARD, project.standard)
            .set(TOTAL_EXPANSION_POTENTIAL, project.totalExpansionPotential)
            .set(TOTAL_VCU, project.totalVCU)
            .set(VERRA_LINK, project.verraLink?.toString())
            .set(PUBLISHED_BY, userId)
            .set(PUBLISHED_TIME, now)
            .onConflict(PROJECT_ID)
            .doUpdate()
            .set(ACCUMULATION_RATE, project.accumulationRate)
            .set(ANNUAL_CARBON, project.annualCarbon)
            .set(TF_REFORESTABLE_LAND, project.confirmedReforestableLand)
            .set(COUNTRY_CODE, project.countryCode)
            .set(DEAL_DESCRIPTION, project.dealDescription)
            .set(DEAL_NAME, project.dealName)
            .set(METHODOLOGY_NUMBER, project.methodologyNumber)
            .set(MIN_PROJECT_AREA, project.minProjectArea)
            .set(NUM_NATIVE_SPECIES, project.numNativeSpecies)
            .set(PER_HECTARE_ESTIMATED_BUDGET, project.perHectareBudget)
            .set(PROJECT_AREA, project.projectArea)
            .set(PROJECT_HIGHLIGHT_PHOTO_VALUE_ID, project.projectHighlightPhotoValueId?.value)
            .set(PROJECT_ZONE_FIGURE_VALUE_ID, project.projectZoneFigureValueId?.value)
            .set(STANDARD, project.standard)
            .set(TOTAL_EXPANSION_POTENTIAL, project.totalExpansionPotential)
            .set(TOTAL_VCU, project.totalVCU)
            .set(VERRA_LINK, project.verraLink?.toString())
            .set(PUBLISHED_BY, userId)
            .set(PUBLISHED_TIME, now)
            .execute()

        publishProjectSdgs(project.projectId, project.sdgList)
        publishProjectLandUses(
            project.projectId,
            project.landUseModelTypes,
            project.landUseModelHectares,
        )
        publishProjectCarbonCerts(project.projectId, project.carbonCertifications)
      }

      eventPublisher.publishEvent(FunderProjectProfilePublishedEvent(project.projectId))
    }
  }

  private fun publishProjectSdgs(projectId: ProjectId, sdgList: Set<SustainableDevelopmentGoal>) {
    with(PUBLISHED_PROJECT_SDG) {
      dslContext.deleteFrom(this).where(PROJECT_ID.eq(projectId)).execute()

      sdgList.forEach { sdg ->
        dslContext
            .insertInto(this)
            .set(PROJECT_ID, projectId)
            .set(SDG_NUMBER, sdg.sdgNumber)
            .execute()
      }
    }
  }

  private fun publishProjectLandUses(
      projectId: ProjectId,
      landUseModelTypes: Set<LandUseModelType>,
      landUseModelHectares: Map<LandUseModelType, BigDecimal>,
  ) {
    with(PUBLISHED_PROJECT_LAND_USE) {
      dslContext.deleteFrom(this).where(PROJECT_ID.eq(projectId)).execute()

      landUseModelTypes.forEach { landType ->
        dslContext
            .insertInto(this)
            .set(PROJECT_ID, projectId)
            .set(LAND_USE_MODEL_TYPE_ID, landType)
            .set(LAND_USE_MODEL_HECTARES, landUseModelHectares[landType])
            .execute()
      }
    }
  }

  private fun publishProjectCarbonCerts(
      projectId: ProjectId,
      carbonCertifications: Set<CarbonCertification>,
  ) {
    with(PUBLISHED_PROJECT_CARBON_CERTS) {
      dslContext.deleteFrom(this).where(PROJECT_ID.eq(projectId)).execute()

      carbonCertifications.forEach { cert ->
        dslContext
            .insertInto(this)
            .set(PROJECT_ID, projectId)
            .set(CARBON_CERTIFICATION, cert.displayName)
            .execute()
      }
    }
  }
}
