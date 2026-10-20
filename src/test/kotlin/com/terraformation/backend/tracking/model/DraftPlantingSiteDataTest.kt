package com.terraformation.backend.tracking.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import com.terraformation.backend.rectangle
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

class DraftPlantingSiteDataTest {
  private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val codec = DraftPlantingSiteData(objectMapper)

  /** Rounds coordinates the same way the JSON serializer does so expected values compare equal. */
  private fun rounded(geometry: Geometry): MultiPolygon =
      objectMapper
          .readValue(objectMapper.writeValueAsString(geometry), Geometry::class.java)
          .toMultiPolygon()

  private val siteBoundary = rounded(rectangle(500))
  private val exclusion = rounded(rectangle(width = 10, x = 400, y = 400))
  private val stratumBoundary = rounded(rectangle(width = 250, height = 500))
  private val substratumBoundary = rounded(rectangle(width = 250, height = 250))

  private fun geoJson(geometry: Any): String = objectMapper.writeValueAsString(geometry)

  private fun blob(
      strataKey: String = "strata",
      substrataKey: String = "substrata",
      densityKey: String = "initialPlantingDensity",
  ): String =
      """
      {
        "boundary": ${geoJson(siteBoundary)},
        "exclusion": ${geoJson(exclusion)},
        "siteEditStep": "zone_boundaries",
        "boundarySource": "shapefile",
        "siteType": "detailed",
        "$strataKey": [
          {
            "name": "A",
            "boundary": ${geoJson(stratumBoundary)},
            "$densityKey": 1500,
            "$substrataKey": [
              {"name": "1", "boundary": ${geoJson(substratumBoundary)}}
            ]
          }
        ]
      }
      """
          .trimIndent()

  private fun parse(json: String) = codec.parse(JSONB.jsonb(json))

  private val expectedMap =
      DraftPlantingSiteMap(
          siteType = DraftPlantingSiteType.Detailed,
          siteEditStep = "zone_boundaries",
          boundarySource = "shapefile",
          boundary = siteBoundary,
          exclusion = exclusion,
          strata =
              listOf(
                  DraftStratumModel(
                      id = null,
                      name = "A",
                      boundary = stratumBoundary,
                      initialPlantingDensity = BigDecimal(1500),
                      substrata =
                          listOf(
                              DraftSubstratumModel(
                                  id = null,
                                  name = "1",
                                  boundary = substratumBoundary,
                              )
                          ),
                  )
              ),
      )

  private val bowtie =
      """{"type":"MultiPolygon","coordinates":[[[[0,0],[10,10],[10,0],[0,10],[0,0]]]]}"""

  private fun stratumJson(
      name: String? = "\"A\"",
      boundary: String = geoJson(stratumBoundary),
      density: String? = "1500",
      substratumName: String? = "\"1\"",
  ): String {
    val fields = mutableListOf<String>()
    if (name != null) fields.add("\"name\": $name")
    fields.add("\"boundary\": $boundary")
    if (density != null) fields.add("\"initialPlantingDensity\": $density")
    val substratumFields = mutableListOf<String>()
    if (substratumName != null) substratumFields.add("\"name\": $substratumName")
    substratumFields.add("\"boundary\": ${geoJson(substratumBoundary)}")
    fields.add("\"substrata\": [{${substratumFields.joinToString()}}]")
    return """{"siteType": "detailed", "strata": [{${fields.joinToString()}}]}"""
  }

  @Test
  fun `accepts self-intersecting polygon without validating`() {
    val parsed = parse(stratumJson(boundary = bowtie))

    assertEquals(5, parsed.strata.single().boundary.numPoints, "Stratum boundary point count")
  }

  @Test
  fun `rejects empty geometry`() {
    assertThrows<IllegalArgumentException> {
      parse(stratumJson(boundary = """{"type":"MultiPolygon","coordinates":[]}"""))
    }
  }

  @Test
  fun `rejects empty geometry collection`() {
    assertThrows<IllegalArgumentException> {
      parse(
          stratumJson(
              boundary =
                  """{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2]}]}"""
          )
      )
    }
  }

  @Test
  fun `rejects missing or null stratum name`() {
    assertThrows<IllegalArgumentException>("missing stratum name") {
      parse(stratumJson(name = null))
    }
    assertThrows<IllegalArgumentException>("null stratum name") {
      parse(stratumJson(name = "null"))
    }
  }

  @Test
  fun `rejects missing or null substratum name`() {
    assertThrows<IllegalArgumentException>("missing substratum name") {
      parse(stratumJson(substratumName = null))
    }
    assertThrows<IllegalArgumentException>("null substratum name") {
      parse(stratumJson(substratumName = "null"))
    }
  }

  @Test
  fun `parses numeric-string density`() {
    val parsed = parse(stratumJson(density = "\"1500\""))

    assertEquals(BigDecimal(1500), parsed.strata.single().initialPlantingDensity)
  }

