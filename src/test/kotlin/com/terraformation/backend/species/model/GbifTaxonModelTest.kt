package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.GbifTaxonId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class GbifTaxonModelTest {
  @Test
  fun `looks up conservation category for threat status`() {
    val model =
        GbifTaxonModel(
            taxonId = GbifTaxonId(1),
            scientificName = "name",
            familyName = "family",
            vernacularNames = emptyList(),
            threatStatus = "least concern",
        )
    assertEquals(ConservationCategory.LeastConcern, model.conservationCategory)
  }
}
