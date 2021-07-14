package com.terraformation.backend.db

import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/** Renders vendor-specific SQL syntax for fuzzy text search. */
interface FuzzySearchOperators {
  fun likeFuzzy(field: Field<String?>, value: String): Condition
  fun similarity(field: Field<String?>, value: String): Field<Double>
}

/** Renders fuzzy text search SQL for PostgreSQL with the pg\_trgm extension. */
@ManagedBean
class PostgresFuzzySearchOperators : FuzzySearchOperators {
  override fun likeFuzzy(field: Field<String?>, value: String) =
      DSL.condition("{0} %>> {1}", field, DSL.`val`(value))
  override fun similarity(field: Field<String?>, value: String) =
      DSL.field("similarity({0},{1})", Double::class.java, field, DSL.`val`(value))
}

/**
 * Allows use of jOOQ-style fluent syntax for rendering fuzzy search syntax. A class that wants to
 * use one of the methods from [FuzzySearchOperators] can implement this interface and include
 * [fuzzySearchOperators] as a dependency, and then it will be able to use the extension functions
 * on jOOQ [Field] objects so the query syntax is consistent with the built-in operators.
 */
interface UsesFuzzySearchOperators {
  val fuzzySearchOperators: FuzzySearchOperators

  fun Field<String?>.likeFuzzy(value: String) = fuzzySearchOperators.likeFuzzy(this, value)

  fun Field<String?>.similarity(value: String) = fuzzySearchOperators.similarity(this, value)
}
