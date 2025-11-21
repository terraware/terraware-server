package com.terraformation.backend.util

import com.terraformation.backend.db.SRID
import com.terraformation.backend.tracking.model.HECTARES_SCALE
import freemarker.template.Template
import java.io.StringWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional
import org.geotools.api.referencing.FactoryException
import org.geotools.api.referencing.crs.CoordinateReferenceSystem
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.geotools.referencing.operation.projection.TransverseMercator
import org.jooq.Field
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer

// One-off extension functions for third-party classes. Extensions that are only useful in the
// context of a specific bit of application code should live alongside that code, but functions that
// are generally useful and that can't be logically grouped together can go here.

/** Transforms a Collection to null if it is empty. */
fun <T : Collection<*>> T.orNull(): T? = ifEmpty { null }

/** Returns null if this value is equal to another value, or this value if they differ. */
fun <T : Any?> T.nullIfEquals(other: T): T? = if (this == other) null else this

/** Tests two nullable BigDecimal values for equality ignoring their scale. */
fun BigDecimal?.equalsIgnoreScale(other: BigDecimal?) =
    this == null && other == null || this != null && other != null && compareTo(other) == 0

/** Converts a BigDecimal in plants per plot to plants per hectare. */
fun BigDecimal.toPlantsPerHectare(scale: Int = 10): BigDecimal =
    this.divide(HECTARES_PER_PLOT.toBigDecimal(), scale, RoundingMode.HALF_UP)

/**
 * Generates an equality condition for a jOOQ field if the value is non-null, or an IS NULL if the
 * value is null.
 */
fun <T> Field<T>.eqOrIsNull(value: T) = if (value != null) eq(value) else isNull

/** Compares two comparable values, treating null values as greater than non-null ones. */
fun <T : Comparable<T>> T?.compareNullsLast(other: T?): Int {
  return when {
    this != null && other != null -> this.compareTo(other)
    this != null && other == null -> -1
    this == null && other != null -> 1
    else -> 0
  }
}

/** Renders a FreeMarker template to a string given the values in a model object. */
fun Template.processToString(model: Any): String {
  return StringWriter().use { writer ->
    process(model, writer)
    writer.toString()
  }
}

/**
 * Appends a path element to a URI. Unlike [URI.resolve], returns the same result whether or not the
 * existing URI's path has a trailing slash. That is, `URI("http://x/y").appendPath("z") ==
 * URI("http://x/y/").appendPath("z")`. Preserves other URI elements such as query string as-is.
 */
fun URI.appendPath(additionalPath: String): URI {
  return if (path.endsWith('/')) {
    URI(scheme, userInfo, host, port, "$path$additionalPath", query, fragment)
  } else {
    URI(scheme, userInfo, host, port, "$path/$additionalPath", query, fragment)
  }
}

/**
 * Calls a function on a chunk of elements from a sequence, then returns the sequence in its
 * original unchunked form for further processing of individual elements. Each element is only
 * consumed from the original sequence once. This is operation is _intermediate_ and _stateful_.
 */
fun <T> Sequence<T>.onChunk(chunkSize: Int, func: (List<T>) -> Unit): Sequence<T> {
  return chunked(chunkSize).onEach { func(it) }.flatten()
}

private val combiningMarksRegex = Regex("[\\u0000\\p{Mn}]+")

/** Removes accents and other diacritics from characters in a string. */
fun String.removeDiacritics(): String {
  // First, decompose characters into combining forms: "á" gets turned into a two-character sequence
  // of "a" followed by a combining character that modifies the previous character to add an accent
  // mark.
  val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)

  // Now remove all the combining characters, resulting in a string without diacritics.
  return normalized.replace(combiningMarksRegex, "")
}

/** Returns the Instant for a time of day on the date in a particular time zone. */
fun LocalDate.toInstant(timeZone: ZoneId, time: LocalTime = LocalTime.MIDNIGHT): Instant =
    ZonedDateTime.of(this, time, timeZone).toInstant()

/**
 * Returns true if this geometry is the same as the other geometry within a certain tolerance level,
 * or if both this and other are null.
 */
fun Geometry?.equalsOrBothNull(other: Geometry?, tolerance: Double = 0.00001): Boolean {
  return this == null && other == null ||
      (this != null && other != null && equalsExact(other, tolerance))
}

/** Returns a rectangular grid-aligned polygon with edges at the specified positions. */
fun GeometryFactory.createRectangle(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
): Polygon {
  return createPolygon(
      arrayOf(
          Coordinate(west, south),
          Coordinate(east, south),
          Coordinate(east, north),
          Coordinate(west, north),
          Coordinate(west, south),
      )
  )
}

/**
 * Returns a MultiPolygon version of a polygonal geometry. If the geometry is already a
 * MultiPolygon, returns it as-is. If the geometry is a GeometryCollection, discards any Point
 * members of the collection.
 */
fun Geometry.toMultiPolygon(): MultiPolygon {
  fun extractPolygonsFromCollection(collection: GeometryCollection): List<Polygon> {
    val polygons = mutableListOf<Polygon>()
    for (n in 0..<collection.numGeometries) {
      when (val element = collection.getGeometryN(n)) {
        is Polygon -> polygons.add(element)
        is GeometryCollection -> polygons.addAll(extractPolygonsFromCollection(element))
      }
    }
    return polygons
  }

  return when (this) {
    is MultiPolygon -> this
    is Polygon -> factory.createMultiPolygon(arrayOf(this))
    is GeometryCollection ->
        factory.createMultiPolygon(extractPolygonsFromCollection(this).toTypedArray())
    else -> throw IllegalArgumentException("Cannot convert $geometryType to MultiPolygon")
  }
}

