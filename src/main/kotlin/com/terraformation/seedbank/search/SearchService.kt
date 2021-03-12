package com.terraformation.seedbank.search

import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.services.debugWithTiming
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

/**
 * Searches the database based on user-supplied search criteria. Since the user is allowed to
 * specify an arbitrary set of query criteria and ask for an arbitrary set of fields that may span
 * multiple tables, we dynamically construct SQL queries to return exactly what the user asked for.
 * This class is just the scaffolding; much of the SQL construction is done in [SearchField],
 * [SearchFilter], and [SearchTable].
 *
 * The design goal is to construct a single SQL query that returns exactly the requested data, as
 * opposed to issuing a series of smaller queries and assembling their results in the application.
 * This maximizes the database's flexibility to optimize the query.
 */
@ManagedBean
class SearchService(private val dslContext: DSLContext, private val searchFields: SearchFields) {
  private val log = perClassLogger()

  /**
   * Queries the values of a list of fields on accessions that match a list of filter criteria.
   *
   * The search results in the return value do not include fields with `null` values. That is, only
   * non-null values are included.
   *
   * If there are more search results than the requested limit, the return value includes a cursor
   * that can be used to view the next set of results.
   */
  fun search(
      fields: List<SearchField<*>>,
      filters: List<SearchFilter> = emptyList(),
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE
  ): SearchResults {
    val fieldNames = setOf("accessionNumber") + fields.map { it.fieldName }.toSet()
    val fieldObjects =
        fieldNames.map {
          searchFields[it] ?: throw IllegalArgumentException("Unknown field name $it")
        }

    // A SearchField might map to multiple database columns (e.g., geolocation is a composite of
    // latitude and longitude).
    val databaseFields = fieldObjects.flatMap { it.selectFields }.toSet()

    val conditions = filters.flatMap { it.toFieldConditions() }

    val orderBy =
        sortOrder.flatMap { sortOrderElement ->
          when (sortOrderElement.direction) {
            SearchDirection.Ascending -> sortOrderElement.field.orderByFields
            SearchDirection.Descending -> sortOrderElement.field.orderByFields.map { it.desc() }
          }
        } + listOf(ACCESSION.NUMBER)

    var query: SelectJoinStep<out Record> = dslContext.select(databaseFields).from(ACCESSION)

    query = joinWithSecondaryTables(query, fields, filters, sortOrder)

    // TODO: Better cursor support. Should remember the most recent values of the sort fields
    //       and pass them to skip(). For now, just treat the cursor as an offset.
    val offset = cursor?.toIntOrNull() ?: 0

    // Query one more row than the limit so we can tell the client whether or not there are
    // additional pages of results.
    val orderedQuery = query.where(conditions).orderBy(orderBy)
    val fullQuery =
        if (limit < Int.MAX_VALUE) {
          orderedQuery.limit(limit + 1).offset(offset)
        } else {
          orderedQuery
        }

    log.debug("search criteria: fields=$fields filters=$filters sort=$sortOrder")
    log.debug("search SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")
    val startTime = System.currentTimeMillis()

    val records = fullQuery.fetch()

    val endTime = System.currentTimeMillis()
    log.debug("search query returned ${records.size} rows in ${endTime - startTime} ms")

    val results =
        records.map { record ->
          fieldObjects
              .mapNotNull { field ->
                // There can be field-specific logic for rendering column values as strings,
                // e.g., geolocation includes both latitude and longitude.
                val value = field.computeValue(record)
                if (value != null) {
                  field.fieldName to value
                } else {
                  null
                }
              }
              .toMap()
        }

    val newCursor =
        if (results.size > limit) {
          "${offset + limit}"
        } else {
          null
        }

    return SearchResults(results.take(limit), newCursor)
  }

