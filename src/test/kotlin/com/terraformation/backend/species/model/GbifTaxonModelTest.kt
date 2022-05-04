package com.terraformation.backend.species.model

import com.terraformation.backend.db.GbifTaxonId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class GbifTaxonModelTest {
  @ParameterizedTest
  @ValueSource(strings = ["critically endangered", "endangered", "extinct", "extinct in the wild"])
  fun `treats IUCN Red List categories of Endangered or worse as endangered`(threatStatus: String) {
    val model =
        GbifTaxonModel(
            taxonId = GbifTaxonId(1),
            scientificName = "name",
            familyName = "family",
            vernacularNames = emptyList(),
            threatStatus = threatStatus)
    assertEquals(true, model.isEndangered)
  }

  @ParameterizedTest
  @ValueSource(strings = ["least concern", "near threatened", "vulnerable"])
  fun `treats IUCN Red list categories of Vulnerable or better as non-endangered`(
      threatStatus: String
  ) {
    val model =
        GbifTaxonModel(
            taxonId = GbifTaxonId(1),
            scientificName = "name",
            familyName = "family",
            vernacularNames = emptyList(),
            threatStatus = threatStatus)
    assertEquals(false, model.isEndangered)
  }

  @Test
  fun `treats unrecognized threat statuses as unknown`() {
    val model =
        GbifTaxonModel(
            taxonId = GbifTaxonId(1),
            scientificName = "name",
            familyName = "family",
            vernacularNames = emptyList(),
            threatStatus = "bogus")
    assertNull(model.isEndangered)
  }
}
