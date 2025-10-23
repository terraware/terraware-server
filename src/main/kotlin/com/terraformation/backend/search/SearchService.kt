package com.terraformation.backend.search

import com.terraformation.backend.log.debugWithTiming
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.search.field.SearchField
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.conf.ParamType

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
    queryBuilder.addSelectFields(fields, criteria)
    queryBuilder.addSortFields(sortOrder)
    queryBuilder.addCondition(queryBuilder.filterResults(rootPrefix, criteria[rootPrefix]))

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

  fun searchCount(
      rootPrefix: SearchFieldPrefix,
      fields: Collection<SearchFieldPath>,
      criteria: Map<SearchFieldPrefix, SearchNode>,
  ): Long {
    val exactCriteria = criteria.mapValues { it.value.toExactSearch() }
    if (exactCriteria != criteria) {
      val exactResults = searchCount(rootPrefix, fields, exactCriteria)
      if (exactResults > 0) {
        return exactResults
      }
    }

    return runQueryCount(rootPrefix, criteria)
  }

  private fun runQueryCount(
      rootPrefix: SearchFieldPrefix,
      criteria: Map<SearchFieldPrefix, SearchNode>,
  ): Long {
    val queryBuilder = buildQuery(rootPrefix, emptyList(), criteria, emptyList())
    val query = queryBuilder.toSelectCountQuery()

    val count =
        log.debugWithTiming("Retrieved count for search query") {
          dslContext.selectCount().from(query).fetchOne(0, Long::class.java) ?: 0L
        }
    return count
  }
}
