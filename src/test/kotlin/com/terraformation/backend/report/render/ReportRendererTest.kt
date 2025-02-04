package com.terraformation.backend.report.render

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SeedFundReportStatus
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportBodyModelV1
import com.terraformation.backend.report.model.ReportMetadata
import com.terraformation.backend.report.model.ReportModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReportRendererTest {
  private val reportStore: ReportStore = mockk()
  private val renderer = ReportRenderer(mockk(), mockk(), reportStore, mockk())

  private val reportId = SeedFundReportId(1)
  private val metadata =
      ReportMetadata(
          id = reportId,
          quarter = 4,
          year = 2023,
          organizationId = OrganizationId(1),
          status = SeedFundReportStatus.Submitted)

  private val csvHeader =
      "Deal ID" +
          ",Organization Name" +
          ",Seed Bank: Count" +
          ",Seed Bank: Seeds Stored" +
          ",Nursery: Count" +
          ",Nursery: Plants/Trees Propagated" +
          ",Nursery: Mortality Rate (%)" +
          ",Planted: Site Count" +
          ",Project Hectares" +
          ",Planted: Ha to Date" +
          ",Planted: Trees" +
          ",Planted: Non-Trees" +
          ",Planted: Mortality Rate (%)" +
          ",Planted: Species" +
          ",Planted: Best Months to Monitor Plantings" +
          ",# of workers for TF projects" +
          ",# of workers who are women for TF projects" +
          ",# of volunteers for TF projects" +
          ",Summary of progress"

  @Test
  fun `renderReportCsv correctly aggregates data`() {
    val reportBody =
        ReportBodyModelV1(
            annualDetails =
                ReportBodyModelV1.AnnualDetails(
                    bestMonthsForObservation = setOf(1, 2, 3),
                ),
            isAnnual = true,
            nurseries =
                listOf(
                    ReportBodyModelV1.Nursery(
                        id = FacilityId(1),
                        mortalityRate = 25,
                        name = "selected nursery 1",
                        totalPlantsPropagated = 100,
                        workers = ReportBodyModelV1.Workers(1, 2, 3),
                    ),
                    ReportBodyModelV1.Nursery(
                        id = FacilityId(2),
                        mortalityRate = 50,
                        name = "selected nursery 2",
                        totalPlantsPropagated = 50,
                        workers = ReportBodyModelV1.Workers(4, 5, 6),
                    ),
                    ReportBodyModelV1.Nursery(
                        id = FacilityId(3),
                        mortalityRate = 75,
                        name = "non-selected nursery",
                        totalPlantsPropagated = 1000,
                        selected = false,
                        workers = ReportBodyModelV1.Workers(7, 8, 9),
                    ),
                ),
            organizationName = "org name",
            plantingSites =
                listOf(
                    ReportBodyModelV1.PlantingSite(
                        id = PlantingSiteId(1),
                        mortalityRate = 10,
                        name = "planting site",
                        species =
                            listOf(
                                ReportBodyModelV1.PlantingSite.Species(
                                    id = SpeciesId(1),
                                    scientificName = "Species b",
                                ),
                                ReportBodyModelV1.PlantingSite.Species(
                                    id = SpeciesId(2),
                                    scientificName = "Species a",
                                ),
                            ),
                        totalPlantedArea = 10,
                        totalPlantingSiteArea = 11,
                        totalPlantsPlanted = 12,
                        totalTreesPlanted = 13,
                        workers = ReportBodyModelV1.Workers(10, 11, 12),
                    ),
                ),
            seedBanks =
                listOf(
                    ReportBodyModelV1.SeedBank(
                        id = FacilityId(4),
                        name = "seed bank",
                        totalSeedsStored = 1000L,
                        workers = ReportBodyModelV1.Workers(13, 14, 15),
                    ),
                ),
            summaryOfProgress = "summary of progress",
            totalNurseries = 2,
            totalPlantingSites = 1,
            totalSeedBanks = 1,
        )

    every { reportStore.fetchOneById(reportId) } returns ReportModel(reportBody, metadata)

    val dataRow =
        "" +
            ",org name" +
            ",1" +
            ",1000" +
            ",2" +
            ",150" +
            // Weighted nursery mortality rate for 25% of 100 plants + 50% of 50 plants = 33%
            ",33" +
            ",1" +
            ",11" +
            ",10" +
            ",13" +
            ",12" +
            ",10" +
            ",\"Species a,Species b\"" +
            ",Jan;Feb;Mar" +
            ",32" +
            ",28" +
            ",36" +
            ",summary of progress"

    val expected = "$csvHeader\r\n$dataRow\r\n"
    val actual = renderer.renderReportCsv(reportId)

    assertEquals(expected, actual)
  }

  @Test
  fun `renderReportCsv outputs default values for missing data`() {
    val reportBody =
        ReportBodyModelV1(
            isAnnual = false,
            nurseries = emptyList(),
            organizationName = "org name",
            plantingSites = emptyList(),
            seedBanks = emptyList(),
            totalNurseries = 0,
            totalPlantingSites = 0,
            totalSeedBanks = 0,
        )

    every { reportStore.fetchOneById(reportId) } returns ReportModel(reportBody, metadata)

    val dataRow =
        "" +
            ",org name" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            ",0" +
            "," +
            "," +
            ",0" +
            ",0" +
            ",0" +
            ","

    val expected = "$csvHeader\r\n$dataRow\r\n"
    val actual = renderer.renderReportCsv(reportId)

    assertEquals(expected, actual)
  }
}
