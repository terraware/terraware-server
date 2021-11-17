package com.terraformation.backend.search

import com.terraformation.backend.search.field.SearchField

/**
 * Defines which search fields exist at a particular point in the application's hierarchical data
 * model.
 *
 * The search API allows clients to navigate data in the form of a tree-structured hierarchy of
 * fields that starts at "organizations". For example, accession data is tied to facilities. So when
 * you search for accessions, you are actually asking for a subset of an organization's data. Of
 * that organization's data, you're asking for data associated with a specific project, with one of
 * that's project sites, and one of that site's facilities.
 *
 * We abstract this into a "path" that specifies how to navigate the hierarchy. See
 * [SearchFieldPath] for the implementation details of paths.
 *
 * Given a partial path, the system needs to know what names are valid to add to the path, and
 * whether those names refer to fields with scalar values (numbers, text, etc) or to additional
 * intermediate levels of the hierarchy (e.g., a site or a facility).
 *
 * Each non-leaf node in the hierarchy is associated with a [SearchFieldNamespace]. The namespace is
 * where the search code goes to look up names when it is turning a client-specified field name into
 * a [SearchFieldPath].
 */
abstract class SearchFieldNamespace {
  /** Scalar fields that are valid in this namespace. Subclasses must supply this. */
  abstract val fields: List<SearchField>

  /**
   * Sublists that appear under this namespace. Subclasses must supply this. The key of this map is
   * the name of the sublist as it appears in a field path. The same namespace may appear more than
   * once in this map as long as each one has a different name.
   */
  abstract val sublists: Map<String, SearchFieldNamespace>

  private val fieldsByName: Map<String, SearchField> by lazy { fields.associateBy { it.fieldName } }

  fun getAllFieldNames(prefix: String = ""): Set<String> {
    val myFieldNames = fields.map { prefix + it.fieldName }
    val sublistFieldNames =
        sublists.flatMap { (name, sublist) -> sublist.getAllFieldNames("${prefix}$name.") }

    return (myFieldNames + sublistFieldNames).toSet()
  }

  operator fun get(fieldName: String): SearchField? = fieldsByName[fieldName]
}
