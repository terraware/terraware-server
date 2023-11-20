package com.terraformation.backend.util

import freemarker.template.Template
import java.io.StringWriter
import java.math.BigDecimal
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.jooq.Field

// One-off extension functions for third-party classes. Extensions that are only useful in the
// context of a specific bit of application code should live alongside that code, but functions that
// are generally useful and that can't be logically grouped together can go here.

/** Transforms a Collection to null if it is empty. */
fun <T : Collection<*>> T.orNull(): T? = ifEmpty { null }

/** Tests two nullable BigDecimal values for equality ignoring their scale. */
fun BigDecimal?.equalsIgnoreScale(other: BigDecimal?) =
    this == null && other == null || this != null && other != null && compareTo(other) == 0

/**
 * Generates an equality condition for a jOOQ field if the value is non-null, or an IS NULL if the
 * value is null.
 */
fun <T> Field<T>.eqOrIsNull(value: T) = if (value != null) eq(value) else isNull

/** Compares two comparable values, treating null values as less than non-null ones. */
fun <T : Comparable<T>> T?.compareNullsFirst(other: T?): Int {
  return when {
    this != null && other != null -> this.compareTo(other)
    this != null && other == null -> 1
    this == null && other != null -> -1
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