  @Test
  fun `rejects non-numeric density`() {
    listOf("\"abc\"", "true", "{}").forEach { density ->
      assertThrows<IllegalArgumentException>("density $density") {
        parse(stratumJson(density = density))
      }
    }
  }

  @Test
  fun `rejects malformed geometry`() {
    listOf(
            """{"type":"MultiPolygon","coordinates":"x"}""",
            """{"coordinates":[]}""",
        )
        .forEach { boundary ->
          assertThrows<IllegalArgumentException>("boundary $boundary") {
            parse(stratumJson(boundary = boundary))
          }
        }
  }

  @Test
  fun `rejects strata that are not arrays`() {
    listOf("\"corrupt\"", "1", "{\"name\": \"A\"}").forEach { strata ->
      assertThrows<IllegalArgumentException>("strata $strata") {
        parse("""{"siteType": "detailed", "strata": $strata}""")
      }
    }
  }

  @Test
  fun `rejects substrata that are not arrays`() {
    listOf("\"corrupt\"", "1", "{\"name\": \"1\"}").forEach { substrata ->
      assertThrows<IllegalArgumentException>("substrata $substrata") {
        parse(
            """
            {"siteType": "detailed", "strata": [
              {"name": "A", "boundary": ${geoJson(stratumBoundary)}, "substrata": $substrata}
            ]}
            """
        )
      }
    }
  }

  @Test
  fun `treats null strata and substrata as empty`() {
    assertEquals(
        emptyList<DraftStratumModel>(),
        parse("""{"siteType": "detailed", "strata": null}""").strata,
        "Strata",
    )
    assertEquals(
        emptyList<DraftSubstratumModel>(),
        parse(
                """
                {"siteType": "detailed", "strata": [
                  {"name": "A", "boundary": ${geoJson(stratumBoundary)}, "substrata": null}
                ]}
                """
            )
            .strata
            .single()
            .substrata,
        "Substrata",
    )
  }

  @Test
  fun `parses current key names`() {
    assertEquals(expectedMap, parse(blob()))
  }

  @Test
  fun `parses legacy key names`() {
    val actual =
        parse(
            blob(
                strataKey = "plantingZones",
                substrataKey = "plantingSubzones",
                densityKey = "targetPlantingDensity",
            )
        )

    assertEquals(expectedMap, actual)
  }

  @Test
  fun `parses mixed key names per element`() {
    val json =
        """
        {
          "siteType": "detailed",
          "strata": [
            {
              "name": "A",
              "boundary": ${geoJson(stratumBoundary)},
              "targetPlantingDensity": 1200,
              "plantingSubzones": [
                {"name": "1", "boundary": ${geoJson(substratumBoundary)}}
              ]
            },
            {
              "name": "B",
              "boundary": ${geoJson(stratumBoundary)},
              "initialPlantingDensity": 900,
              "substrata": [
                {"name": "2", "boundary": ${geoJson(substratumBoundary)}}
              ]
            }
          ]
        }
        """
            .trimIndent()

    val strata = parse(json).strata

    assertEquals(
        listOf(BigDecimal(1200), BigDecimal(900)),
        strata.map { it.initialPlantingDensity },
        "Densities",
    )
    assertEquals(listOf("1", "2"), strata.map { it.substrata.single().name }, "Substratum names")
  }

  @Test
  fun `parses stratum with no density key using default`() {
    val json =
        """
        {
          "siteType": "detailed",
          "strata": [
            {
              "name": "A",
              "boundary": ${geoJson(stratumBoundary)},
              "substrata": []
            }
          ]
        }
        """
            .trimIndent()

    assertEquals(
        StratumModel.DEFAULT_INITIAL_PLANTING_DENSITY,
        parse(json).strata.single().initialPlantingDensity,
    )
  }

  @Test
  fun `rejects stratum without boundary`() {
    val json =
        """
        {
          "siteType": "detailed",
          "strata": [{"name": "A", "initialPlantingDensity": 1500, "substrata": []}]
        }
        """
            .trimIndent()

    assertThrows<IllegalArgumentException> { parse(json) }
  }

  @Test
  fun `drops strata for simple sites`() {
    val json =
        """
        {
          "siteType": "simple",
          "boundary": ${geoJson(siteBoundary)},
          "strata": [
            {"name": "Placeholder", "boundary": ${geoJson(stratumBoundary)}, "substrata": []}
          ]
        }
        """
            .trimIndent()

    val map = parse(json)

    assertEquals(DraftPlantingSiteType.Simple, map.siteType, "Site type")
    assertEquals(emptyList<DraftStratumModel>(), map.strata, "Strata")
  }

