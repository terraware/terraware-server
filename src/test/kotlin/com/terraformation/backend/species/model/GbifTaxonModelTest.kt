package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.GbifTaxonId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class GbifTaxonModelTest {
  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "critically endangered",
              "endangered",
              "extinct",
              "extinct in the wild",
              "vulnerable",
          ])
  fun `treats IUCN Red List categories of Vulnerable or worse as endangered`(threatStatus: String) {
    val model = newModel(threatStatus = threatStatus)
    assertEquals(true, model.isEndangered)
  }

  @ParameterizedTest
  @ValueSource(strings = ["least concern", "near threatened"])
  fun `treats IUCN Red list categories of Near Threatened or better as non-endangered`(
      threatStatus: String
  ) {
    val model = newModel(threatStatus = threatStatus)
    assertEquals(false, model.isEndangered)
  }

  @Test
  fun `treats unrecognized threat statuses as unknown`() {
    val model = newModel(threatStatus = "bogus")
    assertNull(model.isEndangered)
  }

  @Test
  fun `looks up conservation category for threat status`() {
    val model = newModel(threatStatus = "least concern")
    assertEquals(ConservationCategory.LeastConcern, model.conservationCategory)
  }

  private fun newModel(threatStatus: String?) =
      GbifTaxonModel(
          taxonId = GbifTaxonId(1),
          scientificName = "name",
          familyName = "family",
          vernacularNames = emptyList(),
          threatStatus = threatStatus)
}
