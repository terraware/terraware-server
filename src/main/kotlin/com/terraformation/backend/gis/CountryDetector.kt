package com.terraformation.backend.gis

import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.geotools.geojson.feature.FeatureJSON
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.precision.GeometryPrecisionReducer

/**
 * Detects which countries contain a given geometry.
 *
 * Country borders are loaded from a file that is maintained as part of the
 * [Natural Earth](https://www.naturalearthdata.com/) project, which makes its data available in the
 * public domain. The specific file we use is the
 * [GeoJSON 1:10m administrative countries file](https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_10m_admin_0_countries.geojson).
 */
@Named
class CountryDetector {
  companion object {
    /**
     * Fuzz factor to account for border-adjacent geometries not precisely matching the actual
     * border. Countries that contain less than this percent of a geometry are ignored.
     */
    const val MIN_COVERAGE_PERCENT = 3.0

    private val log = perClassLogger()
  }

  private val countryBorders: Map<String, Geometry> by lazy {
    log.debug("Loading borders")

    val result = mutableMapOf<String, Geometry>()

    // We don't want coordinates so precise that they run into floating-point precision errors
    // when we do calculations on them.
    val precisionModel = PrecisionModel(1000000.0)
    val precisionReducer = GeometryPrecisionReducer(precisionModel)
    precisionReducer.setChangePrecisionModel(true)

    javaClass.getResourceAsStream("/gis/countries.geojson").use { stream ->
      FeatureJSON().readFeatureCollection(stream).features().use { iterator ->
        while (iterator.hasNext()) {
          val feature = iterator.next()
          val countryCode = feature.getProperty("ISO_A2_EH")?.value?.toString()
          val border = feature.defaultGeometryProperty.value

          if (countryCode != null && border is Geometry) {
            // Some of the border geometries in the dataset are invalid, which causes failures
            // when we try to compute intersections with them.
            val fixedBorder =
                if (border.isValid) {
                  border
                } else {
                  GeometryFixer(border).result
                }

            val reducedPrecisionBorder = precisionReducer.reduce(fixedBorder)

            // A country code can appear more than once if it has administrative regions such as
            // territories or dependencies; in that case we want to treat them as part of the
            // same country.
            result.merge(countryCode, reducedPrecisionBorder) { a, b -> a.union(b) }
          } else if (border !is Geometry) {
            log.error("Unexpected geometry type ${border?.javaClass?.name} for $countryCode")
          } else {
            log.error("Found feature with no country code property")
            log.debug("$feature")
          }
        }
      }
    }

    log.debug("Done loading borders")

    result
  }

  val countryAreas: Map<String, Double> by lazy {
    countryBorders.mapValues { (_, border) -> border.area }
  }

  /** Returns the country border geometry given the 2-letter country code */
  fun getCountryBorder(countryCode: String): Geometry? {
    return countryBorders[countryCode]
  }

  /**
   * Returns the 2-letter country codes for the countries that contain a geometry. If the geometry
   * falls outside any country, returns an empty set.
   *
   * @param minCoveragePercent Minimum percentage of the smaller geometry that must be covered by
   *   the larger geometry to be included in the result. For geometries such as planting sites that
   *   are much smaller than countries, this means at least this percentage of [geometry] must be
   *   covered by a country for that country to be included. For large geometries like botanical
   *   countries that can span multiple countries, at least this percentage of a country's area must
   *   be covered by [geometry] for the country to be included.
   */
  fun getCountries(
      geometry: Geometry,
      minCoveragePercent: Double = MIN_COVERAGE_PERCENT,
  ): Set<String> {
    val geometryArea = geometry.area

    // For complex MultiPolygons, intersection calculations can be expensive. We only care
    // about exceeding the minimum coverage area, so do the calculation one polygon at a time
    // and stop once we hit the threshold.
    val geometries =
        if (geometry is MultiPolygon) {
          (0..<geometry.numGeometries).map { geometry.getGeometryN(it) }
        } else {
          listOf(geometry)
        }

    return countryBorders
        .mapNotNull { (countryCode, countryBorder) ->
          val countryArea = countryAreas[countryCode] ?: geometryArea
          val minCoverageArea = minOf(geometryArea, countryArea) * minCoveragePercent / 100.0
          val hasMinCoverage =
              geometries
                  .asSequence()
                  .flatMap { geometryPolygon ->
                    if (countryBorder is MultiPolygon) {
                      (0..<countryBorder.numGeometries).asSequence().map {
                        countryBorder.getGeometryN(it) to geometryPolygon
                      }
                    } else {
                      sequenceOf(countryBorder to geometryPolygon)
                    }
                  }
                  .runningFold(0.0) { subtotal, (countryPolygon, geometryPolygon) ->
                    subtotal + intersectionArea(countryPolygon, geometryPolygon)
                  }
                  .any { it >= minCoverageArea }

          if (hasMinCoverage) {
            countryCode
          } else {
            null
          }
        }
        .toSet()
  }

  fun intersectionArea(countryCode: String, geometry: Geometry): Double {
    val countryBorder = countryBorders[countryCode] ?: return 0.0

    return intersectionArea(countryBorder, geometry)
  }

  private fun intersectionArea(geometryA: Geometry, geometryB: Geometry): Double {
    return if (geometryA.intersects(geometryB)) {
      geometryA.intersection(geometryB).area
    } else {
      0.0
    }
  }
}
