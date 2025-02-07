package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Tests to ensure logic to translate from API payloads to models. */
class BiomassPayloadsTest {

  @Nested
  inner class TreePayloadsTest {
    @Test
    fun `converts to NewRecordedTreeModels and assign tree and trunk numbers correctly`() {
      val treeList =
          listOf<NewTreePayload>(
              NewShrubPayload(
                  description = "Live shrub description",
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
                      )),
              NewTreeWithTrunksPayload(
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
                          ))),
          )

      val actual = treeList.flatMapIndexed { index, tree -> tree.toTreeModels(index + 1) }
      val expected =
          listOf(
              NewRecordedTreeModel(
                  id = null,
                  description = "Live shrub description",
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
}
