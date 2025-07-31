package com.terraformation.backend.search

import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.search.field.SearchField
import jakarta.inject.Named
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
@Named
class SearchService(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  /** Returns a condition that filters search results based on a list of criteria. */
  private fun filterResults(
      rootPrefix: SearchFieldPrefix,
      criteria: Map<SearchFieldPrefix, SearchNode>
  ): Condition {
    // Filter out results the user doesn't have the ability to see. NestedQueryBuilder will include
    // the visibility check on the root table, but not on parent tables.
    val rootTable = rootPrefix.root
    val rootCriterion = criteria[rootPrefix] ?: return DSL.trueCondition()
    val conditions =
        listOfNotNull(
            rootCriterion.toCondition(),
            rootTable.inheritsVisibilityFrom?.let { conditionForVisibility(it) })

    val primaryKey = rootTable.primaryKey

    val subquery =
        joinWithSecondaryTables(
                DSL.select(primaryKey).from(rootTable.fromTable), rootPrefix, rootCriterion)
            .where(conditions)

    // Ideally we'd preserve the type of the primary key column returned by the subquery, but that
    // would require adding the primary key class as a type parameter in tons of places throughout
    // the search code. (Try it if you're bored; you'll see it quickly spirals out of control!)
    // The tiny amount of extra type safety we'd gain isn't worth the amount of boilerplate it'd
    // require, especially since the primary key type isn't known at compile time anyway.
    @Suppress("UNCHECKED_CAST")
    return primaryKey.`in`(subquery as Select<Nothing>)
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
   * The [rootPrefix] defines the starting table for field paths (all the paths in [fields] are
   * relative to the root prefix) and the top level of the search results. For example, if you have
   * an accession with two bags:
   * - Searching with a root prefix of `accessions` and a field name of `bags.number` will give you
   *   a result like `[{"bags":[{"number":"1"}, {"number":"2"}]}]`, that is, a single top-level
   *   result with a sublist that has two elements.
   * - Searching with a root prefix of `bags` and a field name of `number` will give you a result
   *   like `[{"number":"1"},{"number":"2"}]`, that is, two top-level results with no sublists.
   *
   * If the filter criteria include any exact-or-fuzzy matches, the system will first try to find
   * exact matches for the search terms; if there are any, it will return those and not do a fuzzy
   * search.
   */
  fun search(
      rootPrefix: SearchFieldPrefix,
      fields: Collection<SearchFieldPath>,
      criteria: Map<SearchFieldPrefix, SearchNode>,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE,
      distinct: Boolean = false,
  ): SearchResults {
    // TODO: Better cursor support. Should remember the most recent values of the sort fields
    //       and pass them to skip(). For now, just treat the cursor as an offset.
    val offset = cursor?.toIntOrNull() ?: 0

    val exactCriteria = criteria.mapValues { it.value.toExactSearch() }
    if (exactCriteria != criteria) {
      val exactResults =
          search(rootPrefix, fields, exactCriteria, sortOrder, cursor, limit, distinct)
      if (exactResults.results.isNotEmpty()) {
        return exactResults
      }
    }

    val results =
        runQuery(rootPrefix, fields, criteria, sortOrder, limit, offset, distinct).filterNotNull()

    val newCursor =
        if (results.size > limit) {
          "${offset + limit}"
        } else {
          null
        }

    return SearchResults(results.take(limit), newCursor)
  }

  /** Returns a [NestedQueryBuilder] configured to perform a particular search. */
  fun buildQuery(
      rootPrefix: SearchFieldPrefix,
      fields: Collection<SearchFieldPath>,
      criteria: Map<SearchFieldPrefix, SearchNode>,
      sortOrder: List<SearchSortField> = emptyList(),
  ): NestedQueryBuilder {
    val queryBuilder = NestedQueryBuilder(dslContext, rootPrefix)
    queryBuilder.addSelectFields(fields)
    queryBuilder.addSortFields(sortOrder)
    queryBuilder.addCondition(filterResults(rootPrefix, criteria))

    return queryBuilder
  }

  private fun runQuery(
      rootPrefix: SearchFieldPrefix,
      fields: Collection<SearchFieldPath>,
      criteria: Map<SearchFieldPrefix, SearchNode>,
      sortOrder: List<SearchSortField>,
      limit: Int,
      offset: Int = 0,
      distinct: Boolean,
  ): List<Map<String, Any>?> {
    val queryBuilder = buildQuery(rootPrefix, fields, criteria, sortOrder)
    val query = queryBuilder.toSelectQuery(distinct)

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
    return results
  }

  /**
   * Returns a list of all the values a single field has on entities matching a set of filter
   * criteria.
   *
   * @param limit Maximum number of results desired. The return value may be larger than this limit
   *   by at most 1 element, which callers can use to detect that the number of values exceeds the
   *   limit.
   * @return A list of values, which may include `null` if the field is optional and has no value on
   *   some of the matching accessions.
   */
  fun fetchValues(
      rootPrefix: SearchFieldPrefix,
      fieldPath: SearchFieldPath,
      criteria: Map<SearchFieldPrefix, SearchNode>,
      cursor: String? = null,
      limit: Int = 50,
  ): List<String?> {
    if (fieldPath.isNested) {
      throw IllegalArgumentException("Fetching nested field values is not supported.")
    }

    val offset = cursor?.toIntOrNull() ?: 0
    val exactCriteria = criteria.mapValues { it.value.toExactSearch() }

    val exactResults =
        runQuery(
            rootPrefix,
            listOf(fieldPath),
            exactCriteria,
            listOf(SearchSortField(fieldPath)),
            limit = limit,
            offset = offset,
            distinct = true,
        )

    val searchResults =
        if (exactResults.isNotEmpty() || exactCriteria == criteria) {
          exactResults
        } else {
          runQuery(
              rootPrefix,
              listOf(fieldPath),
              criteria,
              listOf(SearchSortField(fieldPath)),
              limit = limit,
              offset = offset,
              distinct = true,
          )
        }

    val fieldPathName = "$fieldPath"

    // The distinct() call is needed here despite the "distinct = true" in the runQuery call because
    // SearchField.computeValue() can introduce duplicates that the query's SELECT DISTINCT has no
    // way of filtering out.
    return searchResults.map { it?.get(fieldPathName)?.toString() }.distinct()
  }

  /**
   * Joins a query with any additional tables that are needed in order to determine which results
   * the user has the ability to see. This is needed when the query's root prefix points at a table
   * that doesn't include enough information to do visibility filtering.
   *
   * This can potentially join with multiple additional tables if the required information is more
   * than one hop away in the graph of search tables.
   *
   * The resulting query will include all the tables that will be referenced by
   * [conditionForVisibility].
   *
   * @param referencedTables Which tables are already referenced in the query. This method will not
   *   join with these tables. Should not include tables that are only referenced in subqueries.
   */
  private fun <T : Record> joinForVisibility(
      query: SelectJoinStep<T>,
      referencedTables: Set<SearchTable>,
      searchTable: SearchTable
  ): SelectJoinStep<T> {
    val inheritsVisibilityFrom = searchTable.inheritsVisibilityFrom ?: return query

    return if (inheritsVisibilityFrom in referencedTables) {
      // We've already joined with the next table in the chain, so no need to do it again. But we
      // might still need to join with additional tables beyond the next one.
      joinForVisibility(query, referencedTables, inheritsVisibilityFrom)
    } else {
      // The query doesn't already include the table we need to join with from this one in order to
      // evaluate visibility; join with it and then see if there are additional tables that also
      joinForVisibility(
          searchTable.joinForVisibility(query),
          referencedTables + inheritsVisibilityFrom,
          inheritsVisibilityFrom)
    }
  }

  /**
   * Returns a condition that checks whether the user is able to view a particular search result.
   *
   * The condition can refer to columns in any tables that are added by [joinForVisibility].
   */
  private fun conditionForVisibility(searchTable: SearchTable): Condition? {
    return searchTable.conditionForVisibility()
        ?: searchTable.inheritsVisibilityFrom?.let { conditionForVisibility(it) }
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
      criteria: SearchNode,
  ): SelectJoinStep<T> {
    val referencedSublists = criteria.referencedSublists().distinctBy { it.searchTable }
    val referencedTables = referencedSublists.map { it.searchTable }.toSet()

    val joinedQuery =
        referencedSublists.fold(selectFrom) { query, sublist ->
          query.leftJoin(sublist.searchTable.fromTable).on(sublist.conditionForMultiset)
        }

    return joinForVisibility(joinedQuery, referencedTables, rootPrefix.root)
  }
}
