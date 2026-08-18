package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

class GeometrySvgRendererTest {
  private val renderer = GeometrySvgRenderer()

  /**
   * Geometry is built directly in Web Mercator so that the renderer's reprojection step is a no-op
   * and the expected output coordinates can be calculated by hand.
   */
  private val mercatorFactory = GeometryFactory(PrecisionModel(), SRID.SPHERICAL_MERCATOR)

  @Test
  fun `scales and centers a square boundary`() {
    // Canvas 100x100 with 2 units of padding leaves a 96x96 drawing area, so a 100m square is
    // scaled by 0.96 and drawn from 2 to 98 in both dimensions.
    val svg = renderer.render(rectangle(0.0, 0.0, 100.0, 100.0), 100, 100)

    assertEquals(
        "M2.00 98.00L98.00 98.00L98.00 2.00L2.00 2.00L2.00 98.00Z",
        pathData(svg),
        "Path data",
    )
  }

  @Test
  fun `preserves aspect ratio of a wide boundary`() {
    // A 200x100m boundary on a square canvas is scaled by 0.48, filling the width and leaving equal
    // gaps of 26 units above and below.
    val svg = renderer.render(rectangle(0.0, 0.0, 200.0, 100.0), 100, 100)

    assertEquals(
        "M2.00 74.00L98.00 74.00L98.00 26.00L2.00 26.00L2.00 74.00Z",
        pathData(svg),
        "Path data",
    )
  }

  @Test
  fun `flips the Y axis`() {
    val svg = renderer.render(rectangle(0.0, 0.0, 100.0, 100.0), 100, 100)
    val northernmostY = 2.00
    val southernmostY = 98.00

    // The boundary's northern edge is at mercator y=100 and its southern edge at y=0.
    assertTrue(
        pathData(svg)!!.startsWith("M2.00 $southernmostY"),
        "Southwest corner should be at the bottom of the canvas",
    )
    assertTrue(
        pathData(svg)!!.contains("L98.00 $northernmostY"),
        "Northeast corner should be at the top of the canvas",
    )
  }

