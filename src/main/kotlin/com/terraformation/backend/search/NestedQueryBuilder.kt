package com.terraformation.backend.search

import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.namespace.AccessionsNamespace
import com.terraformation.backend.util.MemoizedValue
import org.apache.naming.SelectorContext.prefix
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.RecordMapper
import org.jooq.SelectJoinStep
import org.jooq.SelectSeekStepN
import org.jooq.SortField
import org.jooq.impl.DSL

/**
 * Builds a database query that returns nested results from a tree of child tables.
 *
 * This code produces a somewhat complex result. Understanding it may require some effort. The rest
 * of this comment will try to lay everything out.
 *
 * # Basic usage
 *
 * Uses of this class will follow the same basic pattern everywhere.
 *
 * 1. Create a [NestedQueryBuilder].
 * 2. Call [addSelectFields] to set the list of fields that should be included in each result.
 * 3. Call [addSortFields] to set the list of fields that should be used to sort the results.
 * 4. Call [addCondition] to set the criteria that should be used to filter the results.
 * 5. Call [toSelectQuery] to get back a jOOQ [SelectSeekStepN] object representing the query.
 * 6. Call [SelectSeekStepN.fetch] to retrieve the query results.
 *
 * The filter criteria in step 4 will often involve a subquery; see the "Search criteria" section
 * below for more on that.
 *
 * # Example
 *
 * Suppose you have the following data. (For clarity, this omits irrelevant columns.)
 *
 * ```sql
 * INSERT INTO facilities (id, name) VALUES (10, 'My Seed Bank');
 *
 * INSERT INTO species (id, name) VALUES (1, 'First Species');
 * INSERT INTO species (id, name) VALUES (2, 'Second Species');
 *
 * INSERT INTO accessions (id, facility_id, species_id) VALUES (3, 10, 1);
 * INSERT INTO accessions (id, facility_id, species_id) VALUES (4, 10, 2);
 *
 * INSERT INTO bags (accession_id, number) VALUES (3, 'First bag for accession 3');
 * INSERT INTO bags (accession_id, number) VALUES (3, 'Second bag for accession 3');
 *
 * INSERT INTO germination_tests (id, accession_id, start_date) VALUES (5, 3, '2021-10-01');
 * INSERT INTO germination_tests (id, accession_id, start_date) VALUES (6, 3, '2021-10-15');
 *
 * INSERT INTO germinations (test_id, seeds_germinated) VALUES (5, 10);
 * INSERT INTO germinations (test_id, seeds_germinated) VALUES (5, 20);
 * ```
 *
 * This represents the following hierarchy.
 *
 * ```
 * - Facility 10, name "My Seed Bank"
 *   - Accession ID 3, species 1 "First Species"
 *      - Bag number "First bag for accession 3"
 *      - Bag number "Second bag for accession 3"
 *      - Germination Test 5, start date 2021-10-01
 *        - Germination, 10 seeds germinated
 *        - Germination, 20 seeds germinated
 *      - Germination Test 6, start date 2021-10-15
 *   - Accession ID 4, species 2 "Second Species", no bags or tests
 * ```
 *
 * Now you construct a query like this, referencing the search fields in [AccessionsNamespace].
 *
 * ```kotlin
 * val rootPrefix = SearchFieldPathPrefix(accessionsNamespace)
 * val queryBuilder = NestedQueryBuilder(dslContext, rootPrefix)
 *
 * queryBuilder.addSelectFields(
 *     listOf(
 *         rootPrefix.resolve("id"),
 *         rootPrefix.resolve("species"),
 *         rootPrefix.resolve("bags.number"),
 *         rootPrefix.resolve("facility.name"),
 *         rootPrefix.resolve("germinationTests.startDate"),
 *         rootPrefix.resolve("germinationTests.germinations.seedsGerminated")))
 *
 * // You can sort on fields you didn't select, and vice versa, though we won't do that here just
 * // to keep the example easier to follow.
 * queryBuilder.addSortFields(
 *     listOf(
 *         SearchSortField(rootPrefix.resolve("species")),
 *         SearchSortField(rootPrefix.resolve("germinationTests.startDate")),
 *         SearchSortField(rootPrefix.resolve("bags.number")),
 *         SearchSortField(rootPrefix.resolve("germinationTests.germinations.seedsGerminated"))))
 *
 * val results = queryBuilder.toSelectQuery().fetch()
 * ```
 *
 * You'll get back a list of results that looks like this, minus the comments, of course:
 *
 * ```json
 * [
 *   # The first sort key is "species". "First Species" is alphabetically lower than "Second
 *   # Species", so accession 3 is first in the results list.
 *   {
 *     "id": "3",
 *     "species": "First Species",
 *     "bags": [
 *       # The first (and only) sort field under "bags" is the bag number, so this list is
 *       # sorted alphabetically on that value.
 *       { "number": "First bag for accession 3" },
 *       { "number": "Second bag for accession 3" }
 *     ],
 *     "facility": {
 *       # This is a single value, not a list, since an accession only ever has one facility.
 *       "name": "My Seed Bank"
 *     },
 *     "germinationTests": [
 *       # The first sort field under "germinationTests" is the start date, so this list is sorted
 *       # in ascending start date order.
 *       {
 *         "germinations": [
 *           # The first sort field under "germinationTests.germinations" is "seedsGerminated",
 *           # so this list is sorted by that.
 *           { "seedsGerminated": "10" },
 *           { "seedsGerminated": "20" }
 *         ],
 *         "startDate": "2021-10-01"
 *       },
 *       {
 *         "startDate": "2021-10-15"
 *       }
 *     ]
 *   },
 *   {
 *     # Empty lists, and scalar fields with no values, are omitted from the result. Each result
 *     # is a Map<String,Any> which might be implemented as a hashtable, so callers shouldn't
 *     # assume the fields are in any particular order.
 *     "species": "Second Species",
 *     "id": "4",
 *     "facility": {
 *       "name": "My Seed Bank"
 *     }
 *   }
 * ]
 * ```
 *
 * TODO: Fold the below into the flattened sublists section or the aliases section, or kill it
 *
 * Contrast this with the older non-nested fields, e.g.,
 *
 * ```kotlin
 * val rootPrefix = SearchFieldPathPrefix(accessionsNamespace)
 * val queryBuilder = NestedQueryBuilder(dslContext, rootPrefix)
 *
 * queryBuilder.addSelectFields(
 *     listOf(
 *         rootPrefix.resolve("id"),
 *         rootPrefix.resolve("species"),
 *         rootPrefix.resolve("bagNumber")))
 * val results = queryBuilder.toSelectQuery().fetch()
 * ```
 *
 * which would result in multiple entries for the same accession:
 *
 * ```json
 * [
 *   {
 *     "id": "3",
 *     "species": "First Species",
 *     "bagNumber": "First bag for accession 3"
 *   },
 *   {
 *     "id": "3",
 *     "species": "First Species",
 *     "bagNumber": "Second bag for accession 3"
 *   },
 *   {
 *     "id": "4",
 *     "species": "Second Species"
 *   }
 * ]
 * ```
 *
 * # Implementation details
 *
 * ## Field paths
 *
 * Search results are returned as a list of maps of field names to field values. A field value can
 * be a string, a map of field names to field values, or a list of maps of field names to field
 * values. Kotlin doesn't have support for union types, so this gets represented as
 * `List<Map<String, Any>>`.
 *
 * There are thus three styles of search field. Internally, this code refers to them as **scalars**
 * and **sublists** (the latter of which has two styles) to reflect how they're returned in search
 * results.
 *
 * Scalar fields are single values, and are always represented as strings in the search results.
 * Scalar field values are either directly stored on main tables (e.g., `accessions.total_quantity`)
 * or are represented as foreign key columns on main tables and looked up from reference tables
 * (e.g., `germination_tests.seed_type_id`.)
 *
 * Sublist fields, as the name suggests, are containers with their own lists of fields. They come in
 * two flavors: "multi-value" and "single-value." A multi-value sublist turns into a list of JSON
 * objects in the search results, whereas a single-value sublist turns into a single JSON object.
 * Each of those objects can contain a mix of scalar fields and sublist fields.
 *
 * Sublist fields aren't directly searchable; they are just containers for scalar fields.
 *
 * A sublist field is identified by the presence of a `.` in its name. For example, the field
 * `germinationTests.germinations.recordingDate` represents a sublist field called
 * `germinationTests` each element of which contains a sublist field called `germinations` each
 * element of which contains a scalar field called `recordingDate`.
 *
 * A field name is represented as a [SearchFieldPath] which consists of a prefix and a scalar field.
 * The prefix is represented as a [SearchFieldPrefix] and includes a list of sublist fields which
 * form a path to the location of the scalar field (`germinationTests` and `germinations` in the
 * above example). The prefix also includes a "root namespace" (in the form of a
 * [SearchFieldNamespace]) that identifies where in the application's data model the prefix begins.
 * In the example, the root namespace would indicate that the fields are all under the "accessions"
 * part of the data model.
 *
 * A filesystem analogy might make the pieces easier to understand:
 *
 * ```
 * # This is the root namespace; everything else is relative to it.
 * cd /organizations/projects/sites/facilities/accessions
 * # This is two sublist fields followed by a scalar field.
 * cat germinationTests/germinations/recordingDate
 * ```
 *
 * ## Query hierarchy
 *
 * The query is represented as a tree of [NestedQueryBuilder]s. The root node represents the query
 * as a whole, and each child node represents the query for a sublist field. Each node has a
 * [SearchFieldPrefix].
 *
 * For example, a search field of `germinationTests.germinations.recordingDate` with a root
 * namespace of `accessions` would be turned into a structure something like this YAML document:
 *
 * ```yaml
 * prefix:
 *   root: accessions
 *   sublists: []
 * scalarFields: []
 * sublistQueryBuilders:
 *   germinationTests:
 *     prefix:
 *       # This prefix refers to the germinationTests sublist under accessions; the root
 *       # is the same as the parent's root.
 *       root: accessions
 *       sublists: [germinationTests]
 *     scalarFields: []
 *     sublistQueryBuilders:
 *       germinations:
 *         prefix:
 *           # This prefix refers to the germinations sublist under the germinationTests
 *           # sublist under accessions. As before, the root is the same as the parent's.
 *           root: accessions
 *           sublists: [germinationTests, germinations]
 *         scalarFields: [recordingDate]
 *         sublistQueryBuilders: []
 * ```
 *
 * Field names are always evaluated relative to the current node (that is, the prefix is stripped
 * off to get a relative name). So for example, the middle node in the above hierarchy strips off
 * its prefix and treats the search field as having a name of `germinations.recordingDate`. Because
 * that name contains a `.` character, the middle node needs to peel off the part before the `.` and
 * treat the field as a sublist called `germinations`.
 *
 * The examples in this document treat field names as string values. Although the client-facing API
 * accepts field names as period-separated strings, internally they are represented as
 * [SearchFieldPath] objects, which are automatically constructed during deserialization of the JSON
 * request payloads.
 *
 * ## Multisets
 *
 * This implementation is heavily dependent on implementation details of jOOQ's "multiset" operator.
 * For background on multisets, please see
 * [the jOOQ docs](https://www.jooq.org/doc/latest/manual/sql-building/column-expressions/multiset-value-constructor/)
 * or for a deeper dive,
 * [this blog post](https://blog.jooq.org/jooq-3-15s-new-multiset-operator-will-change-how-you-think-about-sql/)
 * introducing the feature.
 *
 * Conceptually, this works kind of like the examples in the docs: it constructs a query on the
 * table for the root namespace where the list of fields includes [DSL.multiset] values that hold
 * child records. Those child records can, in turn, have their own multiset fields if they
 * themselves have children, e.g., an accession has germination tests each of which has
 * germinations.
 *
 * Under the covers, on PostgreSQL, jOOQ turns a multiset into an aggregation operation over a
 * subquery. The aggregation function returns a JSON array where each element of the array
 * represents a row of results from the subquery. Each of those elements is itself a JSON array of
 * the field values in the subquery's SELECT clause. In other words, the multiset turns into a
 * two-dimensional array indexed by row number and field position. This implementation detail will
 * become important later.
 *
 * ## Search criteria
 *
 * Say you have an accession with two bags, "A" and "B". The user does a search with a root
 * namespace of `accessions` and asks for results containing bag "A".
 *
 * There are two ways that could work, neither of them wrong: treat the search criterion as a filter
 * on bag numbers (returning a result with a single bag) or treat it as a filter on accessions
 * (returning a result with two bags).
 *
 * Our product decision is to do the latter. Search criteria control which top-level results are
 * returned, but each result includes the full set of values for all its fields. When you search for
 * bag "A", what you're really telling the system is that you want all _accessions_ that have a bag
 * called "A". And for each accession that matches the search criteria, you get back the full list
 * of bags.
 *
 * That's relevant here because it changes what the SQL looks like: we don't apply any search
 * criteria to the multiset subqueries, because we want to return the full set of data for any
 * accessions that match the criteria.
 *
 * Instead, user-supplied search criteria are turned into a subquery which is used to generate a
 * list of accession IDs. The nested query then selects the values of all the requested fields for
 * the accessions on that list. The subquery is constructed in [SearchService.filterResults].
 *
 * In the above example, the query is structured like this (pseudocode):
 *
 * ```
 * SELECT accessions.id,
 *        MULTISET(SELECT bags.number
 *                 FROM bags
 *                 WHERE accessions.id = bags.accession_id)
 * FROM accessions
 * WHERE accessions.id IN (
 *     SELECT accessions.id
 *     FROM accessions
 *     JOIN bags ON accessions.id = bags.accession_id
 *     WHERE bags.number = 'A')
 * ```
 *
 * The key point is that the multiset will contain _all_ the bags for the accession.
 *
 * ## Ordering
 *
 * Unfortunately, the happy-path usage of multisets breaks down once you need to sort the results
 * based on a field in a child table, because the ordering needs to bubble up through the rest of
 * the query too. For example, say you have two accessions each with two bags:
 *
 * - Accession 1: Bags X and Y
 * - Accession 2: Bags A and B
 *
 * You specify that you want to sort the search results in ascending order of bag number. In that
 * case, you want accession 2 to appear before accession 1 because bag A comes before the others.
 * You also want each accession's bag numbers to be sorted.
 *
 * What does that query look like, though? Ordering the bags for each accession is simple enough;
 * just put an `ORDER BY` on the subquery. But how do you sort the accessions at the top level of
 * the query?
 *
 * ```
 * SELECT accessions.id,
 *        MULTISET(SELECT bags.id, bags.number
 *                 FROM bags
 *                 WHERE accessions.id = bags.accession_id
 *                 ORDER BY bags.number)
 * FROM accessions
 * WHERE accessions.id IN (...)
 * ORDER BY ?????
 * ```
 *
 * It turns out there is no officially-supported way for the outer query to peek inside the
 * multiset. If you want to do it portably, for now
 * [you have to repeat the subquery.](https://stackoverflow.com/questions/69577525/how-to-order-by-values-from-nested-multisets)
 *
 * Doing it that way would make queries more expensive, so instead we break portability and rely on
 * the way multisets are implemented on PostgreSQL. Recall that they turn into two-dimensional
 * arrays.
 *
 * We know that the multiset is already sorted correctly because the subquery has an `ORDER BY`
 * clause: the first row in the multiset will always be the one that should determine the sort order
 * of the parent row. In the example query, the first row of the multiset will have bag number "X"
 * for accession 1 and "A" for accession 2.
 *
 * We also know the order of the fields in the multiset's subquery. In the example, the field we
 * want to sort on is the second one, `bags.number`.
 *
 * Putting the two together, and remembering that JSON arrays are 0-indexed, we want to do something
 * like this (note the column alias on the multiset):
 *
 * ```
 * SELECT accessions.id,
 *        MULTISET(
 *            SELECT bags.id, bags.number
 *            FROM bags
 *            WHERE accessions.id = bags.accession_id
 *            ORDER BY bags.number
 *        ) AS bags_multiset
 * FROM accessions
 * WHERE accessions.id IN (...)
 * ORDER BY bags_multiset[0][1]
 * ```
 *
 * ## Lateral joins
 *
 * Unfortunately, an `ORDER BY` clause can't use a computed value from the `SELECT` clause of the
 * same query. So in the above example, `ORDER BY bags_multiset[0][1]` won't work because
 * `bags_multiset` is an alias for the output of a computation, not an input to the query.
 *
 * The solution is to move the multisets out of the main query. We do this using
 * [lateral subqueries](https://www.postgresql.org/docs/13/queries-table-expressions.html#QUERIES-LATERAL)
 * resulting in a structure like
 *
 * ```
 * SELECT accessions.id, bags_multiset
 * FROM accessions
 * LEFT JOIN LATERAL (
 *     SELECT MULTISET(
 *         SELECT bags.id, bags.number
 *         FROM bags
 *         WHERE accessions.id = bags.accession_id
 *         ORDER BY bags.number
 *     ) AS bags_multiset
 * )
 * WHERE accessions.id IN (...)
 * ORDER BY bags_multiset[0][1]
 * ```
 *
 * Here the `bags_multiset` column is computed in the subquery and is an input to the top-level
 * query, so we can reference it in the `ORDER BY` clause.
 *
 * ## Enums
 *
 * Some fields are stored as integer IDs in the database, but rendered as human-readable strings in
 * the UI. For example, an accession with `state_id = 10` is shown as having a state of `Pending`
 * for a user using the service in English. These fields have a fixed set of values and get turned
 * into enums in the generated Kotlin code.
 *
 * When the user sorts by a field that maps to an enum, we want to order it based on the (possibly
 * localized) display name, not on the numeric ID. Currently, we do that by generating a `CASE`
 * expression to map the IDs to their display names so the database can sort on them.
 *
 * ## Flattened sublists
 *
 * Most of the discussion above was concerned with how we return search results in tree-structured
 * form. But sometimes a tree structure isn't the right representation. Think, for example, of
 * exporting search results to a CSV file: by definition there is no way to nest values, and if a
 * sublist has multiple values for a field, they need to be represented as multiple rows. In short,
 * the results need to be returned as a series of flat records where the values are always scalar.
 *
 * To support this use case, the search code has the ability to "flatten" a sublist. Flattening a
 * sublist causes the sublist's fields to be merged into the parent. The sublist no longer appears
 * explicitly as a field in the search results. If the sublist is multi-value and there's more than
 * one list entry, there will be one copy of the parent for each entry of the sublist.
 *
 * A flattened sublist is specified by using an underscore `_` rather than a period `.` as the
 * delimiter after the sublist name. Internally, a flattened sublist is distinguished from a nested
 * one by the [SublistField.isFlattened] property.
 *
 * A simple pair of examples should help illustrate what flattening does. In the first search
 * result, the caller asked for `id` and `bags.number` in the `accessions namespace.
 *
 * ```json
 * [
 *   { "id": "1", "bags": [ { "number": "200" }, { "number": "300" } ] },
 *   { "id": "2", "bags": [ { "number": "400" } ] }
 * ]
 * ```
 *
 * In the second, the caller asked for `id` and `bags_number`, using an underscore to ask the system
 * to flatten the `bags` sublist.
 *
 * ```json
 * [
 *   { "id": "1", "bags_number": "200" },
 *   { "id": "1", "bags_number": "300" },
 *   { "id": "2", "bags_number": "400" },
 * ]
 * ```
 *
 * Two things are happening here: the `number` field from the `bags` sublist is included as a
 * top-level field in each result, and there are now two search results for ID 1 because there were
 * two values in the `bags` sublist.
 *
 * Flattened sublists are potentially useful even when you don't need all the search results to be
 * completely tabular. In particular, they can simplify the representation of fields from
 * single-value sublists. For example, compare what happens when you ask for `facility.name`:
 *
 * ```json
 * [ { "facility": { "name": "My Seed Bank" } } ]
 * ```
 *
 * and `facility_name`:
 *
 * ```json
 * [ { "facility_name": "My Seed Bank" } ]
 * ```
 *
 * It is possible to mix nested and flattened sublists: nested sublists can contain flattened ones
 * but not the other way around. Mixing the two styles is not useful for the "export to CSV" case
 * (when you wouldn't want any nesting at all) but is useful for the single-value-sublist scenario
 * in the second pair of examples above.
 *
 * Constructing the SQL queries for flattened sublists is far simpler than for nested ones. Each
 * flattened sublist turns into a simple `LEFT JOIN` operation and there is no need for multisets at
 * all. The results of the SQL join already have the right structure: the columns from all the
 * joined tables are mixed together in a single result row, and there are separate results if one of
 * the joined tables has multiple rows that match the join criterion.
 */
