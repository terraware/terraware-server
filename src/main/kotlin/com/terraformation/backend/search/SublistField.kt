package com.terraformation.backend.search

import org.jooq.Condition

/**
 * A container for related search fields. A sublist represents a relationship between two
 * [SearchFieldNamespace]s and may be an element of a [SearchFieldPrefix].
 *
 * See the "Field paths" section of the class documentation for [NestedQueryBuilder] for a
 * discussion of how sublists work including some examples, but briefly, if you think of the data
 * model as a graph of things like projects, sites, facilities, and so forth, then a sublist is an
 * edge in the graph as viewed from one of its nodes.
 *
 * @see NestedQueryBuilder
 */
data class SublistField(
    /** Name of the sublist as it appears in search field prefixes. */
    val name: String,

    /**
     * Namespace that the sublist points to. This determines which field names are valid after this
     * sublist in a field path.
     */
    val namespace: SearchFieldNamespace,

    /**
     * True if this sublist represents a one-to-many relationship such that the search results
     * should include a list of values for this sublist. False if it represents a one-to-one or
     * many-to-one relationship such that the search results should include a single value for this
     * sublist.
     *
     * For example, for the "projects" path element, a reference to the "sites" path element would
     * be one-to-many because there can be many sites for a single project. But from that same
     * "projects" path element, a reference to "organization" would _not_ be one-to-many because
     * each project only has one organization.
     */
    val isMultiValue: Boolean,

    /**
     * A condition to add to the `WHERE` clause of a multiset subquery to correlate it with the
     * table for the namespace that contains this field. This will generally be the condition you
     * would put in the `ON` part of a `LEFT JOIN x ON Y` clause that connects this sublist to its
     * parent.
     *
     * For example, if this is the `sites` sublist field of the `projects` namespace, the condition
     * would be `SITES.PROJECT_ID.eq(PROJECTS.ID)`.
     */
    val conditionForMultiset: Condition,

    // TODO: Document
    val isFlattened: Boolean = false,
) {
  val delimiter: Char
    get() = if (isFlattened) FLATTENED_SUBLIST_DELIMITER else NESTED_SUBLIST_DELIMITER

  fun asFlattened(): SublistField = copy(isFlattened = true)
}