  @Test
  fun `honors requested canvas size`() {
    val svg = renderer.render(rectangle(0.0, 0.0, 100.0, 100.0), 64, 48)

    assertTrue(svg.contains("""width="64""""), "Width attribute in $svg")
    assertTrue(svg.contains("""height="48""""), "Height attribute in $svg")
    assertTrue(svg.contains("""viewBox="0 0 64 48""""), "viewBox attribute in $svg")
  }

  @Test
  fun `uses the default canvas size if none is requested`() {
    val svg = renderer.render(rectangle(0.0, 0.0, 100.0, 100.0))

    assertTrue(
        svg.contains(
            """viewBox="0 0 ${GeometrySvgRenderer.DEFAULT_SIZE} """ +
                """${GeometrySvgRenderer.DEFAULT_SIZE}""""
        ),
        "viewBox attribute in $svg",
    )
  }

  @Test
  fun `renders each polygon of a multi-polygon as a separate subpath`() {
    val multiPolygon =
        mercatorFactory.createMultiPolygon(
            arrayOf(
                rectangle(0.0, 0.0, 40.0, 40.0),
                rectangle(60.0, 60.0, 40.0, 40.0),
            )
        )

    assertEquals(2, subpathCount(renderer.render(multiPolygon, 100, 100)), "Number of subpaths")
  }

  @Test
  fun `renders interior rings as holes`() {
    val polygonWithHole =
        mercatorFactory.createPolygon(
            ring(0.0, 0.0, 100.0, 100.0),
            arrayOf(ring(25.0, 25.0, 50.0, 50.0)),
        )

    val svg = renderer.render(polygonWithHole, 100, 100)

    assertEquals(2, subpathCount(svg), "Number of subpaths")
    assertTrue(svg.contains("""fill-rule="evenodd""""), "Fill rule in $svg")
  }

  @Test
  fun `drops detail that is smaller than a pixel`() {
    val vertexCount = 500
    val circle = circle(radiusMeters = 1000.0, vertexCount = vertexCount)

    val renderedVertices = pathData(renderer.render(circle, 100, 100))!!.count { it == 'L' } + 1

    assertTrue(
        renderedVertices < vertexCount / 4,
        "Expected far fewer than $vertexCount vertices, got $renderedVertices",
    )
  }

  @Test
  fun `renders geographic coordinates without distorting the shape`() {
    // A 500m square in Sweden. Without reprojection, a degree of longitude would be treated as the
    // same distance as a degree of latitude and the square would come out nearly three times wider
    // than it is tall.
    val square = Turtle(point(18.0, 59.0)).makeMultiPolygon { square(500) }

    val coordinates = drawnCoordinates(renderer.render(square, 100, 100))
    val drawnWidth = coordinates.maxOf { it.first } - coordinates.minOf { it.first }
    val drawnHeight = coordinates.maxOf { it.second } - coordinates.minOf { it.second }

    assertEquals(1.0, drawnWidth / drawnHeight, 0.02, "Ratio of drawn width to drawn height")
  }

  @Test
  fun `formats coordinates independently of the default locale`() {
    val originalLocale = Locale.getDefault()

    try {
      // German locale uses a comma as the decimal separator, which would be invalid path data.
      Locale.setDefault(Locale.GERMANY)

      assertFalse(pathData(renderer.render(rectangle(0.0, 0.0, 100.0, 100.0)))!!.contains(','))
    } finally {
      Locale.setDefault(originalLocale)
    }
  }

  @Test
  fun `renders an empty document for empty geometry`() {
    val svg = renderer.render(mercatorFactory.createMultiPolygon())

    assertEquals(
        """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" """ +
            """viewBox="0 0 256 256"></svg>""",
        svg,
    )
  }

  @Test
  fun `renders an empty document for geometry with no area`() {
    val svg = renderer.render(point(1.0, 2.0), 100, 100)

    assertEquals(null, pathData(svg), "Path data")
  }

  @Test
  fun `renders an empty document for a boundary with no extent`() {
    val singlePoint = Coordinate(5.0, 5.0)
    val degenerate =
        mercatorFactory.createPolygon(
            arrayOf(singlePoint, singlePoint, singlePoint, singlePoint)
                .map { Coordinate(it) }
                .toTypedArray()
        )

    assertEquals(null, pathData(renderer.render(degenerate, 100, 100)), "Path data")
  }

  @Test
  fun `renders a tiny canvas without clipping the whole boundary`() {
    val svg = renderer.render(rectangle(0.0, 0.0, 100.0, 100.0), 4, 4)

    assertTrue(pathData(svg) != null, "Path data in $svg")
  }

  /** Returns a rectangle whose southwest corner is at the given Web Mercator coordinates. */
  private fun rectangle(west: Double, south: Double, width: Double, height: Double): Polygon =
      mercatorFactory.createPolygon(ring(west, south, width, height))

  private fun ring(west: Double, south: Double, width: Double, height: Double): LinearRing =
      mercatorFactory.createLinearRing(
          arrayOf(
              Coordinate(west, south),
              Coordinate(west + width, south),
              Coordinate(west + width, south + height),
              Coordinate(west, south + height),
              Coordinate(west, south),
          )
      )

  private fun circle(radiusMeters: Double, vertexCount: Int): Polygon {
    val coordinates =
        (0..<vertexCount).map { index ->
          val angle = 2.0 * PI * index / vertexCount
          Coordinate(radiusMeters * cos(angle), radiusMeters * sin(angle))
        }

    return mercatorFactory.createPolygon((coordinates + coordinates.first()).toTypedArray())
  }

  private fun point(longitude: Double, latitude: Double): Point =
      GeometryFactory(PrecisionModel(), SRID.LONG_LAT).createPoint(Coordinate(longitude, latitude))

  private fun pathData(svg: String): String? =
      Regex(""" d="([^"]+)"""").find(svg)?.groupValues?.get(1)

  private fun subpathCount(svg: String): Int = pathData(svg)!!.count { it == 'M' }

  private fun drawnCoordinates(svg: String): List<Pair<Double, Double>> =
      Regex("""(-?\d+\.\d+) (-?\d+\.\d+)""")
          .findAll(pathData(svg)!!)
          .map {
            it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
          }
          .toList()
}
