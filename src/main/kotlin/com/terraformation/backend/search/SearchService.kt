package com.terraformation.backend.search

import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.search.field.SearchField
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
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
class SearchService(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  /** Returns a condition that filters search results based on a list of criteria. */
  private fun filterResults(rootPrefix: SearchFieldPrefix, criteria: SearchNode): Condition {
    // Filter out results the user doesn't have permission to see.
    val searchTable = rootPrefix.root.searchTable
    val conditions = listOfNotNull(criteria.toCondition(), conditionForPermissions(searchTable))

    val primaryKey = searchTable.primaryKey

    val subquery =
        joinWithSecondaryTables(
                DSL.select(primaryKey).from(searchTable.fromTable),
                rootPrefix,
                emptyList(),
                criteria)
            .where(conditions)

    // Ideally we'd preserve the type of the primary key column returned by the subquery, but that
    // would require adding the primary key class as a type parameter in tons of places throughout
    // the search code. (Try it if you're bored; you'll see it quickly spirals out of control!)
    // The tiny amount of extra type safety we'd gain isn't worth the amount of boilerplate it'd
    // require, especially since the primary key type isn't known at compile time anyway.
    @Suppress("UNCHECKED_CAST") return primaryKey.`in`(subquery as Select<Nothing>)
  }

  /**
   * Queries the values of a list of fields that match a list of filter criteria.
   *
   * The search results in the return value do not include fields with `null` values. That is, only
   * non-null values are included.
   *
   * If there are more search results than the requested limit, the return value includes a cursor
   * that can be used to view the next set of results.
   *
   * The [rootPrefix] defines the starting namespace for field paths (all the paths in [fields] are
   * relative to the root prefix) and the top level of the search results. For example, if you have
   * an accession with two bags:
   *
   * - Searching with a root prefix of `accessions` and a field name of `bags.number` will give you
   * a result like `[{"bags":[{"number":"1"}, {"number":"2"}]}]`, that is, a single top-level result
   * with a sublist that has two elements.
   * - Searching with a root prefix of `bags` and a field name of `number` will give you a result
   * like `[{"number":"1"},{"number":"2"}]`, that is, two top-level results with no sublists.
   */
  fun search(
      rootPrefix: SearchFieldPrefix,
      fields: Collection<SearchFieldPath>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE
  ): SearchResults {
    val queryBuilder = NestedQueryBuilder(dslContext, rootPrefix)
    queryBuilder.addSelectFields(fields)
    queryBuilder.addSortFields(sortOrder)
    queryBuilder.addCondition(filterResults(rootPrefix, criteria))

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
   * Returns a list of all the values a single field has on entities matching a set of filter
   * criteria.
   *
   * @param limit Maximum number of results desired. The return value may be larger than this limit
   * by at most 1 element, which callers can use to detect that the number of values exceeds the
   * limit.
   * @return A list of values, which may include `null` if the field is optional and has no value on
   * some of the matching accessions.
   */
  fun fetchValues(
      rootPrefix: SearchFieldPrefix,
      fieldPath: SearchFieldPath,
      criteria: SearchNode,
      limit: Int = 50,
  ): List<String> {
    val field = fieldPath.searchField
    val selectFields =
        field.selectFields + listOf(field.orderByField.`as`(DSL.field("order_by_field")))

    val searchTable = rootPrefix.root.searchTable
    var query: SelectJoinStep<out Record> =
        dslContext.selectDistinct(selectFields).from(searchTable.fromTable)

    query = joinWithSecondaryTables(query, rootPrefix, listOf(field), criteria)

    val conditions = listOfNotNull(criteria.toCondition(), conditionForPermissions(searchTable))

    val fullQuery =
        query
            .where(conditions)
            .orderBy(DSL.field("order_by_field").asc().nullsLast())
            .limit(limit + 1)

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
  fun fetchAllValues(fieldPath: SearchFieldPath, limit: Int = 50): List<String?> {
    // If the field is in a reference table that gets turned into an enum at build time, we don't
    // need to hit the database.
    val field = fieldPath.searchField
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

    val searchTable = field.table
    val permsCondition = conditionForPermissions(searchTable)

    val fullQuery =
        dslContext
            .selectDistinct(selectFields)
            .from(searchTable.fromTable)
            .let { joinForPermissions(it, setOf(searchTable), searchTable) }
            .let { if (permsCondition != null) it.where(permsCondition) else it }
            .orderBy(DSL.field("order_by_field").asc().nullsLast())
            .limit(limit + 1)

    log.debug("queryAllValues SQL query: ${fullQuery.getSQL(ParamType.INLINED)}")
    return log.debugWithTiming("queryAllValues") {
      fullQuery.fetch { field.computeValue(it) }.filterNotNull()
    }
  }

  /**
   * Joins a query with any additional tables that are needed in order to determine which results
   * the user has permission to see. This is needed when the query's root prefix points at a table
   * that doesn't include enough information to do permissions filtering.
   *
   * This can potentially join with multiple additional tables if the required information is more
   * than one hop away in the graph of search tables.
   *
   * The resulting query will include all the tables that will be referenced by
   * [conditionForPermissions].
   *
   * @param referencedTables Which tables are already referenced in the query. This method will not
   * join with these tables. Should not include tables that are only referenced in subqueries.
   */
  private fun <T : Record> joinForPermissions(
      query: SelectJoinStep<T>,
      referencedTables: Set<SearchTable>,
      searchTable: SearchTable
  ): SelectJoinStep<T> {
    val inheritsPermissionsFrom = searchTable.inheritsPermissionsFrom ?: return query

    return if (inheritsPermissionsFrom in referencedTables) {
      // We've already joined with the next table in the chain, so no need to do it again. But we
      // might still need to join with additional tables beyond the next one.
      joinForPermissions(query, referencedTables, inheritsPermissionsFrom)
    } else {
      // The query doesn't already include the table we need to join with from this one in order to
      // evaluate permissions; join with it and then see if there are additional tables that also
      joinForPermissions(
          searchTable.joinForPermissions(query),
          referencedTables + inheritsPermissionsFrom,
          inheritsPermissionsFrom)
    }
  }

  /**
   * Returns a condition that checks whether the user has permission to view a particular search
   * result.
   *
   * The condition can refer to columns in any tables that are added by [joinForPermissions].
   */
  private fun conditionForPermissions(searchTable: SearchTable): Condition? {
    return searchTable.conditionForPermissions()
        ?: searchTable.inheritsPermissionsFrom?.let { conditionForPermissions(it) }
  }

  /**
   * Adds JOIN clauses to a query to join the root table with any other tables referenced by a list
   * of [SearchField] s. This is not used for nested fields.
   *
   * This handles indirect references; if a field is in a table that is two foreign-key hops away
   * from `accession`, the intermediate table is included here too.
   */
  private fun <T : Record> joinWithSecondaryTables(
      selectFrom: SelectJoinStep<T>,
      rootPrefix: SearchFieldPrefix,
      fields: List<SearchField>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList()
  ): SelectJoinStep<T> {
    var query = selectFrom
    val directlyReferencedTables =
        fields.map { it.table }.toSet() +
            criteria.referencedTables() +
            sortOrder.map { it.field.searchField.table }.toSet() - rootPrefix.namespace.searchTable

    directlyReferencedTables.forEach { table -> query = table.leftJoinWithMain(query) }

    return joinForPermissions(query, directlyReferencedTables, rootPrefix.root.searchTable)
  }
}
