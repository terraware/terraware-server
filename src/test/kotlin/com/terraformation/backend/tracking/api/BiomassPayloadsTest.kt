package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Tests to ensure logic to translate from API payloads to models. */
class BiomassPayloadsTest {
  @Nested
  inner class NewTreePayloadsTest {
    @Test
    fun `converts to NewRecordedTreeModels and assign tree and trunk numbers correctly`() {
      val treeList =
          listOf<NewTreePayload>(
              NewShrubPayload(
                  description = "Live shrub description",
                  gpsCoordinates = point(1),
                  isDead = false,
                  shrubDiameter = 5,
                  speciesId = SpeciesId(10),
                  speciesName = null,
              ),
              NewShrubPayload(
                  description = "Dead shrub description",
                  isDead = true,
                  shrubDiameter = 3,
                  speciesId = SpeciesId(10),
                  speciesName = null,
              ),
              NewTreeWithTrunksPayload(
                  gpsCoordinates = point(2),
                  speciesId = null,
                  speciesName = "Other tree",
                  trunks =
                      listOf(
                          NewTrunkPayload(
                              description = "Single trunk description",
                              diameterAtBreastHeight = BigDecimal(7),
                              height = BigDecimal(16),
                              isDead = false,
                              pointOfMeasurement = BigDecimal(1.3),
                          ),
                      ),
              ),
              NewTreeWithTrunksPayload(
                  gpsCoordinates = point(3),
                  speciesId = SpeciesId(5),
                  speciesName = null,
                  trunks =
                      listOf(
                          NewTrunkPayload(
                              description = "Multi-trunk description 1",
                              diameterAtBreastHeight = BigDecimal(4),
                              height = BigDecimal(4),
                              isDead = false,
                              pointOfMeasurement = BigDecimal(1.3),
                          ),
                          NewTrunkPayload(
                              description = "Multi-trunk description 2",
                              diameterAtBreastHeight = BigDecimal(8),
                              height = BigDecimal(9),
                              isDead = false,
                              pointOfMeasurement = BigDecimal(1.3),
                          ),
                      ),
              ),
          )

      val actual = treeList.flatMapIndexed { index, tree -> tree.toTreeModels(index + 1) }
      val expected =
          listOf(
              NewRecordedTreeModel(
                  id = null,
                  description = "Live shrub description",
                  gpsCoordinates = point(1),
                  isDead = false,
                  shrubDiameterCm = 5,
                  speciesId = SpeciesId(10),
                  speciesName = null,
                  treeGrowthForm = TreeGrowthForm.Shrub,
                  treeNumber = 1,
                  trunkNumber = 1,
              ),
              NewRecordedTreeModel(
                  id = null,
                  description = "Dead shrub description",
                  gpsCoordinates = null,
                  isDead = true,
                  shrubDiameterCm = 3,
                  speciesId = SpeciesId(10),
                  speciesName = null,
                  treeGrowthForm = TreeGrowthForm.Shrub,
                  treeNumber = 2,
                  trunkNumber = 1,
              ),
              NewRecordedTreeModel(
                  id = null,
                  description = "Single trunk description",
                  diameterAtBreastHeightCm = BigDecimal(7),
                  gpsCoordinates = point(2),
                  heightM = BigDecimal(16),
                  isDead = false,
                  pointOfMeasurementM = BigDecimal(1.3),
                  speciesId = null,
                  speciesName = "Other tree",
                  treeGrowthForm = TreeGrowthForm.Tree,
                  treeNumber = 3,
                  trunkNumber = 1,
              ),
              NewRecordedTreeModel(
                  id = null,
                  description = "Multi-trunk description 1",
                  diameterAtBreastHeightCm = BigDecimal(4),
                  gpsCoordinates = point(3),
                  heightM = BigDecimal(4),
                  isDead = false,
                  pointOfMeasurementM = BigDecimal(1.3),
                  speciesId = SpeciesId(5),
                  speciesName = null,
                  treeGrowthForm = TreeGrowthForm.Trunk,
                  treeNumber = 4,
                  trunkNumber = 1,
              ),
              NewRecordedTreeModel(
                  id = null,
                  description = "Multi-trunk description 2",
                  diameterAtBreastHeightCm = BigDecimal(8),
                  gpsCoordinates = point(3),
                  heightM = BigDecimal(9),
                  isDead = false,
                  pointOfMeasurementM = BigDecimal(1.3),
                  speciesId = SpeciesId(5),
                  speciesName = null,
                  treeGrowthForm = TreeGrowthForm.Trunk,
                  treeNumber = 4,
                  trunkNumber = 2,
              ),
          )

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class ExistingBiomassPayload {
    @Test
    fun `converts model to payload`() {
      val model =
          ExistingBiomassDetailsModel(
              description = "description",
              forestType = BiomassForestType.Terrestrial,
              herbaceousCoverPercent = 10,
              observationId = ObservationId(1),
              quadrats =
                  mapOf(
                      ObservationPlotPosition.NortheastCorner to
                          BiomassQuadratModel(
                              description = "NE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 40,
                                          speciesId = SpeciesId(1),
                                      )
                                  ),
                          ),
                      ObservationPlotPosition.NorthwestCorner to
                          BiomassQuadratModel(
                              description = "NW description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 60,
                                          speciesId = SpeciesId(2),
                                      ),
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 5,
                                          speciesName = "Herbaceous species",
                                      ),
                                  ),
                          ),
                      ObservationPlotPosition.SoutheastCorner to
                          BiomassQuadratModel(
                              description = "SE description",
                              species =
                                  setOf(
                                      BiomassQuadratSpeciesModel(
                                          abundancePercent = 90,
                                          speciesId = SpeciesId(1),
                                      )
                                  ),
                          ),
                      ObservationPlotPosition.SouthwestCorner to
                          BiomassQuadratModel(
                              description = "SW description",
                              species = emptySet(),
                          ),
                  ),
              smallTreeCountRange = 0 to 10,
              soilAssessment = "soil",
              species =
                  setOf(
                      BiomassSpeciesModel(
                          speciesId = SpeciesId(1),
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = SpeciesId(2),
                          isInvasive = false,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = SpeciesId(3),
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Herbaceous species",
                          commonName = "Common herb",
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Additional species",
                          commonName = "additional herb",
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          speciesId = SpeciesId(11),
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesModel(
                          speciesId = SpeciesId(12),
                          isInvasive = true,
                          isThreatened = false,
                      ),
                      BiomassSpeciesModel(
                          scientificName = "Tree species",
                          commonName = "Common tree",
                          isInvasive = false,
                          isThreatened = false,
                      ),
                  ),
              plotId = MonitoringPlotId(1),
              trees =
                  listOf(
                      ExistingRecordedTreeModel(
                          gpsCoordinates = point(1),
                          id = RecordedTreeId(1),
                          isDead = false,
                          shrubDiameterCm = 25,
                          speciesId = SpeciesId(11),
                          treeGrowthForm = TreeGrowthForm.Shrub,
                          treeNumber = 1,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = RecordedTreeId(2),
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          gpsCoordinates = null,
                          pointOfMeasurementM = BigDecimal.valueOf(1.3),
                          heightM = BigDecimal.TEN,
                          isDead = true,
                          speciesName = "Tree species",
                          treeGrowthForm = TreeGrowthForm.Tree,
                          treeNumber = 2,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = RecordedTreeId(3),
                          description = "Multi-trunk description 1",
                          diameterAtBreastHeightCm = BigDecimal.TEN,
                          gpsCoordinates = point(2),
                          pointOfMeasurementM = BigDecimal.valueOf(1.5),
                          isDead = false,
                          speciesId = SpeciesId(12),
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 1,
                      ),
                      ExistingRecordedTreeModel(
                          id = RecordedTreeId(4),
                          description = "Multi-trunk description 2",
                          diameterAtBreastHeightCm = BigDecimal.TWO,
                          gpsCoordinates = point(2),
                          pointOfMeasurementM = BigDecimal.valueOf(1.1),
                          isDead = false,
                          speciesId = SpeciesId(12),
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 2,
                      ),
                  ),
          )

