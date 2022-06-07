package com.terraformation.backend.util

import com.fasterxml.jackson.databind.ObjectMapper
import freemarker.template.Template
import java.io.StringWriter
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.util.EnumSet
import java.util.function.Supplier
import org.jooq.Field

// One-off extension functions for third-party classes. Extensions that are only useful in the
// context of a specific bit of application code should live alongside that code, but functions that
// are generally useful and that can't be logically grouped together can go here.

/** Returns an empty EnumSet without having to pass in a `Class` explicitly. */
inline fun <reified T : Enum<T>> emptyEnumSet(): EnumSet<T> = EnumSet.noneOf(T::class.java)

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

/**
 * Returns a handler that deserializes a JSON HTTP response to a particular class. This can be
 * passed to [java.net.http.HttpClient.send].
 *
 * The handler actually returns a [Supplier] that returns the deserialized value. The value isn't
 * deserialized until [Supplier.get] is called. That allows callers to first check whether the HTTP
 * request succeeded.
 *
 * Usage example:
 * ```
 * val response = httpClient.send(httpRequest, objectMapper.bodyHandler(SomeClass::class.java)
 * if (HttpStatus.resolve(response.statusCode())?.is2xxSuccessful == true) {
 *   val payload = response.body().get()
 * }
 * ```
 */
fun <T> ObjectMapper.bodyHandler(responseClass: Class<T>): BodyHandler<Supplier<T>> {
  return BodyHandler {
    HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) {
      Supplier<T> {
        if (responseClass == Unit::class.java) {
          @Suppress("UNCHECKED_CAST")
          Unit as T
        } else {
          readValue(it, responseClass)
        }
      }
    }
  }
}

/**
 * Returns a handler that deserializes a JSON HTTP response to a particular class. This can be
 * passed to [java.net.http.HttpClient.send].
 *
 * The handler actually returns a [Supplier] that returns the deserialized value. The value isn't
 * deserialized until [Supplier.get] is called. That allows callers to first check whether the HTTP
 * request succeeded.
 *
 * Usage example:
 * ```
 * val response = httpClient.send(httpRequest, objectMapper.bodyHandler<SomeClass>()
 * if (HttpStatus.resolve(response.statusCode())?.is2xxSuccessful == true) {
 *   val payload = response.body().get()
 * }
 * ```
 */
inline fun <reified T> ObjectMapper.bodyHandler(): BodyHandler<Supplier<T>> =
    bodyHandler(T::class.java)