  @Test
  fun `converts polygons to multipolygons`() {
    val json =
        """
        {
          "siteType": "detailed",
          "boundary": ${geoJson(rectanglePolygon(500))},
          "strata": [
            {
              "name": "A",
              "boundary": ${geoJson(rectanglePolygon(250))},
              "initialPlantingDensity": 1500,
              "substrata": []
            }
          ]
        }
        """
            .trimIndent()

    val map = parse(json)

    assertEquals(MultiPolygon::class.java, map.boundary?.javaClass, "Site boundary type")
    assertEquals(rounded(rectangle(500)), map.boundary, "Site boundary")
    assertEquals(
        MultiPolygon::class.java,
        map.strata.single().boundary.javaClass,
        "Stratum boundary type",
    )
    assertEquals(rounded(rectangle(250)), map.strata.single().boundary, "Stratum boundary")
  }

  @Test
  fun `ignores client ids, fullName, plantingCompleted, plantingSeasons`() {
    val json =
        """
        {
          "siteType": "detailed",
          "plantingSeasons": [{"id": 1, "startDate": "2024-01-01", "endDate": "2024-02-01"}],
          "strata": [
            {
              "id": 77,
              "name": "A",
              "boundary": ${geoJson(stratumBoundary)},
              "initialPlantingDensity": 1500,
              "plantingCompleted": true,
              "substrata": [
                {
                  "id": 88,
                  "name": "1",
                  "fullName": "A-1",
                  "plantingCompleted": true,
                  "boundary": ${geoJson(substratumBoundary)}
                }
              ]
            }
          ]
        }
        """
            .trimIndent()

    val stratum = parse(json).strata.single()

    assertEquals(null, stratum.id, "Stratum ID")
    assertEquals(null, stratum.substrata.single().id, "Substratum ID")
    assertEquals("1", stratum.substrata.single().name, "Substratum name")
  }

  @Test
  fun `missing boundary and exclusion are null`() {
    val map = parse("""{"siteType": "detailed"}""")

    assertEquals(null, map.boundary, "Boundary")
    assertEquals(null, map.exclusion, "Exclusion")
  }

  @Test
  fun `rejects missing or unknown siteType`() {
    assertThrows<IllegalArgumentException>("missing") { parse("""{"strata": []}""") }
    assertThrows<IllegalArgumentException>("unknown") { parse("""{"siteType": "bogus"}""") }
  }

  @Test
  fun `generates data`() {
    val map =
        expectedMap.copy(
            strata =
                listOf(
                    expectedMap.strata
                        .single()
                        .copy(
                            id = DraftStratumId(10),
                            substrata =
                                listOf(
                                    expectedMap.strata
                                        .single()
                                        .substrata
                                        .single()
                                        .copy(id = DraftSubstratumId(20))
                                ),
                        )
                )
        )

    val expected =
        objectMapper.readTree(
            """
            {
              "boundary": ${geoJson(siteBoundary)},
              "exclusion": ${geoJson(exclusion)},
              "boundarySource": "shapefile",
              "siteEditStep": "zone_boundaries",
              "siteType": "detailed",
              "plantingSeasons": [],
              "strata": [
                {
                  "id": 10,
                  "name": "A",
                  "boundary": ${geoJson(stratumBoundary)},
                  "initialPlantingDensity": 1500,
                  "substrata": [
                    {"id": 20, "name": "1", "boundary": ${geoJson(substratumBoundary)}}
                  ]
                }
              ]
            }
            """
        )

    assertEquals(expected, objectMapper.readTree(codec.generate(map).data()))
  }

  @Test
  fun `generate omits boundarySource, boundary and exclusion when null`() {
    val map =
        DraftPlantingSiteMap(
            siteType = DraftPlantingSiteType.Detailed,
            siteEditStep = null,
            boundarySource = null,
            boundary = null,
            exclusion = null,
            strata = emptyList(),
        )

    val json = objectMapper.readTree(codec.generate(map).data())

    assertEquals(
        emptySet<String>(),
        setOf("boundarySource", "boundary", "exclusion").filter { json.has(it) }.toSet(),
        "Omitted keys that were present",
    )
  }

  @Test
  fun `generate emits empty strata for simple sites`() {
    val map =
        DraftPlantingSiteMap(
            siteType = DraftPlantingSiteType.Simple,
            siteEditStep = null,
            boundarySource = null,
            boundary = siteBoundary,
            exclusion = null,
            strata = emptyList(),
        )

    val json = objectMapper.readTree(codec.generate(map).data())

    assertEquals(objectMapper.readTree("[]"), json.get("strata"), "Strata")
    assertEquals("simple", json.get("siteType").asText(), "Site type")
  }

  @Test
  fun `round trips`() {
    val withIds =
        expectedMap.copy(
            strata =
                listOf(
                    expectedMap.strata
                        .single()
                        .copy(
                            id = DraftStratumId(10),
                            substrata =
                                listOf(
                                    expectedMap.strata
                                        .single()
                                        .substrata
                                        .single()
                                        .copy(id = DraftSubstratumId(20))
                                ),
                        )
                )
        )

    assertEquals(expectedMap, codec.parse(codec.generate(withIds)))
  }
}