      val expected =
          ExistingBiomassMeasurementPayload(
              additionalSpecies =
                  listOf(
                      BiomassSpeciesPayload(
                          speciesId = SpeciesId(3),
                          scientificName = null,
                          commonName = null,
                          isInvasive = false,
                          isThreatened = true,
                      ),
                      BiomassSpeciesPayload(
                          speciesId = null,
                          scientificName = "Additional species",
                          commonName = "additional herb",
                          isInvasive = true,
                          isThreatened = false,
                      ),
                  ),
              description = "description",
              forestType = BiomassForestType.Terrestrial,
              herbaceousCoverPercent = 10,
              ph = null,
              quadrats =
                  listOf(
                      ExistingBiomassQuadratPayload(
                          description = "SW description",
                          position = ObservationPlotPosition.SouthwestCorner,
                          species = emptyList(),
                      ),
                      ExistingBiomassQuadratPayload(
                          description = "SE description",
                          position = ObservationPlotPosition.SoutheastCorner,
                          species =
                              listOf(
                                  ExistingBiomassQuadratSpeciesPayload(
                                      abundancePercent = 90,
                                      isInvasive = true,
                                      isThreatened = false,
                                      speciesId = SpeciesId(1),
                                      speciesName = null,
                                  ),
                              ),
                      ),
                      ExistingBiomassQuadratPayload(
                          description = "NE description",
                          position = ObservationPlotPosition.NortheastCorner,
                          species =
                              listOf(
                                  ExistingBiomassQuadratSpeciesPayload(
                                      abundancePercent = 40,
                                      isInvasive = true,
                                      isThreatened = false,
                                      speciesId = SpeciesId(1),
                                      speciesName = null,
                                  ),
                              ),
                      ),
                      ExistingBiomassQuadratPayload(
                          description = "NW description",
                          position = ObservationPlotPosition.NorthwestCorner,
                          species =
                              listOf(
                                  ExistingBiomassQuadratSpeciesPayload(
                                      abundancePercent = 60,
                                      isInvasive = false,
                                      isThreatened = false,
                                      speciesId = SpeciesId(2),
                                      speciesName = null,
                                  ),
                                  ExistingBiomassQuadratSpeciesPayload(
                                      abundancePercent = 5,
                                      isInvasive = false,
                                      isThreatened = true,
                                      speciesId = null,
                                      speciesName = "Herbaceous species",
                                  ),
                              ),
                      ),
                  ),
              salinity = null,
              smallTreeCountLow = 0,
              smallTreeCountHigh = 10,
              soilAssessment = "soil",
              tide = null,
              tideTime = null,
              treeSpeciesCount = 3,
              trees =
                  listOf(
                      ExistingTreePayload(
                          description = null,
                          diameterAtBreastHeight = null,
                          gpsCoordinates = point(1),
                          height = null,
                          isDead = false,
                          isInvasive = false,
                          isThreatened = true,
                          pointOfMeasurement = null,
                          shrubDiameter = 25,
                          speciesId = SpeciesId(11),
                          speciesName = null,
                          treeGrowthForm = TreeGrowthForm.Shrub,
                          treeNumber = 1,
                          trunkNumber = 1,
                      ),
                      ExistingTreePayload(
                          description = null,
                          diameterAtBreastHeight = BigDecimal.TWO,
                          gpsCoordinates = null,
                          height = BigDecimal.TEN,
                          isDead = true,
                          isInvasive = false,
                          isThreatened = false,
                          pointOfMeasurement = BigDecimal.valueOf(1.3),
                          shrubDiameter = null,
                          speciesId = null,
                          speciesName = "Tree species",
                          treeGrowthForm = TreeGrowthForm.Tree,
                          treeNumber = 2,
                          trunkNumber = 1,
                      ),
                      ExistingTreePayload(
                          description = "Multi-trunk description 1",
                          diameterAtBreastHeight = BigDecimal.TEN,
                          gpsCoordinates = point(2),
                          height = null,
                          isDead = false,
                          isInvasive = true,
                          isThreatened = false,
                          pointOfMeasurement = BigDecimal.valueOf(1.5),
                          shrubDiameter = null,
                          speciesId = SpeciesId(12),
                          speciesName = null,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 1,
                      ),
                      ExistingTreePayload(
                          description = "Multi-trunk description 2",
                          diameterAtBreastHeight = BigDecimal.TWO,
                          gpsCoordinates = point(2),
                          height = null,
                          isDead = false,
                          isInvasive = true,
                          isThreatened = false,
                          pointOfMeasurement = BigDecimal.valueOf(1.1),
                          shrubDiameter = null,
                          speciesId = SpeciesId(12),
                          speciesName = null,
                          treeGrowthForm = TreeGrowthForm.Trunk,
                          treeNumber = 3,
                          trunkNumber = 2,
                      ),
                  ),
              waterDepth = null,
          )

      val actual = ExistingBiomassMeasurementPayload.of(model)
      assertEquals(
          expected.copy(quadrats = emptyList()),
          actual.copy(quadrats = emptyList()),
          "Payload without quadrats",
      )

      assertEquals(expected.quadrats.toSet(), actual.quadrats.toSet(), "Payload quadrats")
    }
  }
}
