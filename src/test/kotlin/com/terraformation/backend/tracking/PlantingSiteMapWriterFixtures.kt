package com.terraformation.backend.tracking

import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.util.toMultiPolygon
import java.io.InputStream
import java.math.BigDecimal
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.readValue

internal object PlantingSiteMapWriterFixtures {
  val objectMapper = jacksonMapperBuilder().addModule(GeometryModule()).build()

  val layers by lazy {
    val siteBoundary = geometry("PlantingSite")
    val stratumBoundary = geometry("Strata")
    val wktReader = WKTReader(siteBoundary.factory)
    val polygonWithHole =
        wktReader.read(
            "POLYGON ((-138.001 27.001, -138.001 27.021, -137.981 27.021, " +
                "-137.981 27.001, -138.001 27.001), (-137.997 27.003, -137.990 27.003, " +
                "-137.990 27.010, -137.997 27.010, -137.997 27.003))"
        )
    val substratumBoundary =
        siteBoundary.factory
            .createGeometryCollection(arrayOf(stratumBoundary, polygonWithHole))
            .toMultiPolygon()
    val exclusion =
        wktReader
            .read(
                "POLYGON ((-138.115 26.900, -138.114 26.900, -138.114 26.901, " +
                    "-138.115 26.901, -138.115 26.900))"
            )
            .toMultiPolygon()

    listOf(
        PlantingSiteMapLayer(
            "site-boundary",
            listOf(PlantingSiteMapFeature(siteBoundary, "Site", BigDecimal("12.5"))),
        ),
        PlantingSiteMapLayer("strata", emptyList()),
        PlantingSiteMapLayer(
            "substrata",
            listOf(
                PlantingSiteMapFeature(substratumBoundary, "Sous-strate <N°1>", BigDecimal("2.5"))
            ),
        ),
        PlantingSiteMapLayer(
            "exclusions",
            listOf(PlantingSiteMapFeature(exclusion, mapOf("area_ha" to BigDecimal("1.5")))),
        ),
    )
  }

  fun expectedFile(filename: String): InputStream =
      requireNotNull(javaClass.getResourceAsStream("/gis/map-export/$filename"))

  private fun geometry(name: String) =
      javaClass.getResourceAsStream("/gis/$name.geojson").use {
        objectMapper.readValue<Geometry>(it).toMultiPolygon()
      }
}
