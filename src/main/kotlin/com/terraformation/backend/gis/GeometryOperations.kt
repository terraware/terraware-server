package com.terraformation.backend.gis

import com.terraformation.backend.util.toMultiPolygon
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.GeometryEditor
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.precision.GeometryPrecisionReducer

/**
 * Strips Z and M dimensions and optionally reduces coordinate precision. The default eight decimal
 * digits give sub-centimeter resolution and avoid small errors in derived boundaries. Pass null to
 * preserve precision and topology, for example when validating uploaded geometries before merging.
 */
fun convertToXY(
    geometry: Geometry,
    precisionModel: PrecisionModel? = PrecisionModel(100000000.0),
): Geometry {
  val xy = GeometryEditor(geometry.factory).edit(geometry, XYEditorOperation)
  return precisionModel?.let { GeometryPrecisionReducer(it).reduce(xy) } ?: xy
}

private object XYEditorOperation : GeometryEditor.CoordinateOperation() {
  override fun edit(coordinates: Array<out Coordinate>, geometry: Geometry): Array<Coordinate> =
      coordinates.map { it as? CoordinateXY ?: CoordinateXY(it.x, it.y) }.toTypedArray()
}

private suspend fun <T> parallelReduce(items: Collection<T>, reducer: (T, T) -> T): T =
    coroutineScope {
      if (items.size == 1) {
        items.first()
      } else {
        val reducedItems =
            items
                .chunked(2)
                .map { pair ->
                  async {
                    if (pair.size == 1) {
                      pair[0]
                    } else {
                      reducer(pair[0], pair[1])
                    }
                  }
                }
                .awaitAll()

        parallelReduce(reducedItems, reducer)
      }
    }

/**
 * Merges a set of geometries into a MultiPolygon. If two geometries are adjacent but have a tiny
 * gap, e.g., due to floating-point precision limitations, the gap is eliminated.
 */
suspend fun mergeToMultiPolygon(geometries: Collection<Geometry>): MultiPolygon {
  return parallelReduce(geometries) { a, b ->
        val tolerance = GeometrySnapper.computeOverlaySnapTolerance(a, b)
        a.union(GeometrySnapper(b).snapTo(a, tolerance))
      }
      .toMultiPolygon()
}

/** Extracts polygon components, rejecting any geometry other than Polygon or MultiPolygon. */
fun Geometry.extractPolygons(onNonPolygon: () -> Nothing): List<Polygon> =
    when (this) {
      is Polygon -> listOf(this)
      is MultiPolygon -> (0 until numGeometries).map { getGeometryN(it) as Polygon }
      else -> onNonPolygon()
    }
