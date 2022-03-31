package com.terraformation.backend.util

import freemarker.template.Template
import java.io.StringWriter
import java.math.BigDecimal
import java.util.EnumSet
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
