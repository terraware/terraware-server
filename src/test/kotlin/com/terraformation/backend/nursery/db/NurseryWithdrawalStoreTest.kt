package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.nursery.model.NurserySpeciesModel
import com.terraformation.backend.nursery.model.PlotSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class NurseryWithdrawalStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: NurseryWithdrawalStore by lazy { NurseryWithdrawalStore(dslContext) }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    plantingSiteId = insertPlantingSite()
  }

  @Nested
  inner class FetchSiteSpeciesByPlot {
    @Test
    fun `throws exception when user lacks permission`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchSiteSpeciesByPlot(plantingSiteId) }
    }

    @Test
    fun `fetches site species by plot with densities`() {
      val species1 = insertSpecies()
      val species2 = insertSpecies()
      val species3 = insertSpecies()
      insertPlantingZone()
      val subzone1 = insertPlantingSubzone(areaHa = BigDecimal.ONE)
      val plot1 = insertMonitoringPlot()
      val plot2 = insertMonitoringPlot()
      val subzone2 = insertPlantingSubzone(areaHa = BigDecimal.TEN)
      val plot3 = insertMonitoringPlot()
      val plot4 = insertMonitoringPlot()
      insertPlantingZone()
      val subzone3 = insertPlantingSubzone(areaHa = BigDecimal.valueOf(5))
      val plot5 = insertMonitoringPlot()
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone1, speciesId = species1, 100)
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone1, speciesId = species2, 200)
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone2, speciesId = species1, 300)
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone2, speciesId = species2, 395)
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone2, speciesId = species3, 500)
      insertPlantingSubzonePopulation(plantingSubzoneId = subzone3, speciesId = species3, 600)

      val expected =
          setOf(
              PlotSpeciesModel(
                  monitoringPlotId = plot1,
                  species =
                      createSpeciesDensityList(
                          species1 to BigDecimal.valueOf(100),
                          species2 to BigDecimal.valueOf(200),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot2,
                  species =
                      createSpeciesDensityList(
                          species1 to BigDecimal.valueOf(100),
                          species2 to BigDecimal.valueOf(200),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot3,
                  species =
                      createSpeciesDensityList(
                          species1 to BigDecimal.valueOf(30),
                          species2 to BigDecimal.valueOf(40), // this confirms correct rounding
                          species3 to BigDecimal.valueOf(50),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot4,
                  species =
                      createSpeciesDensityList(
                          species1 to BigDecimal.valueOf(30),
                          species2 to BigDecimal.valueOf(40),
                          species3 to BigDecimal.valueOf(50),
                      ),
              ),
              PlotSpeciesModel(
                  monitoringPlotId = plot5,
                  species =
                      createSpeciesDensityList(
                          species3 to BigDecimal.valueOf(120),
                      ),
              ),
          )

      assertSetEquals(expected, store.fetchSiteSpeciesByPlot(plantingSiteId).toSet())
    }
  }

  private fun createSpeciesDensityList(
      vararg densities: Pair<SpeciesId, BigDecimal>
  ): List<NurserySpeciesModel> {
    return densities.map { NurserySpeciesModel(speciesId = it.first, density = it.second) }
  }
}
