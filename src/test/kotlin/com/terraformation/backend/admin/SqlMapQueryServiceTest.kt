package com.terraformation.backend.admin

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import com.terraformation.backend.rectangle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SqlMapQueryServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val service: SqlMapQueryService by lazy { SqlMapQueryService(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  @Test
  fun `converts geometry column to a JTS geometry feature`() {
    val boundary = rectangle(10)
    insertPlantingSite(boundary = boundary)

    val result = service.runQuery("select boundary from tracking.planting_sites")

    assertEquals(1, result.rowCount, "row count")

    val features = result.featureCollection.features
    assertEquals(1, features.size, "feature count")
    assertGeometryEquals(boundary, features[0].geometry)
  }

  @Test
  fun `uses non-geometry columns as feature properties`() {
    val plantingSiteId = insertPlantingSite(name = "Test Site")

    val result = service.runQuery("select boundary, name, id from tracking.planting_sites; ")

    val features = result.featureCollection.features
    assertEquals(
        mapOf(
            "id" to "$plantingSiteId",
            "name" to "Test Site",
        ),
        features[0].properties,
    )
  }

  @Test
  fun `rejects attempts to bypass read-only protection`() {
    assertThrows<IllegalArgumentException> {
      service.runQuery("commit; update users set first_name = 'blah'; commit")
    }
  }

  @Test
  fun `throws exception when the result has no geometry column`() {
    insertPlantingSite()

    assertThrows<IllegalArgumentException> {
      service.runQuery("select name from tracking.planting_sites")
    }
  }
}
