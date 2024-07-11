package com.terraformation.backend.gis

import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.geotools.geojson.feature.FeatureJSON
import org.locationtech.jts.geom.Geometry

/**
 * Detects which countries contain a given geometry.
 *
 * Country borders are loaded from a file that is maintained as part of the
 * [Natural Earth](https://www.naturalearthdata.com/) project, which makes its data available in the
 * public domain. The specific file we use is the
 * [GeoJSON 10-meter administrative countries file](https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_10m_admin_0_countries.geojson).
 */
@Named
class CountryDetector {
  private val log = perClassLogger()

  val countryBorders: Map<String, Geometry> by lazy {
    log.debug("Loading borders")

    val result = mutableMapOf<String, Geometry>()

    javaClass.getResourceAsStream("/gis/countries.geojson").use { stream ->
      FeatureJSON().readFeatureCollection(stream).features().use { iterator ->
        while (iterator.hasNext()) {
          val feature = iterator.next()
          val countryCode = feature.getProperty("ISO_A2_EH")?.value?.toString()
          val border = feature.defaultGeometryProperty.value

          if (countryCode != null && border is Geometry) {
            // A country code can appear more than once if it has administrative regions such as
            // territories or dependencies; in that case we want to treat them as part of the
            // same country.
            result.merge(countryCode, border) { a, b -> a.union(b) }
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

  /**
   * Returns the 2-letter country codes for the countries that contain a geometry. If the geometry
   * falls outside any country, returns an empty set.
   */
  fun getCountries(geometry: Geometry): Set<String> {
    return countryBorders.filterValues { border -> geometry.intersects(border) }.keys
  }
}