class NestedQueryBuilder(
    private val dslContext: DSLContext,
    /**
     * Field path covered by this node. For example, the node that contains the field
     * `germinationTests.germinations.seedsSown` would have a prefix of
     * `germinationTests.germinations`. Must be an absolute path.
     */
    private val prefix: SearchFieldPrefix,
) {
  /**
   * Conditions to include in this query's `WHERE` clause. This includes conditions that are
   * specified by the caller as well as internally-generated ones that filter multiset subqueries to
   * only include rows related to the current row from the parent query.
   */
  private val conditions = mutableListOf<Condition>()

  /** Scalar fields indexed by relative name. */
  private val scalarFields = mutableMapOf<String, SearchField>()

  /**
   * Query builders for sublists indexed by the first element of their relative names. For example,
   * if this node has a prefix of `a.b` and there are two fields whose full names are `a.b.c.d.e`
   * and `a.b.f.g`, this map would contain keys `c` and `f`.
   */
  private val sublistQueryBuilders = mutableMapOf<String, NestedQueryBuilder>()

  // TODO: Document
  private val flattenedSublists = mutableSetOf<SublistField>()

  /**
   * Zero-indexed position of each field in the `SELECT` clause. Both scalar and sublist fields are
   * included. However, this does _not_ include fields that are only used as sort keys.
   */
  private val selectFieldPositions = mutableMapOf<String, Int>()

  /**
   * Fields that should be used to sort the query results. This can include both scalar and sublist
   * fields. Sublist sort fields will also appear in [sublistQueryBuilders] even if they aren't
   * going to be included in the search results. Scalar sort fields don't appear in [scalarFields]
   * if they're only used for sorting.
   */
  private val sortFields = mutableSetOf<SearchSortField>()

  /**
   * Zero-indexed position of each sort field in the `SELECT` clause.
   *
   * If a sort field is also being included in the search results, this may be the same as the
   * position of that field in [selectFieldPositions].
   *
   * In some cases, the raw value in the database column can't be used as a sort key and we need to
   * sort based on a derived value. (For example, the `active` field on accessions is derived from
   * the accession's state.) In that case, both the raw column and the expression that calculates
   * the sort key need to appear in the `SELECT` clause; [selectFieldPositions] will have the
   * position of the field with the raw value, and [sortFieldPositions] will have the position of
   * the expression.
   */
  private val sortFieldPositions = mutableMapOf<String, Int>()

  /**
   * Zero-indexed position that the next field that's added to the `SELECT` clause will have. Since
   * the `SELECT` clause can ultimately include a mix of raw columns and expressions (see
   * [sortFieldPositions]) this can't easily be calculated by just looking at the number of entries
   * in the field lists.
   */
  private var nextSelectFieldPosition = 0

  /**
   * The result of [toSelectQuery]. Rendering the query is a destructive operation and can only be
   * done once, but we want to allow callers (including parent [NestedQueryBuilder]s) to access the
   * rendered query as many times as they need.
   */
  private val renderedQuery = MemoizedValue<SelectSeekStepN<Record>>()

  /**
   * The result of [toMultiset] if this is a sublist. This is cached rather than computed from
   * scratch on each [toMultiset] call because jOOQ can potentially generate column and table
   * aliases and we don't want those to change from one [toMultiset] call to the next.
   */
  private val renderedMultiset = MemoizedValue<Field<List<Map<String, Any>>?>>()

  /**
   * Includes a set of fields in the search results.
   *
   * @throws IllegalStateException The query has already been rendered.
   */
  fun addSelectFields(fields: Collection<SearchFieldPath>) {
    assertNotRendered()
    fields.forEach { addSelectField(it) }
  }

  /**
   * Uses a list of fields to sort the search results.
   *
   * @throws IllegalStateException The query has already been rendered.
   */
  fun addSortFields(sortFields: List<SearchSortField>) {
    assertNotRendered()
    sortFields.forEach { addSortField(it) }
  }

  /**
   * Adds a search condition to this query.
   *
   * @throws IllegalStateException The query has already been rendered.
   */
  fun addCondition(condition: Condition) {
    assertNotRendered()
    conditions.add(condition)
  }

  /**
   * Returns a jOOQ query object for the currently-configured query. Once this method is called,
   * attempts to modify its field lists or conditions will throw [IllegalStateException].
   */
  fun toSelectQuery(distinct: Boolean = false): SelectSeekStepN<Record> {
    return renderedQuery.get {
      val select =
          if (distinct) dslContext.selectDistinct(getSelectFields())
          else dslContext.select(getSelectFields())

      val selectFrom = select.from(getSearchTable().fromTable)
      val selectWithParents = joinFlattenedSublists(selectFrom)

      // Add lateral joins for all the multiset subqueries.
      val selectWithLateral =
          sublistQueryBuilders.keys.fold(selectWithParents) { query, sublistName ->
            val lateral = DSL.lateral(DSL.select(getMultiset(sublistName)))
            query.leftJoin(lateral).on(DSL.trueCondition())
          }

      val selectWithConditions = selectWithLateral.where(conditions)

      selectWithConditions.orderBy(getOrderBy(!distinct))
    }
  }

  /**
   * Converts a row of results of the SELECT statement into a map of scalar and sublist fields.
   *
   * This is called in two contexts. The caller can use it as a [RecordMapper] by passing it as an
   * argument to the `fetch` method when it executes the query. The result of using it that way is
   * that `fetch` will return a list of `Map<String,Any>` instead of the default behavior which is
   * to return a list of `Record`.
   *
   * It is also used as an
   * [ad-hoc converter](https://www.jooq.org/doc/3.0/manual/sql-execution/fetching/ad-hoc-converter/)
   * that's attached to the multisets that are generated for sublists. The same transformation
   * happens: the result of the subquery inside the multiset is turned into `List<Map<String,Any>>`.
   * The result of that conversion becomes the value of the multiset field in the parent query. See
   * the jOOQ docs for a more detailed explanation of how ad-hoc converters and nested queries
   * interact with each other.
   *
   * Returns null rather than an empty map if there were no values in any fields.
   */
  fun convertToMap(record: Record): Map<String, Any>? {
    val fieldValues = mutableMapOf<String, Any>()

    scalarFields.forEach { (name, field) ->
      val value = field.computeValue(record)
      if (value != null) {
        fieldValues[name] = value
      }
    }

    // It is possible to sort the query results by a field that isn't in the list of fields you
    // want to get back in the search results. If such a sort field is in a sublist, then there
    // needs to be a multiset for it (so the parent query can include it in `ORDER BY`) but we don't
    // want to include it in the results. So we only want to consider sublists that contain at least
    // one field that's going to be returned to the caller.
    val sublistsWithSelectFields = sublistQueryBuilders.filterValues { it.hasSelectFields() }

    sublistsWithSelectFields.forEach { (sublistName, queryBuilder) ->
      val values: List<Map<String, Any>>? = record[getMultiset(sublistName)]
      val firstValue = values?.firstOrNull()

      if (firstValue != null) {
        fieldValues[sublistName] = if (queryBuilder.prefix.isMultiValue) values else firstValue
      }
    }

    return fieldValues.ifEmpty { null }
  }

  /**
   * Sanity check to make sure someone doesn't try to modify a query after rendering it. Query
   * rendering is allowed to be a destructive operation.
   */
  private fun assertNotRendered() {
    if (renderedQuery.isComputed) {
      throw IllegalStateException("Cannot modify query after it has been rendered")
    }
  }

  /**
   * Adds a field to the list of fields the caller wants to get back in the search results. If the
   * field is in a sublist, adds it to the sublist query, creating the query if needed.
   */
  private fun addSelectField(fieldPath: SearchFieldPath) {
    val relativeField = fieldPath.relativeTo(prefix)

    if (relativeField.searchField is AliasField) {
      addFlattenedSublists(relativeField.searchField.targetPath.sublists)
    }

    if (relativeField.isFlattened) {
      addFlattenedSublists(relativeField.sublists)

      val searchField = fieldPath.searchField
      val fieldName = "$relativeField"
      scalarFields[fieldName] = searchField
      if (fieldName !in selectFieldPositions) {
        selectFieldPositions[fieldName] = nextSelectFieldPosition
        nextSelectFieldPosition += searchField.selectFields.size
      }
    } else if (relativeField.isNested) {
      // Prefix = a.b, fieldName = a.b.c.d => make a sublist "c" to hold field "d"
      val sublistName = getSublistName(relativeField)
      getSublistQuery(relativeField).addSelectField(fieldPath)
      selectFieldPositions.computeIfAbsent(sublistName) { nextSelectFieldPosition++ }
    } else {
      val searchField = fieldPath.searchField
      val fieldName = searchField.fieldName
      scalarFields[fieldName] = searchField
      if (fieldName !in selectFieldPositions) {
        selectFieldPositions[fieldName] = nextSelectFieldPosition
        nextSelectFieldPosition += searchField.selectFields.size
      }
    }
  }

  /**
   * Adds a field to the list of fields the caller wants to use to sort the search results. If the
   * field is in a sublist, adds it to the sublist query's sort fields, creating the query if
   * needed.
   */
  private fun addSortField(sortField: SearchSortField) {
    val relativeField = sortField.field.relativeTo(prefix)

    sortFields.add(sortField)

    if (relativeField.isFlattened) {
      addFlattenedSublists(relativeField.sublists)
    } else {
      if (relativeField.isNested) {
        // If we are sorting by field "a.b", then sublist "a" needs to be sorted by "b".
        getSublistQuery(relativeField).addSortField(sortField)
      }
    }
  }

  private fun addFlattenedSublists(sublists: Collection<SublistField>) {
    sublists.forEach { sublist ->
      if (sublist.isFlattened) {
        flattenedSublists.add(sublist)
      } else {
        throw IllegalArgumentException("BUG! Sublist $sublist is not flattened")
      }
    }
  }

  /** Returns the [NestedQueryBuilder] for a sublist, creating it if needed. */
  private fun getSublistQuery(relativeField: SearchFieldPath): NestedQueryBuilder {
    if (!relativeField.isNested) {
      throw IllegalArgumentException("Cannot get sublist for non-nested field $relativeField")
    }

    val sublistName = relativeField.sublists.first().name

    return sublistQueryBuilders.computeIfAbsent(sublistName) {
      NestedQueryBuilder(dslContext, prefix.withSublist(sublistName))
    }
  }

  /**
   * For non-nested fields in child tables, joins the top-level query with those tables. This is not
   * used when child tables are queried using sublist fields, but is required for backward
   * compatibility with the existing search API that expects child table values to be returned as
   * multiple top-level entries in the results list.
   *
   * TODO: Update docs
   */
  private fun joinFlattenedSublists(query: SelectJoinStep<Record>): SelectJoinStep<Record> {
    return flattenedSublists.fold(query) { joinedQuery, sublist ->
      joinedQuery.leftJoin(sublist.namespace.searchTable.fromTable).on(sublist.conditionForMultiset)
    }
  }

  /**
   * Returns the list of fields to include in the `ORDER BY` clause of this query. This can include
   * a mix of JSONB array expressions (when sorting by a field in a sublist), numeric column indexes
   * (when sorting by the raw value of a column that already appears in the `SELECT` clause), and
   * computation expressions (when sorting by a derived value).
   */
  private fun getOrderBy(includeDefaultFields: Boolean): List<OrderField<*>> {
    val orderByFields = sortFields.map { getOrderByField(it) }

    return if (includeDefaultFields) {
      // Fall back on the default sort order for each table to ensure stable ordering of query
      // results if the user doesn't specify precise sort criteria.
      val defaultSortFields =
          scalarFields.values.map { it.table }.distinct().flatMap { table ->
            table.defaultOrderFields
          }

      orderByFields + defaultSortFields
    } else {
      orderByFields
    }
  }

  /**
   * Returns a field to include in an `ORDER BY` clause.
   *
   * If the field is in a sublist, returns an expression to pull the field value from the first row
   * of the sublist's multiset.
   *
   * If the field appears in the `SELECT` clause, returns its 1-indexed position in the `SELECT`
   * field list.
   *
   * Otherwise, returns a field with an expression that calculates the sort key.
   */
  private fun getOrderByField(sortField: SearchSortField): SortField<*> {
    val relativeField = sortField.field.relativeTo(prefix)
    val field = sortField.field.searchField
    val relativeName = "$relativeField"
    val sortFieldIndex = sortFieldPositions[relativeName]

    val orderByField =
        if (sortFieldIndex != null) {
          // Sorting by a column that's in the `SELECT` clause.
          DSL.inline(sortFieldIndex + 1)
        } else if (relativeField.isNested) {
          getOrderByFieldForSublist(sortField.field)
        } else {
          // Sorting by a derived value.
          field.orderByField
        }

    return orderByField.sort(sortField.direction.sortOrder).nullsLast()
  }

  /**
   * Returns the list of fields to include in the `SELECT` clause of the query. This includes fields
   * that should be returned to the caller in search results, and also, if this node is a sublist
   * query, any fields that the parent query will need to be able to use in its `ORDER BY` clause.
   */
  private fun getSelectFields(): List<Field<*>> {
    val selectFields = scalarFields.values.flatMap { it.selectFields }

    val sublistFields =
        sublistQueryBuilders.keys.map { sublistName ->
          val multiset = getMultiset(sublistName)
          DSL.field(multiset.name, multiset.dataType)
        }

    return selectFields + getSortFieldsToExposeToParent() + sublistFields
  }

  /**
   * Returns a list of fields to add to the `SELECT` clause so they can be referenced in the `ORDER
   * BY` clause of the parent query.
   */
  private fun getSortFieldsToExposeToParent(): List<Field<*>> {
    return sortFields
        .filter {
          val relativeField = it.field.relativeTo(prefix)
          relativeField.isFlattened || !relativeField.isNested
        }
        .distinctBy { it.field }
        .mapNotNull { sortField ->
          val field = sortField.field.searchField
          val relativeField = sortField.field.relativeTo(prefix)
          val relativeName = "$relativeField"

          if (relativeName !in sortFieldPositions) {
            // If we are selecting and sorting on an enum field, the sortable value will be a CASE
            // expression. We need to make that available in the multiset so the parent can order
            // by
            // it.
            val selectFieldIndex = selectFieldPositions[relativeName]
            val orderByField = field.orderByField
            if (selectFieldIndex != null &&
                field.selectFields.size == 1 &&
                field.selectFields[0] == orderByField) {
              sortFieldPositions[relativeName] = selectFieldIndex
              null
            } else {
              sortFieldPositions[relativeName] = nextSelectFieldPosition++
              orderByField
            }
          } else {
            null
          }
        }
  }

  /**
   * Returns the table to use in the `FROM` clause of this query. This will usually be the table
   * that contains most of the scalar fields for the namespace of the current prefix.
   */
  private fun getSearchTable(): SearchTable {
    return prefix.namespace.searchTable
  }

  /**
   * Returns the multiset field for a sublist query, rendering it if it hasn't been rendered before.
   */
  private fun getMultiset(sublistName: String): Field<List<Map<String, Any>>?> {
    val queryBuilder =
        sublistQueryBuilders[sublistName]
            ?: throw IllegalStateException("BUG! Sublist $sublistName not found")
    return queryBuilder.toMultiset()
  }

  /**
   * Renders this query as a multiset field that can be included in a parent query. The field will
   * use [prefix] as part of its column alias; this is mostly for ease of debugging if a human needs
   * to look at the SQL.
   */
  private fun toMultiset(): Field<List<Map<String, Any>>?> {
    return renderedMultiset.get {
      if (isRoot()) {
        throw IllegalStateException("BUG! Root node should never be rendered as a multiset")
      }

      val alias = "$prefix".replace('.', '_').lowercase() + "multiset"

      prefix.sublistField?.conditionForMultiset?.let { addCondition(it) }

      DSL.multiset(toSelectQuery()).`as`(alias).convertFrom { result ->
        result.mapNotNull { record -> convertToMap(record) }
      }
    }
  }

  /**
   * Returns true if this builder or any of its descendents contains a field that will be returned
   * as part of the query results. This will be false for builders that only contain sort criteria.
   */
  private fun hasSelectFields(): Boolean {
    return scalarFields.isNotEmpty() || sublistQueryBuilders.values.any { it.hasSelectFields() }
  }

  /**
   * Returns a [Field] that extracts a scalar field from the first row of a sublist's multiset for
   * use in the `ORDER BY` clause of this query.
   */
  private fun getOrderByFieldForSublist(field: SearchFieldPath): Field<Any?> {
    val relativeField = field.relativeTo(prefix)
    if (!relativeField.isNested) {
      throw IllegalArgumentException("BUG! $field is not a sublist")
    }

    val sublistName = getSublistName(relativeField)
    val queryBuilder =
        sublistQueryBuilders[sublistName]
            ?: throw IllegalStateException("BUG! Unable to find subquery for $relativeField")
    val multiset = getMultiset(sublistName)

    return DSL.field(queryBuilder.buildMultisetFieldExpression(multiset.name, field))
  }

  /**
   * Given a SQL expression that resolves to this query's multiset, returns an expression that
   * resolves to the value of a sort field.
   *
   * This method is heavily dependent on the way jOOQ implements multisets on PostgreSQL. It won't
   * work on other databases and it may break in future jOOQ versions if they change the multiset
   * implementation.
   *
   * This is only needed to work around a limitation of the jOOQ multiset API; once there's a
   * portable way to manipulate the contents of multisets, this method can be updated to use that
   * instead. See
   * [this Stack Overflow question](https://stackoverflow.com/questions/69577525/how-to-order-by-values-from-nested-multisets)
   * (and especially the answer from Lukas Eder, the lead developer on jOOQ) for a bit more context.
   */
  private fun buildMultisetFieldExpression(
      parentExpression: String,
      field: SearchFieldPath
  ): String {
    val relativeField = field.relativeTo(prefix)
    val relativeName = "$relativeField"

    return if (relativeField.isNested) {
      // We're pulling the value out of a multiset.
      val sublistName = getSublistName(relativeField)
      val fieldPosition =
          selectFieldPositions[sublistName]
              ?: throw IllegalStateException("BUG! No field position for sublist $sublistName")

      val queryBuilder =
          sublistQueryBuilders[sublistName]
              ?: throw IllegalStateException("BUG! No sublist for $sublistName")

      queryBuilder.buildMultisetFieldExpression("$parentExpression->0->$fieldPosition", field)
    } else {
      // We are the subquery where the field actually lives, so we want to dereference the actual
      // value.
      val fieldPosition =
          sortFieldPositions[relativeName]
              ?: throw IllegalStateException("BUG! No sort field position for $relativeName")

      "$parentExpression->0->>$fieldPosition"
    }
  }

  /** Returns true if this node is the root of the tree of [NestedQueryBuilder]s. */
  private fun isRoot() = prefix.isRoot

  /** Returns the name of the sublist containing the given relative field. */
  private fun getSublistName(relativeField: SearchFieldPath): String {
    return if (relativeField.isNested) {
      relativeField.sublists.first().name
    } else {
      throw IllegalArgumentException("BUG! $relativeField has no sublist")
    }
  }
}
