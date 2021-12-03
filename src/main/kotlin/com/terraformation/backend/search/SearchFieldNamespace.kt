package com.terraformation.backend.search

import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition

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

  /** Sublist fields that are valid in this namespace. Subclasses must supply this. */
  abstract val sublists: List<SublistField>

  /**
   * The main search table for this namespace. By default, this is the table that contains the first
   * scalar field in the namespace. Subclasses can override this if the first scalar field happens
   * to be in a reference table.
   */
  open val searchTable: SearchTable
    get() = fields.first().table

  private val fieldsByName: Map<String, SearchField> by lazy { fields.associateBy { it.fieldName } }
  private val sublistsByName: Map<String, SublistField> by lazy { sublists.associateBy { it.name } }

  fun getAllFieldNames(prefix: String = ""): Set<String> {
    val myFieldNames = fields.map { prefix + it.fieldName }
    val sublistFieldNames =
        sublistsByName.filterValues { it.isMultiValue }.flatMap { (name, sublist) ->
          sublist.namespace.getAllFieldNames("${prefix}$name.")
        }

    return (myFieldNames + sublistFieldNames).toSet()
  }

  operator fun get(fieldName: String): SearchField? = fieldsByName[fieldName]

  fun getSublistOrNull(sublistName: String): SublistField? = sublistsByName[sublistName]

  fun aliasField(fieldName: String, targetName: String): AliasField {
    val targetPath = SearchFieldPrefix(this).resolve(targetName)
    return AliasField(fieldName, targetPath)
  }

  /**
   * Returns a [SublistField] pointing to this namespace for use in cases where there can be
   * multiple values. In other words, returns a [SublistField] that defines a 1:N relationship
   * between another namespace and this one. For example, `facilities` is a multi-value sublist of
   * `sites` because each site can have multiple facilities.
   */
  fun asMultiValueSublist(name: String, conditionForMultiset: Condition): SublistField {
    return SublistField(
        name = name,
        namespace = this,
        isMultiValue = true,
        conditionForMultiset = conditionForMultiset)
  }

  /**
   * Returns a [SublistField] pointing to this namespace for use in cases where there is only a
   * single value. In other words, returns a [SublistField] that defines a 1:1 or N:1 relationship
   * between another namespace and this one. For example, `site` is a single-value sublist of
   * `facilities` because each facility is only associated with one site.
   */
  fun asSingleValueSublist(name: String, conditionForMultiset: Condition): SublistField {
    return SublistField(
        name = name,
        namespace = this,
        isMultiValue = false,
        conditionForMultiset = conditionForMultiset)
  }
}
