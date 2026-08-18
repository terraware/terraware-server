package com.terraformation.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.seedbank.db.AccessionImporterTest
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.equalsOrBothNull
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assume.assumeNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

/**
 * ObjectMapper configured to pretty print. This is lazily instantiated since ObjectMappers aren't
 * terribly lightweight.
 */
private val prettyPrintingObjectMapper: ObjectMapper by lazy {
  jacksonObjectMapper()
      .registerModule(GeometryModule())
      .registerModule(JavaTimeModule())
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY)
}

/**
 * Asserts that two objects are equal and, if they're not, outputs the comparison failure using
 * pretty-printed JSON rather than the outputs of their `toString` methods.
 *
 * This makes it easier to examine differences between accession objects with lots of field values.
 */
fun assertJsonEquals(expected: Any, actual: Any, message: String? = null) {
  if (expected != actual) {
    assertEquals(
        prettyPrintingObjectMapper.writeValueAsString(expected),
        prettyPrintingObjectMapper.writeValueAsString(actual),
        message,
    )
  }
}

/**
 * Asserts that two Geometry objects are approximately equal. The regular assertEquals function can
 * fail due to loss of precision when geometries are stored in the database.
 */
fun assertGeometryEquals(expected: Geometry?, actual: Geometry?, message: String? = null) {
  if (!expected.equalsOrBothNull(actual)) {
    // Let the regular assertEquals output the failure message.
    assertEquals(expected, actual, message)
  }
}

/**
 * Asserts that two sets are equal. If not, produces an assertion failure message with the items
 * sorted by their string representations, each item on a separate line, to make it easier to spot
 * differences.
 */
fun <T> assertSetEquals(expected: Set<T>, actual: Set<T>, message: String? = null) {
  if (expected != actual) {
    assertEquals(expected.toPrettyString(), actual.toPrettyString(), message)

    // Should never get here unless the string representations of the items are incomplete.
    assertEquals(
        expected,
        actual,
        "Sets are not equal, but their items' string representations are the same",
    )
  }
}

/** Asserts that two BigDecimal values are equal, ignoring their scales. */
fun assertEqualsIgnoreScale(expected: BigDecimal, actual: BigDecimal?, message: String? = null) {
  if (actual == null || !expected.equalsIgnoreScale(actual)) {
    assertEquals(expected, actual, message)
  }
}

fun point(x: Number, y: Number = x, z: Number? = null, srid: Int = SRID.LONG_LAT): Point {
  val geometryFactory = GeometryFactory(PrecisionModel(), srid)
  return geometryFactory.createPoint(
      Coordinate(x.toDouble(), y.toDouble(), z?.toDouble() ?: Coordinate.NULL_ORDINATE)
  )
}

/** Creates a rectangular Polygon. */
fun polygon(
    left: Number,
    bottom: Number,
    right: Number,
    top: Number,
    srid: Int = SRID.LONG_LAT,
): Polygon {
  val geometryFactory = GeometryFactory(PrecisionModel(), srid)
  return geometryFactory.createPolygon(
      arrayOf(
          CoordinateXY(left.toDouble(), bottom.toDouble()),
          CoordinateXY(right.toDouble(), bottom.toDouble()),
          CoordinateXY(right.toDouble(), top.toDouble()),
          CoordinateXY(left.toDouble(), top.toDouble()),
          CoordinateXY(left.toDouble(), bottom.toDouble()),
      )
  )
}

/** Creates a square Polygon with its left bottom corner at the origin. */
fun polygon(scale: Number): Polygon {
  return polygon(0.0, 0.0, scale, scale)
}

/**
 * Creates a Polygon with holes in it. The outer boundaries of [holes] become the interior rings of
 * the result; any holes they have of their own are discarded.
 */
fun polygonWithHoles(shell: Polygon, holes: List<Polygon>): Polygon {
  return shell.factory.createPolygon(
      shell.exteriorRing,
      holes.map { it.exteriorRing }.toTypedArray(),
  )
}

/**
 * Creates a regular polygon approximating a circle. Useful for exercising code whose behavior
 * depends on how many vertices a geometry has.
 *
 * Unlike [rectangle], the radius is in the coordinate units of [srid] rather than in meters.
 */
fun circle(
    radius: Number,
    x: Number = 0,
    y: Number = 0,
    vertexCount: Int = 360,
    srid: Int = SRID.LONG_LAT,
): Polygon {
  val coordinates =
      (0..<vertexCount).map { index ->
        val angle = 2.0 * Math.PI * index / vertexCount
        CoordinateXY(
            x.toDouble() + radius.toDouble() * cos(angle),
            y.toDouble() + radius.toDouble() * sin(angle),
        )
      }

  return GeometryFactory(PrecisionModel(), srid)
      .createPolygon((coordinates + coordinates.first()).toTypedArray())
}

/** Wraps a Polygon in a MultiPolygon. */
fun multiPolygon(polygon: Polygon): MultiPolygon {
  return polygon.factory.createMultiPolygon(arrayOf(polygon))
}

/**
 * Combines Polygons into a MultiPolygon. The coordinate reference system is taken from the first
 * polygon.
 */
fun multiPolygon(polygons: List<Polygon>): MultiPolygon {
  return polygons.first().factory.createMultiPolygon(polygons.toTypedArray())
}

