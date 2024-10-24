package com.terraformation.backend.search.field

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchTable
import java.util.EnumSet
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record

/**
 * Metadata about a field that can be included in accession search requests. This is used by
 * [SearchService] to dynamically construct SQL queries for arbitrary user-specified searches.
 */
interface SearchField {
  /**
   * The name of the field as presented in the search API. This does not necessarily exactly match
   * the column name, though in most cases it should be similar.
   */
  @get:JsonValue val fieldName: String

  /**
   * Which table the field is in. [SearchService] joins with this table when constructing queries.
   */
  val table: SearchTable

  /**
   * Which database columns contain the field's data. In most cases, this will be a 1-element list,
   * but it can have multiple elements in the case of fields such as geolocation that are presented
   * as composite values in search results but stored as individual components in the database.
   */
  val selectFields: List<Field<*>>

  /**
   * Which value is used when the query results are ordered by this field. Most of the time this is
   * the same as the first element of [selectFields] and the default implementation delegates to
   * that value, but for fields with computed values, the "field" here may be an expression rather
   * than a simple column name. If the field is a compound value from multiple database columns,
   * this should combine them into a single computed field that can be used for sorting.
   */
  val orderByField: Field<*>

  /**
   * Which kinds of filters are allowed for this field. For example, it makes no sense to support
   * fuzzy text search on numeric values.
   */
  val supportedFilterTypes: Set<SearchFilterType>
    get() = EnumSet.allOf(SearchFilterType::class.java)

  /** The values that this field could have, or null if it doesn't have a fixed set of options. */
  val possibleValues: List<String>?
    get() = null

  /** If true, values should be localized to the current locale. */
  val localize: Boolean
    get() = true

  /** If true, values can be exported to CSV. */
  val exportable: Boolean
    get() = true

  /**
   * Returns a list of conditions to include in a WHERE clause when this field is used to filter
   * search results. This may vary based on the filter type.
   */
  fun getConditions(fieldNode: FieldNode): List<Condition>

  /**
   * Renders the value of this field as a string given a row of results from a search query.
   * Typically this will just call `toString()` on the value of a single element of [record] but for
   * fields with computed values or that are composites of multiple columns, this can include
   * additional logic.
   */
  fun computeValue(record: Record): String?

  /** Returns this field's human-readable name in the current locale. */
  fun getDisplayName(messages: Messages): String {
    return messages.searchFieldDisplayName(table.name, fieldName)
  }

  /**
   * Returns a copy of this field that accepts and returns raw values rather than localized ones, if
   * relevant. The copy should have a field name with a suffix of "(raw)".
   *
   * Returns null if raw values are irrelevant for the field or if this field is already raw.
   */
  fun raw(): SearchField?

  /** Returns the name of the raw variant of this field, if any. */
  fun rawFieldName(): String = if (localize) "$fieldName(raw)" else fieldName
}
