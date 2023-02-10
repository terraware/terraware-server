package com.terraformation.backend.report

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.LatestReportBodyModel
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import javax.inject.Named

@Named
class ReportService(
    private val accessionStore: AccessionStore,
    private val facilityStore: FacilityStore,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val reportStore: ReportStore,
    private val speciesStore: SpeciesStore,
) {
  fun fetchOneById(reportId: ReportId): ReportModel {
    val report = reportStore.fetchOneById(reportId)

    // Never refresh server-generated values in reports once they're submitted.
    return if (report.metadata.submittedTime != null) {
      report
    } else {
      report.copy(
          body = populateBody(report.metadata.organizationId, report.body.toLatestVersion()),
      )
    }
  }

  fun create(organizationId: OrganizationId): ReportMetadata {
    return reportStore.create(organizationId, populateBody(organizationId))
  }

  fun update(reportId: ReportId, modify: (LatestReportBodyModel) -> LatestReportBodyModel) {
    val modifiedBody = modify(fetchOneById(reportId).body.toLatestVersion())

    reportStore.update(reportId, modifiedBody)
  }

  private fun populateBody(
      organizationId: OrganizationId,
      body: LatestReportBodyModel? = null
  ): LatestReportBodyModel {
    val facilities = facilityStore.fetchByOrganizationId(organizationId)
    val nurseryModels = facilities.filter { it.type == FacilityType.Nursery }
    val organization = organizationStore.fetchOneById(organizationId)
    val plantingSiteModels = plantingSiteStore.fetchSitesByOrganizationId(organizationId)
    val seedBankModels = facilities.filter { it.type == FacilityType.SeedBank }

    val nurseryBodies =
        nurseryModels.map { facility ->
          body?.nurseries?.find { it.id == facility.id }?.populate(facility)
              ?: ReportBodyModelV1.Nursery(facility)
        }

    val plantingSiteBodies =
        plantingSiteModels.map { plantingSiteModel ->
          val speciesModels = speciesStore.fetchSpeciesByPlantingSiteId(plantingSiteModel.id)
          body
              ?.plantingSites
              ?.find { it.id == plantingSiteModel.id }
              ?.populate(plantingSiteModel, speciesModels)
              ?: ReportBodyModelV1.PlantingSite(plantingSiteModel, speciesModels)
        }

    val seedBankBodies =
        seedBankModels.map { facility ->
          val totalSeedsStored =
              accessionStore.getSummaryStatistics(facility.id).totalSeedsRemaining
          body?.seedBanks?.find { it.id == facility.id }?.populate(facility, totalSeedsStored)
              ?: ReportBodyModelV1.SeedBank(facility, totalSeedsStored)
        }

    return body?.copy(
        nurseries = nurseryBodies,
        organizationName = organization.name,
        plantingSites = plantingSiteBodies,
        seedBanks = seedBankBodies,
        totalNurseries = nurseryModels.size,
        totalPlantingSites = plantingSiteModels.size,
        totalSeedBanks = seedBankModels.size,
    )
        ?: ReportBodyModelV1(
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
