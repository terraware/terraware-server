package com.terraformation.backend.search

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.search.field.SearchField

const val NESTED_SUBLIST_DELIMITER: Char = '.'

/**
 * A partial location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more sublists, and a scalar field.
 * This class is where the root and the sublists live.
 *
 * See [SearchFieldPath] for more details about how prefixes are used.
 */
data class SearchFieldPrefix(
    val root: SearchFieldNamespace,
    val sublists: List<SublistField> = emptyList()
) {
  /**
   * True if this prefix represents a 1:N relationship with its parent prefix. Always false for a
   * root prefix since there is no parent.
   */
  val isMultiValue: Boolean
    get() = sublists.isNotEmpty() && sublists.last().isMultiValue

  /** True if this prefix is at the root of the path, that is, there aren't any sublists. */
  val isRoot: Boolean
    get() = sublists.isEmpty()

  val isFlattened: Boolean
    get() = sublists.isNotEmpty() && sublists.all { it.isFlattened }

  /** Which sublist this prefix refers to, or null if this is a root prefix. */
  val sublistField: SublistField?
    get() = sublists.lastOrNull()

  /**
   * The namespace of this prefix as a whole. This is the namespace of the last sublist, or the root
   * namespace if this is a root prefix and there thus aren't any sublists.
   */
  val namespace: SearchFieldNamespace
    get() = sublistField?.namespace ?: root

  /**
   * Resolves a period-delimited path string relative to this prefix
   *
   * @return The fully-resolved path, or null if one of the elements of [relativePath] wasn't a
   * valid field name.
   */
  fun resolveOrNull(relativePath: String): SearchFieldPath? {
    val nextNestedAndRest = relativePath.split(NESTED_SUBLIST_DELIMITER, limit = 2)
    val nextFlattenedAndRest = relativePath.split('_', limit = 2)

    return if (nextNestedAndRest.size == 1 && nextFlattenedAndRest.size == 1) {
      namespace[nextNestedAndRest[0]]?.let { field ->
        SearchFieldPath(prefix = this, searchField = field)
      }
    } else if (nextNestedAndRest[0].length < nextFlattenedAndRest[0].length) {
      if (sublists.lastOrNull()?.isFlattened == true) {
        throw IllegalArgumentException("Flattened sublists cannot contain nested sublists")
      } else {
        withSublistOrNull(nextNestedAndRest[0], false)?.resolveOrNull(nextNestedAndRest[1])
      }
    } else {
      withSublistOrNull(nextFlattenedAndRest[0], true)?.resolveOrNull(nextFlattenedAndRest[1])
    }
  }

  /**
   * Resolves a period-delimited path string relative to this prefix.
   *
   * @throws IllegalArgumentException One of the elements of the path wasn't a valid field name.
   */
  fun resolve(relativePath: String): SearchFieldPath {
    return resolveOrNull(relativePath)
        ?: throw IllegalArgumentException("Unknown field name $relativePath")
  }

  /**
   * Returns a new [SearchFieldPrefix] with an additional sublist at the end.
   *
   * @param sublistName The name of a sublist field that's defined in this prefix's namespace.
   */
  private fun withSublistOrNull(sublistName: String, flatten: Boolean): SearchFieldPrefix? {
    if (NESTED_SUBLIST_DELIMITER in sublistName || '_' in sublistName) {
      throw IllegalArgumentException("Cannot resolve nested path: $sublistName")
    }

    val sublist = namespace.getSublistOrNull(sublistName) ?: return null
    val possiblyFlattenedSublist = if (flatten) sublist.asFlattened() else sublist
    val newSublists = sublists + possiblyFlattenedSublist

    return SearchFieldPrefix(root = root, sublists = newSublists)
  }

  /** Returns a copy of this prefix with an additional sublist at the end. */
  fun withSublist(sublistName: String): SearchFieldPrefix {
    return withSublistOrNull(sublistName, false)
        ?: throw IllegalArgumentException("Unknown name $sublistName under $this")
  }

  fun asFlattened(): SearchFieldPrefix {
    return if (isFlattened || isRoot) { this } else {
      SearchFieldPrefix(root, sublists.map { it.asFlattened()})
    }
  }

  @JsonValue
  override fun toString() =
      sublists.joinToString("") {
        if (it.isFlattened) "${it.name}_" else it.name + NESTED_SUBLIST_DELIMITER
      }
}

/**
 * The location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more sublists, and a scalar field.
 * The root and the list of sublists live in [SearchFieldPrefix] and the scalar field lives here.
 *
 * A filesystem analogy to help clarify the different parts:
 *
 * ```
 * $ cd /a/b/c
 * $ cat d/e/file
 * ```
 *
 * The root is like your current working directory, `/a/b/c` in the filesystem analogy. All relative
 * paths are evaluated starting from there.
 *
 * The sublists are like a series of subdirectories in a relative path, `d/e` in the analogy. The
 * first one needs to exist in the root (working directory), the second needs to exist in the first,
 * and so on.
 *
 * The scalar field is like the filename of a regular file. In the analogy it is `file`.
 *
 * There can be multiple paths that refer to the same field. To continue with the filesystem
 * analogy, the following would output the same file as the first example:
 *
 * ```
 * $ cd /a/b/c/d/e
 * $ cat file
 * ```
 *
 * The difference is how the two are mapped to search results. In the first case, the search results
 * would be structured like `[{"d":[{"e":[{"file":"contents"}]}]}]` whereas in the second case, the
 * structure would be `[{"file":"contents"}]`. This is very significant to the client because it
 * controls how results are grouped together when there are multiple values for a particular field.
 */
class SearchFieldPath(private val prefix: SearchFieldPrefix, val searchField: SearchField) {
  val sublists = prefix.sublists

  /**
   * True if there are intermediate non-flattened sublists between the root namespace and this
   * field. False if this field is directly under the root namespace.
   */
  val isNested: Boolean
    get() = prefix.sublists.any { !it.isFlattened }

  val isFlattened: Boolean
    get() = prefix.isFlattened

  /**
   * Strips sublists from the beginning of this path's prefix. Returns a copy of this path that's
   * rooted at [otherPrefix]. Only valid if [otherPrefix] is the same as the beginning of this
   * path's prefix; you can't use this to move a field to an unrelated prefix.
   */
  fun relativeTo(otherPrefix: SearchFieldPrefix): SearchFieldPath {
    if (prefix.root != otherPrefix.root) {
      throw IllegalArgumentException("$this is under a different root than $otherPrefix")
    }

    if (otherPrefix.isRoot) {
      return this
    }

    if (otherPrefix.sublists != prefix.sublists.subList(0, otherPrefix.sublists.size)) {
      throw IllegalArgumentException("$otherPrefix is not a prefix of $this")
    }

    if (otherPrefix.isRoot) {
      return this
    }

    return SearchFieldPath(
        SearchFieldPrefix(
            root = otherPrefix.namespace,
            sublists = prefix.sublists.drop(otherPrefix.sublists.size)),
        searchField = searchField)
  }

  fun splitFlattened(): Pair<List<SublistField>, SearchFieldPath> {
    val flattenedFields = sublists.takeWhile { it.isFlattened }
    if (flattenedFields.isEmpty()) {
      return Pair(emptyList(), this)
    }

    return Pair(
        flattenedFields,
        SearchFieldPath(
            SearchFieldPrefix(root = prefix.root, sublists = sublists.drop(flattenedFields.size)),
            searchField = searchField))
  }

  @JsonValue
  override fun toString(): String {
    return "$prefix${searchField.fieldName}"
  }
}
