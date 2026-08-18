package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import jakarta.inject.Named
import java.util.Locale
import kotlin.math.min
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.simplify.TopologyPreservingSimplifier

/**
 * Renders polygonal geometry as a standalone SVG document, scaled to fit a canvas of a requested
 * size.
 */
@Named
class GeometrySvgRenderer {
  companion object {
    const val DEFAULT_SIZE = 256

    /**
     * Blank space to leave between the edges of the canvas and the geometry's bounding box, in SVG
     * user units.
     */
    private const val PADDING = 2.0

    /**
     * Detail smaller than this many SVG user units is dropped. Boundaries are stored with a
     * half-meter tolerance, which can be tens of thousands of vertices for a large site, nearly all
     * of which land on the same pixel once the site is scaled down to thumbnail size.
     */
    private const val DETAIL_TOLERANCE = 0.5

    /** Number of decimal places to emit for coordinates. */
    private const val COORDINATE_SCALE = 2
  }

  /**
   * Renders a geometry as an SVG document.
   *
   * @param width Width of the canvas in SVG user units.
   * @param height Height of the canvas in SVG user units.
   * @return An SVG document. If the geometry is empty or has no area in either dimension, the
   *   document has no content and renders as blank.
   */
  fun render(geometry: Geometry, width: Int = DEFAULT_SIZE, height: Int = DEFAULT_SIZE): String {
    val pathData = renderPathData(geometry, width, height)
    val content = if (pathData != null) """<path fill-rule="evenodd" d="$pathData"/>""" else ""

    // Nothing user-supplied is interpolated into the document, so there is nothing to escape. If
    // that changes (a site name in a <title>, say), the value will need XML escaping.
    return """<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" """ +
        """viewBox="0 0 $width $height">$content</svg>"""
  }

  /**
   * Returns the `d` attribute of the path covering the geometry, or null if there is nothing to
   * draw.
   */
  private fun renderPathData(geometry: Geometry, width: Int, height: Int): String? {
    if (geometry.isEmpty) {
      return null
    }

    val mercator = geometry.projectTo(SRID.SPHERICAL_MERCATOR)
    val envelope = mercator.envelopeInternal

    // Shrink the padding on very small canvases so there is still room to draw something.
    val padding = min(PADDING, min(width, height) / 8.0)
    val drawableWidth = width - 2 * padding
    val drawableHeight = height - 2 * padding

    // A geometry with no extent in one dimension is scaled to fit the other one; one with no extent
    // in either dimension is a point and can't be drawn at all.
    val scale =
        when {
          envelope.width <= 0.0 && envelope.height <= 0.0 -> return null
          envelope.width <= 0.0 -> drawableHeight / envelope.height
          envelope.height <= 0.0 -> drawableWidth / envelope.width
          else -> min(drawableWidth / envelope.width, drawableHeight / envelope.height)
        }

    val simplified = TopologyPreservingSimplifier.simplify(mercator, DETAIL_TOLERANCE / scale)

    // Center the geometry's scaled bounding box in the canvas.
    val originX = (width - envelope.width * scale) / 2.0
    val originY = (height - envelope.height * scale) / 2.0

    val polygons = simplified.polygons()
    if (polygons.isEmpty()) {
      return null
    }

    // SVG's Y axis points down; Web Mercator's points up.
    fun project(coordinate: Coordinate): String {
      val x = originX + (coordinate.x - envelope.minX) * scale
      val y = height - originY - (coordinate.y - envelope.minY) * scale
      return "${format(x)} ${format(y)}"
    }

    fun subpath(ring: LinearRing): String =
        ring.coordinates.joinToString(separator = "L", prefix = "M", postfix = "Z") {
          project(it)
        }

    return polygons.joinToString(separator = " ") { polygon ->
      val rings =
          listOf(polygon.exteriorRing) +
              (0..<polygon.numInteriorRing).map { polygon.getInteriorRingN(it) }
      rings.joinToString(separator = " ") { subpath(it) }
    }
  }

  private fun Geometry.polygons(): List<Polygon> =
      when (this) {
        is Polygon -> if (isEmpty) emptyList() else listOf(this)
        is GeometryCollection -> (0..<numGeometries).flatMap { getGeometryN(it).polygons() }
        else -> emptyList()
      }

  private fun format(value: Double): String = "%.${COORDINATE_SCALE}f".format(Locale.ROOT, value)
}