fun MultiPolygon.orEmpty(): MultiPolygon = if (isEmpty) factory.createMultiPolygon() else this

fun Geometry.toNormalizedMultiPolygon() = norm().toMultiPolygon().orEmpty()

/**
 * Fixes problems with invalid geometries if possible. Geometries that are calculated using
 * operations like intersections and unions can suffer from floating-point inaccuracy which causes
 * things like self-intersecting polygon edges. Subsequent calculations on those geometries will
 * throw exceptions.
 *
 * Note that this should only be used to fix geometries that are derived from valid geometries, not
 * as a way to avoid having to validate user-supplied geometries.
 */
fun Geometry.fixIfNeeded(): Geometry {
  return if (isValid) {
    this
  } else {
    GeometryFixer(this).result
  }
}

/**
 * Calculates the approximate area of a geometry in hectares. If this feature isn't already in a UTM
 * coordinate system, converts it to the appropriate one first.
 *
 * @throws FactoryException The geometry couldn't be converted to UTM.
 */
fun Geometry.calculateAreaHectares(originalCrs: CoordinateReferenceSystem? = null): BigDecimal {
  if (isEmpty) {
    return BigDecimal.ZERO.setScale(1)
  }

  val crs = originalCrs ?: CRS.decode("EPSG:$srid", true)

  // Transform to UTM if it isn't already.
  val utmGeometry =
      if (CRS.getProjectedCRS(crs) is TransverseMercator) {
        this
      } else {
        // To use the "look up the right UTM for a location" feature of GeoTools, we need to
        // know the location in WGS84 (EPSG:4326) longitude and latitude; transform the feature's
        // centroid coordinates to WGS84.
        val wgs84Centroid =
            if (CRS.lookupEpsgCode(crs, false) == SRID.LONG_LAT) {
              centroid
            } else {
              val wgs84Transform = CRS.findMathTransform(crs, DefaultGeographicCRS.WGS84)
              JTS.transform(centroid, wgs84Transform) as Point
            }

        val utmCrs = CRS.decode("AUTO2:42001,${wgs84Centroid.x},${wgs84Centroid.y}")

        JTS.transform(this, CRS.findMathTransform(crs, utmCrs))
      }

  return BigDecimal(utmGeometry.area / SQUARE_METERS_PER_HECTARE)
      .setScale(HECTARES_SCALE, RoundingMode.HALF_EVEN)
}

/** Returns the percentage of this geometry that is covered by another one. */
fun Geometry.coveragePercent(other: Geometry): Double {
  return if (coveredBy(other)) {
    100.0
  } else {
    intersection(other).area / area * 100.0
  }
}

/**
 * Determines whether this geometry is nearly covered by another one. This differs from `coveredBy`
 * in that it allows a small margin of error to account for floating-point inaccuracy.
 *
 * @param minCoveragePercent Minimum percentage of this geometry's area that needs to be covered by
 *   the other geometry. Default is 99.99%.
 */
fun Geometry.nearlyCoveredBy(other: Geometry, minCoveragePercent: Double = 99.99): Boolean {
  return coveredBy(other) || coveragePercent(other) >= minCoveragePercent
}

/**
 * Returns the difference between this geometry and another geometry, or this geometry if the other
 * one is null.
 */
fun Geometry.differenceNullable(other: Geometry?): Geometry =
    if (other != null) difference(other) else this

/**
 * Applies this `Optional` as a replacement for an existing value.
 *
 * This is primarily used with payloads for `PATCH` endpoints that can update nullable values. For
 * example, say an entity has a property `notes` of type `String?`. We'd want to handle a `PATCH`
 * request as follows:
 *
 * | JSON               | Desired end result      |
 * |--------------------|-------------------------|
 * | `{}`               | Keep the original notes |
 * | `{"notes": null}`  | Set notes to null       |
 * | `{"notes": "bar"}` | Set notes to "bar"      |
 *
 * If a payload class includes `notes: Optional<String>?`, Jackson (with the JDK8 module enabled)
 * deserializes it as follows:
 *
 * | JSON               | Deserializes to              |
 * |--------------------|------------------------------|
 * | `{}`               | `notes = null`               |
 * | `{"notes": null}`  | `notes = Optional.empty()`   |
 * | `{"notes": "bar"}` | `notes = Optional.of("bar")` |
 *
 * This method implements the patch semantics from the first table. Use it like this:
 * ```kotlin
 * val updatedModel = model.copy(
 *     notes = payload.notes.patchNullable(model.notes),
 * )
 * ```
 *
 * Updates to non-nullable values can be handled by making the PATCH request payload fields nullable
 * and using `payloadField ?: originalValue`; Jackson will set the payload field to null if it's
 * absent. This will have the effect of silently ignoring attempts to set non-nullable values to
 * null, which should be acceptable in most cases.
 */
fun <T> Optional<T>?.patchNullable(original: T?): T? = if (this == null) original else orElse(null)
