package com.terraformation.backend.species.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NormalizeScientificNameTest {
  @Test
  fun `removes unwanted notations from scientific names`() {
    val expected =
        mapOf(
            "Pelargonium ×orphanum Hoffmanns. ex F.Dietr." to "Pelargonium orphanum",
            "Musa × paradisiaca L." to "Musa paradisiaca",
            "Abelmoschus manihot subsp. tetraphyllus (Roxb. ex Hornem.) Borss.Waalk." to
                "Abelmoschus manihot subsp. tetraphyllus",
            "Prosopis glandulosa var. glandulosa" to "Prosopis glandulosa var. glandulosa",
            "Pinus nigra f. nigra" to "Pinus nigra f. nigra",
            "Aloe vera (L.) Burm. f." to "Aloe vera",
            "Costus woodsonii Maas" to "Costus woodsonii",
            " Allium   porrum " to "Allium porrum",
            "Allium" to "Allium",
        )

    val actual = expected.mapValues { (name, _) -> normalizeScientificName(name) }

    assertEquals(expected, actual)
  }

  @Test
  fun `throws IllegalArgumentException on empty name`() {
    assertThrows<IllegalArgumentException> { normalizeScientificName("") }
  }
}
