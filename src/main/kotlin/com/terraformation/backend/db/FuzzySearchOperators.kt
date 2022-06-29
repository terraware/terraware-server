package com.terraformation.backend.db

import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Returns a condition that checks if a field's value is sufficiently similar to a given string.
 * This uses the PostgreSQL `pg\_trgm` extension.
 */
fun Field<String?>.likeFuzzy(value: String): Condition {
  // Trigram searches don't work for single-character search strings, so fall back to LIKE.
  return if (value.length > 1) {
    DSL.condition("{0} %> {1}", this, DSL.`val`(value))
  } else {
    val escapedValue = escapeLikePattern(value.lowercase())
    DSL.lower(this).like("%$escapedValue%")
  }
}

/**
 * Returns a calculated field whose value is the similarity between the receiver field and a given
 * string. This uses the PostgreSQL `pg\_trgm` extension.
 */
fun Field<String?>.similarity(value: String): Field<Double> {
  return DSL.field("similarity({0},{1})", Double::class.java, this, DSL.`val`(value))
}

/** Escapes special characters in a string for use in a SQL "LIKE" clause. */
fun escapeLikePattern(pattern: String): String {
  return pattern.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%")
}
