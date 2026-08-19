package com.terraformation.backend.util

import com.terraformation.backend.circle
import com.terraformation.backend.db.SRID
import com.terraformation.backend.emptyMultiPolygon
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.polygonWithHoles
import com.terraformation.backend.rectangle
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Test geometry is built directly in Web Mercator so that the renderer's reprojection step is a
 * no-op and the expected output coordinates can be calculated by hand.
 */
class GeometrySvgRendererTest {
  private val renderer = GeometrySvgRenderer()

  @Test
  fun `scales and centers a square boundary`() {
    // Canvas 100x100 with 2 units of padding leaves a 96x96 drawing area, so a 100m square is
    // scaled by 0.96 and drawn from 2 to 98 in both dimensions.
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR), 100, 100)

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
    val svg = renderer.render(polygon(0, 0, 200, 100, SRID.SPHERICAL_MERCATOR), 100, 100)

    assertEquals(
        "M2.00 74.00L98.00 74.00L98.00 26.00L2.00 26.00L2.00 74.00Z",
        pathData(svg),
        "Path data",
    )
  }

  @Test
  fun `flips the Y axis`() {
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR), 100, 100)
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
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR), 64, 48)

    assertTrue(svg.contains("""width="64""""), "Width attribute in $svg")
    assertTrue(svg.contains("""height="48""""), "Height attribute in $svg")
    assertTrue(svg.contains("""viewBox="0 0 64 48""""), "viewBox attribute in $svg")
  }

  @Test
  fun `uses the default canvas size if none is requested`() {
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR))

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
        multiPolygon(
            listOf(
                polygon(0, 0, 40, 40, SRID.SPHERICAL_MERCATOR),
                polygon(60, 60, 100, 100, SRID.SPHERICAL_MERCATOR),
            )
        )

    assertEquals(2, subpathCount(renderer.render(multiPolygon, 100, 100)), "Number of subpaths")
  }

  @Test
  fun `emits no styling attributes`() {
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR), 100, 100)

    // Colors, opacities and stroke widths are the client's to choose.
    assertEquals(
        """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" """ +
            """viewBox="0 0 100 100"><path fill-rule="evenodd" """ +
            """d="M2.00 98.00L98.00 98.00L98.00 2.00L2.00 2.00L2.00 98.00Z"/></svg>""",
        svg,
    )
  }

  @Test
  fun `renders interior rings as holes`() {
    val polygonWithHole =
        polygonWithHoles(
            polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR),
            listOf(polygon(25, 25, 75, 75, SRID.SPHERICAL_MERCATOR)),
        )

    val svg = renderer.render(polygonWithHole, 100, 100)

    assertEquals(2, subpathCount(svg), "Number of subpaths")
    assertTrue(svg.contains("""fill-rule="evenodd""""), "Fill rule in $svg")
  }

  @Test
  fun `drops detail that is smaller than a pixel`() {
    val vertexCount = 500
    val circle = circle(radius = 1000, vertexCount = vertexCount, srid = SRID.SPHERICAL_MERCATOR)

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
    val square = rectangle(500, origin = point(18, 59))

    val coordinates = drawnCoordinates(renderer.render(square, 100, 100))
    val drawnWidth = coordinates.maxOf { it.first } - coordinates.minOf { it.first }
    val drawnHeight = coordinates.maxOf { it.second } - coordinates.minOf { it.second }

    assertEquals(1.0, drawnWidth / drawnHeight, 0.02, "Ratio of drawn width to drawn height")
  }

  @Test
  fun `unwraps a boundary split across the antimeridian`() {
    // Transforming to SRID 4326 leaves a site that straddles 180 degrees as two polygons, one on
    // each side of the antimeridian. Rendered naively, the site's bounding box is nearly Earth-wide
    // and the site collapses into slivers at the left and right edges of the canvas.
    val crossing =
        multiPolygon(
            listOf(polygon(179.9, -16.0, 180.0, -15.9), polygon(-180.0, -16.0, -179.9, -15.9))
        )
    val equivalent =
        multiPolygon(listOf(polygon(10.0, -16.0, 10.1, -15.9), polygon(10.1, -16.0, 10.2, -15.9)))

    assertEquals(
        pathData(renderer.render(equivalent, 100, 100)),
        pathData(renderer.render(crossing, 100, 100)),
        "Crossing the antimeridian should render the same as the equivalent site elsewhere",
    )
  }

  @Test
  fun `unwraps a single ring that crosses the antimeridian`() {
    val crossing = polygon(179.9, -16.0, -179.9, -15.9)
    val equivalent = polygon(10.0, -16.0, 10.2, -15.9)

    assertEquals(
        pathData(renderer.render(equivalent, 100, 100)),
        pathData(renderer.render(crossing, 100, 100)),
        "Crossing the antimeridian should render the same as the equivalent site elsewhere",
    )
  }

  @Test
  fun `formats coordinates independently of the default locale`() {
    val originalLocale = Locale.getDefault()

    try {
      // German locale uses a comma as the decimal separator, which would be invalid path data.
      Locale.setDefault(Locale.GERMANY)

      val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR))

      assertFalse(pathData(svg)!!.contains(','), "Path data in $svg")
    } finally {
      Locale.setDefault(originalLocale)
    }
  }

  @Test
  fun `renders an empty document for empty geometry`() {
    val svg = renderer.render(emptyMultiPolygon())

    assertEquals(
        """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" """ +
            """viewBox="0 0 256 256"></svg>""",
        svg,
    )
  }

  @Test
  fun `renders an empty document for geometry with no area`() {
    val svg = renderer.render(point(1, 2), 100, 100)

    assertEquals(null, pathData(svg), "Path data")
  }

  @Test
  fun `renders an empty document for a boundary with no extent`() {
    val degenerate = polygon(5, 5, 5, 5, SRID.SPHERICAL_MERCATOR)

    assertEquals(null, pathData(renderer.render(degenerate, 100, 100)), "Path data")
  }

  @Test
  fun `renders a tiny canvas without clipping the whole boundary`() {
    val svg = renderer.render(polygon(0, 0, 100, 100, SRID.SPHERICAL_MERCATOR), 4, 4)

    assertTrue(pathData(svg) != null, "Path data in $svg")
  }

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