  /**
   * Returns a list of all the values a single field has on accessions matching a set of filter
   * criteria.
   *
   * @param limit Maximum number of results desired. The return value may be larger than this limit
   * by at most 1 element, which callers can use to detect that the number of values exceeds the
   * limit.
   * @return A list of values, which may include `null` if the field is optional and has no value on
   * some of the matching accessions.
   */
  fun <T> fetchValues(
      field: SearchField<T>,
      filters: List<SearchFilter>,
      limit: Int = 50
  ): List<String?> {
    val selectFields =
        field.selectFields +
            field.orderByFields.mapIndexed { index, orderByField ->
              orderByField.`as`(DSL.field("field$index"))
            }

    var query: SelectJoinStep<out Record> = dslContext.selectDistinct(selectFields).from(ACCESSION)

    query = joinWithSecondaryTables(query, listOf(field), filters)

    val fullQuery =
        query
            .where(filters.flatMap { it.toFieldConditions() })
            .orderBy(
                field.orderByFields.mapIndexed { index, _ ->
                  DSL.field("field$index").asc().nullsLast()
                })
            .limit(limit + 1)

    log.debug("fetchFieldValues ${field.fieldName} filters $filters")
    log.debug("fetchFieldValues SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")
    val startTime = System.currentTimeMillis()

    val results = fullQuery.fetch { field.computeValue(it) }

    val endTime = System.currentTimeMillis()
    log.debug("fetchFieldValues query returned ${results.size} rows in ${endTime - startTime} ms")

    // SearchField.computeValue() can introduce duplicates that the query's SELECT DISTINCT has no
    // way of filtering out.
    return results.distinct()
  }

  /**
   * Returns all the values for a particular field.
   *
   * This is not the same as calling [fetchValues] with no filter criteria, because it does not
   * limit the results to values that are currently used on accessions; for values from reference
   * tables such as `storage_location`, that means the list of values may include ones that are
   * currently not used anywhere.
   *
   * @param limit Maximum number of results desired. The return value may be larger than this limit
   * by at most 1 element, which callers can use to detect that the number of values exceeds the
   * limit.
   * @return A list of values, which may include `null` if the field is not mandatory.
   */
  fun <T> fetchAllValues(field: SearchField<T>, limit: Int = 50): List<String?> {
    // If the field is in a reference table that gets turned into an enum at build time, we don't
    // need to hit the database.
    val values = field.possibleValues ?: queryAllValues(field, limit)

    return if (field.nullable) {
      listOf(null) + values.take(limit)
    } else {
      values
    }
  }

  private fun <T> queryAllValues(field: SearchField<T>, limit: Int): List<String> {
    val selectFields =
        field.selectFields +
            field.orderByFields.mapIndexed { index, orderByField ->
              orderByField.`as`(DSL.field("field$index"))
            }

    val fullQuery =
        dslContext
            .selectDistinct(selectFields)
            .from(field.table.fromTable)
            .orderBy(
                field.orderByFields.mapIndexed { index, _ ->
                  DSL.field("field$index").asc().nullsLast()
                },
            )
            .limit(limit + 1)

    log.debug("queryAllValues SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")
    return log.debugWithTiming("queryAllValues") {
      fullQuery.fetch { field.computeValue(it) }.filterNotNull()
    }
  }

  /**
   * Adds JOIN clauses to a query to join the accession table with any other tables referenced by a
   * list of [SearchField] s.
   *
   * This handles indirect references; if a field is in a table that is two foreign-key hops away
   * from `accession`, the intermediate table is included here too.
   */
  private fun joinWithSecondaryTables(
      selectFrom: SelectJoinStep<out Record>,
      fields: List<SearchField<*>>,
      filters: List<SearchFilter>,
      sortOrder: List<SearchSortField> = emptyList()
  ): SelectJoinStep<out Record> {
    var query = selectFrom
    val directlyReferencedTables =
        fields.map { it.table }.toSet() +
            filters.map { it.field.table }.toSet() +
            sortOrder.map { it.field.table }.toSet()
    val dependencyTables =
        directlyReferencedTables.flatMap { it.dependsOn() }.toSet() - directlyReferencedTables

    dependencyTables.forEach { table -> query = table.leftJoinWithAccession(query) }
    directlyReferencedTables.forEach { table -> query = table.leftJoinWithAccession(query) }
    return query
  }
}

/** Return value from [SearchService.search]. */
data class SearchResults(
    /**
     * List of results containing the fields specified by the caller. Each element of the list is a
     * map of field name to non-null value. If an accession does not have a value for a particular
     * field, it is omitted from the map.
     */
    val results: List<Map<String, String>>,

    /**
     * Cursor that can be passed to [SearchService.search] to retrieve additional results. If
     * [results] contains the full set of results, this will be null.
     */
    val cursor: String?
)
