package com.terraformation.backend.tracking

import com.terraformation.backend.db.SRID
import com.terraformation.backend.tracking.model.ShapefileFeature
import java.math.BigDecimal
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

/** Helper class to generate shapefiles for testing planting site behavior with imported maps. */
class ShapefileGenerator(
    /** Coordinate system to use for polygons. */
    srid: Int = SRID.UTM_20S,
    /**
     * Number of permanent clusters to specify in zone shapefile features by default. Null means
     * ShapefileImporter will calculate the number of clusters.
     */
    private val defaultPermanentClusters: Int? = null,
    /**
     * Number of temporary plots to specify in zone shapefile features by default. Null means
     * ShapefileImporter will calculate the number of plots.
     */
    private val defaultTemporaryPlots: Int? = null,
) {
  private val crs = CRS.decode("EPSG:$srid", true)
  private val geometryFactory = GeometryFactory(PrecisionModel(), srid)

  private var nextSubzoneNumber = 1

  private var lastZoneName: String = "Z1"

  fun subzoneFeature(
      boundary: MultiPolygon,
      name: String = "S${nextSubzoneNumber++}",
      zone: String = lastZoneName,
      permanentClusters: Int? = defaultPermanentClusters,
      temporaryPlots: Int? = defaultTemporaryPlots,
      targetPlantingDensity: BigDecimal? = null,
  ): ShapefileFeature {
    lastZoneName = zone

    return ShapefileFeature(
        boundary,
        listOfNotNull(
                "subzone" to name,
                "zone" to zone,
                "density" to "1200",
                permanentClusters?.let { "permanent" to "$it" },
                temporaryPlots?.let { "temporary" to "$it" },
                targetPlantingDensity?.let { "density" to "$it" },
            )
            .toMap(),
        crs,
    )
  }

  fun exclusionFeature(boundary: MultiPolygon): ShapefileFeature {
    return ShapefileFeature(boundary, emptyMap(), crs)
  }

  /**
   * Returns a multipolygon containing a single rectangular polygon in UTM 20S coordinate space with
   * the specified corners.
   */
  fun multiRectangle(southwest: Pair<Int, Int>, northeast: Pair<Int, Int>): MultiPolygon {
    return multiPolygon(
        southwest,
        northeast.first to southwest.second,
        northeast,
        southwest.first to northeast.second)
  }

  /**
   * Returns a polygon in UTM 20S coordinate space with the listed vertices. There is no need to
   * close the list (the first point will be added to the list of coordinates automatically).
   */
  fun polygon(vararg points: Pair<Int, Int>): Polygon {
    val coordinates =
        points.map { Coordinate(it.first.toDouble(), it.second.toDouble()) } +
            Coordinate(points[0].first.toDouble(), points[0].second.toDouble())
    return geometryFactory.createPolygon(coordinates.toTypedArray())
  }

  /**
   * Returns a multipolygon containing a single polygon in UTM 20S coordinate space with the listed
   * vertices. There is no need to close the list (the first point will be added to the list of
   * coordinates automatically).
   */
  fun multiPolygon(vararg points: Pair<Int, Int>): MultiPolygon {
    return geometryFactory.createMultiPolygon(arrayOf(polygon(*points)))
  }

  /** Returns a point in UTM 20S coordinate space. */
  fun point(point: Pair<Int, Int>): Point {
    return geometryFactory.createPoint(Coordinate(point.first.toDouble(), point.second.toDouble()))
  }
}
