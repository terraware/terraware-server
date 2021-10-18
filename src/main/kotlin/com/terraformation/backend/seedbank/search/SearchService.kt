package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.SelectJoinStep
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

/**
 * Searches the database based on user-supplied search criteria. Since the user is allowed to
 * specify an arbitrary set of query criteria and ask for an arbitrary set of fields that may span
 * multiple tables, we dynamically construct SQL queries to return exactly what the user asked for.
 * This class is just the scaffolding; much of the SQL construction is done in [SearchField],
 * [SearchNode], and [SearchTable].
 *
 * The design goal is to construct a single SQL query that returns exactly the requested data, as
 * opposed to issuing a series of smaller queries and assembling their results in the application.
 * This maximizes the database's flexibility to optimize the query.
 */
@ManagedBean
class SearchService(private val dslContext: DSLContext, private val searchFields: SearchFields) {
  private val log = perClassLogger()

  /** Returns a query that selects the IDs of accessions that match a list of filter criteria. */
  private fun selectAccessionIds(
      facilityId: FacilityId,
      criteria: SearchNode
  ): Select<Record1<AccessionId?>> {
    // Filter out results the user doesn't have permission to see.
    val conditions =
        criteria
            .toCondition()
            .and(SearchTables.Accession.conditionForPermissions())
            .and(ACCESSIONS.FACILITY_ID.eq(facilityId))

    return joinWithSecondaryTables(
            DSL.select(ACCESSIONS.ID).from(ACCESSIONS), emptyList(), criteria)
        .where(conditions)
  }

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
      facilityId: FacilityId,
      fields: List<SearchField>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE
  ): SearchResults {
    val fieldNames = setOf("id", "accessionNumber") + fields.map { it.fieldName }.toSet()
    val fieldObjects =
        fieldNames.map {
          searchFields[it] ?: throw IllegalArgumentException("Unknown field name $it")
        }

    val queryBuilder = NestedQueryBuilder(dslContext)
    queryBuilder.addSelectFields(fieldObjects)
    queryBuilder.addSortFields(sortOrder)
    queryBuilder.addCondition(ACCESSIONS.ID.`in`(selectAccessionIds(facilityId, criteria)))

    val query = queryBuilder.toSelectQuery()

    // TODO: Better cursor support. Should remember the most recent values of the sort fields
    //       and pass them to skip(). For now, just treat the cursor as an offset.
    val offset = cursor?.toIntOrNull() ?: 0

    // Query one more row than the limit so we can tell the client whether or not there are
    // additional pages of results.
    val queryWithLimit =
        if (limit < Int.MAX_VALUE) {
          query.limit(limit + 1).offset(offset)
        } else {
          query
        }

    log.debug("search criteria: fields=$fields criteria=$criteria sort=$sortOrder")
    log.debug("search SQL query: ${queryWithLimit.getSQL(ParamType.INLINED)}")
    val startTime = System.currentTimeMillis()

    val results = queryWithLimit.fetch(queryBuilder::convertToMap)

    val endTime = System.currentTimeMillis()
    log.debug("search query returned ${results.size} rows in ${endTime - startTime} ms")

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
  fun fetchValues(field: SearchField, criteria: SearchNode, limit: Int = 50): List<String?> {
    val selectFields =
        field.selectFields + listOf(field.orderByField.`as`(DSL.field("order_by_field")))

    var query: SelectJoinStep<out Record> = dslContext.selectDistinct(selectFields).from(ACCESSIONS)

    query = joinWithSecondaryTables(query, listOf(field), criteria)

    val conditions = criteria.toCondition().and(SearchTables.Accession.conditionForPermissions())

    val fullQuery =
        query
            .where(conditions)
            .orderBy(DSL.field("order_by_field").asc().nullsLast())
            .limit(limit + 1)

    log.debug("fetchFieldValues ${field.fieldName} criteria $criteria")
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
  fun fetchAllValues(field: SearchField, limit: Int = 50): List<String?> {
    // If the field is in a reference table that gets turned into an enum at build time, we don't
    // need to hit the database.
    val values = field.possibleValues ?: queryAllValues(field, limit)

    return if (field.nullable) {
      listOf(null) + values.take(limit)
    } else {
      values
    }
  }

  private fun queryAllValues(field: SearchField, limit: Int): List<String> {
    val selectFields =
        field.selectFields + listOf(field.orderByField.`as`(DSL.field("order_by_field")))

    val permsCondition = field.table.conditionForPermissions()

    val fullQuery =
        dslContext
            .selectDistinct(selectFields)
            .from(field.table.fromTable)
            .let { field.table.joinForPermissions(it) }
            .let { if (permsCondition != null) it.where(permsCondition) else it }
            .orderBy(DSL.field("order_by_field").asc().nullsLast())
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
  private fun <T : Record> joinWithSecondaryTables(
      selectFrom: SelectJoinStep<T>,
      fields: List<SearchField>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList()
  ): SelectJoinStep<T> {
    var query = selectFrom
    val directlyReferencedTables =
        fields.map { it.table }.toSet() +
            criteria.referencedTables() +
            sortOrder.map { it.field.table }.toSet()

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
     *
     * Each value is either `String` or `List<Map<String, Any>>`.
     */
    val results: List<Map<String, Any>>,

    /**
     * Cursor that can be passed to [SearchService.search] to retrieve additional results. If
     * [results] contains the full set of results, this will be null.
     */
    val cursor: String?
)