/** Creates a simple rectangular MultiPolygon. */
fun multiPolygon(scale: Number): MultiPolygon {
  return multiPolygon(polygon(scale))
}

/** Creates a MultiPolygon with no polygons in it. */
fun emptyMultiPolygon(srid: Int = SRID.LONG_LAT): MultiPolygon {
  return GeometryFactory(PrecisionModel(), srid).createMultiPolygon()
}

/**
 * Returns a rectangular MultiPolygon with position and size in meters.
 *
 * @param x Distance in meters east of [origin].
 * @param y Distance in meters north of [origin].
 * @param origin Point the rectangle is positioned relative to. Pass a high-latitude point to
 *   exercise code whose behavior depends on the distortion of geographic coordinates.
 */
fun rectangle(
    width: Number,
    height: Number = width,
    x: Number = 0,
    y: Number = 0,
    origin: Point = point(1),
): MultiPolygon {
  return if (width == 0) {
    GeometryFactory(PrecisionModel(), origin.srid).createMultiPolygon(emptyArray())
  } else {
    Turtle(origin)
        .makeMultiPolygon {
          north(y)
          east(x)
          rectangle(width, height)
        }
        .norm()
        .toMultiPolygon()
  }
}

/** Returns a rectangular Polygon with position and size in meters. */
fun rectanglePolygon(
    width: Number,
    height: Number = width,
    x: Number = 0,
    y: Number = 0,
): Polygon =
    Turtle(point(1))
        .makePolygon {
          north(y)
          east(x)
          rectangle(width, height)
        }
        .norm() as Polygon

/**
 * Returns dummy information about Keycloak. This can be used to test code that generates
 * Keycloak-related output such as registration URLs.
 */
fun dummyKeycloakInfo() = KeycloakInfo("client-id", "secret", "http://dummy/realms/terraware")

/** A 1-pixel JPEG file for testing code that requires valid image data. */
val onePixelJpeg: ByteArray by lazy {
  TestClock::class.java.getResourceAsStream("/file/pixel.jpg").use { it.readAllBytes() }
}

/** A 1-pixel PNG file for testing code that requires valid image data. */
val onePixelPng: ByteArray by lazy {
  TestClock::class.java.getResourceAsStream("/file/pixel.png").use { it.readAllBytes() }
}

/**
 * Converts an arbitrary numeric type to a BigDecimal. May involve converting it to a Double as an
 * intermediate step.
 *
 * This is analogous to the `toBigDecimal()` extension methods in the Kotlin standard library, but
 * can be called on an unknown numeric type.
 */
fun Number.toBigDecimal(): BigDecimal =
    when (this) {
      is BigDecimal -> this
      is Int -> BigDecimal(this)
      is Long -> BigDecimal(this)
      // Let BigDecimal parse the string representation; this is how the toBigDecimal() extension
      // methods in the Kotlin standard library do it for Float and Double types, and we want to
      // return the same values they do.
      else -> BigDecimal(toString())
    }

/**
 * Returns a map from 1-indexed IDs to the actual IDs from a list of entities.
 *
 * For example, if you have a list of `SpeciesRow` objects whose IDs are `SpeciesId(8)`,
 * `SpeciesId(10)`, and `SpeciesId(11)`, this would return
 *
 * ```
 * mapOf(
 *     SpeciesId(1) to SpeciesId(8),
 *     SpeciesId(2) to SpeciesId(10),
 *     SpeciesId(3) to SpeciesId(11))
 * ```
 *
 * This is used in tests that insert new entities and need to assert that other entities refer to
 * the correct IDs; the expected values are constructed with hardwired IDs starting with 1 and the
 * references in the expected values use those 1-indexed IDs. Once the entities are created, this
 * function is called to map the 1-indexed IDs to the actual ones, and the expected references are
 * then replaced with the corresponding actual IDs by looking them up in the map.
 *
 * @see AccessionImporterTest.HappyPath.runHappyPath
 */
fun <T : Any, FAKE_ID : Any, ACTUAL_ID : Any> mapTo1IndexedIds(
    entities: List<T>,
    newIdFunc: (Long) -> FAKE_ID,
    getIdFunc: (T) -> ACTUAL_ID?,
): Map<FAKE_ID, ACTUAL_ID> {
  return entities
      .mapIndexed { index, entity ->
        val fakeId = newIdFunc(index + 1L)
        val actualId =
            getIdFunc(entity)
                ?: throw IllegalArgumentException(
                    "Null ID in ${entity.javaClass.simpleName} at index $index"
                )
        fakeId to actualId
      }
      .toMap()
}

/**
 * Gets the value of an environment variable. If the variable isn't set, skips the current test.
 * This is typically used for tests that depend on external services, which we don't want to include
 * in test runs by default since they can be slow and flaky.
 */
fun getEnvOrSkipTest(name: String): String {
  val value = System.getenv(name)
  assumeNotNull(value, "$name not set; skipping test")
  return value
}

/**
 * Returns a string representation of a Set with the items sorted and each item on its own indented
 * line.
 */
private fun Set<*>.toPrettyString(): String = sortedBy {
  it.toString()
}
    .joinToString(",\n  ", "[\n  ", "\n]")
