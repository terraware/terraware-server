package com.terraformation.backend.db

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.default_schema.tables.references.SPATIAL_REF_SYS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SRIDTest : DatabaseTest() {
  @Test
  fun `can look up UTM zones`() {
    assertEquals(2976, SRID.byName("Tahiti_1952_UTM_6S"))
  }

  @Test
  fun `can look up SRIDs whose names have whitespace`() {
    assertEquals(4326, SRID.byName("WGS 84"))
  }

  @Test
  fun `ignores EPSG prefix`() {
    assertEquals(3857, SRID.byName("EPSG:WGS 84 / Pseudo-Mercator"))
  }

  @Test
  fun `throws IllegalArgumentException if name is unknown`() {
    assertThrows<IllegalArgumentException> { SRID.byName("bogus nonexistent name") }
  }

  @Test
  fun `all SRIDs in mapping are valid in PostGIS`() {
    val postgisSrids =
        dslContext
            .select(SPATIAL_REF_SYS.SRID)
            .from(SPATIAL_REF_SYS)
            .fetch(SPATIAL_REF_SYS.SRID.asNonNullable())
            .toSortedSet()
    val mappingSrids = SRID.mapping.values.toSortedSet()

    assertSetEquals(emptySet<Int>(), mappingSrids - postgisSrids, "Unrecognized SRIDs in mapping")
  }
}
