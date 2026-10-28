package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BiomassSpeciesModelTest {
  @Test
  fun `requires exactly one species identifier`() {
    assertThrows<IllegalArgumentException> {
      BiomassSpeciesModel(isInvasive = false, isThreatened = false)
    }
    assertThrows<IllegalArgumentException> {
      BiomassSpeciesModel(
          isInvasive = false,
          isThreatened = false,
          speciesId = SpeciesId(1),
          scientificName = "Species",
      )
    }
  }
}
