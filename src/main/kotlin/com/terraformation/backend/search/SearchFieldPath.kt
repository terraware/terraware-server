package com.terraformation.backend.search

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.search.field.AliasField
import com.terraformation.backend.search.field.SearchField

const val NESTED_SUBLIST_DELIMITER: Char = '.'
const val FLATTENED_SUBLIST_DELIMITER: Char = '_'

/**
 * A partial location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more sublists, and a scalar field.
 * This class is where the root and the sublists live.
 *
 * See [SearchFieldPath] for more details about how prefixes are used.
 */
data class SearchFieldPrefix(
    val root: SearchTable,
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

  /** True if this prefix contains at least one nested sublist. */
  val isNested: Boolean
    get() = sublists.isNotEmpty() && !sublists.first().isFlattened

  /**
   * True if this prefix only contains flattened sublists and there is at least one sublist. False
   * if there are no sublists or if it contains nested sublists, even if there are flattened ones as
   * well.
   */
  val isFlattened: Boolean
    get() {
      // Flattened sublists cannot come before nested ones in a prefix. So if the first sublist
      // is flattened, all the remaining ones must also be.
      return sublists.isNotEmpty() && sublists.first().isFlattened
    }

  /**
   * True if the sublist represented by this prefix is guaranteed to have at least one value if the
   * root has a value. For example, if the root namespace is `sites`, then a prefix with a prefix of
   * `project` and `organization` is required because every site must have a project and every
   * project must have an organization.
   */
  val isRequired: Boolean
    get() = isRoot || sublists.all { it.isRequired }

  /** Which sublist this prefix refers to, or null if this is a root prefix. */
  val sublistField: SublistField?
    get() = sublists.lastOrNull()

  /**
   * The search table of this prefix as a whole. This is the table of the last sublist, or the root
   * table if this is a root prefix and there thus aren't any sublists.
   */
  val searchTable: SearchTable
    get() = sublistField?.searchTable ?: root

  /** Only works with (and is useful for) nested sublists, not flattened */
  fun relativeSublistPrefix(relativePath: String): SearchFieldPrefix? {
    val sublistNames = relativePath.split(NESTED_SUBLIST_DELIMITER)

    var newPrefix = this.copy()
    for (sublistName in sublistNames) {
      val updated = newPrefix.withSublistOrNull(sublistName, false)
      if (updated == null) {
        return null
      }
      newPrefix = updated
    }
    return newPrefix
  }

  /**
   * Resolves a period-delimited path string relative to this prefix
   *
   * @return The fully-resolved path, or null if one of the elements of [relativePath] wasn't a
   *   valid field name.
   */
  private fun resolveOrNull(relativePath: String): SearchFieldPath? {
    val nextNestedAndRest = relativePath.split(NESTED_SUBLIST_DELIMITER, limit = 2)
    val nextFlattenedAndRest = relativePath.split(FLATTENED_SUBLIST_DELIMITER, limit = 2)

    return if (nextNestedAndRest.size == 1 && nextFlattenedAndRest.size == 1) {
      // A plain field name with no sublist of either sort.
      searchTable[nextNestedAndRest[0]]?.let { field ->
        SearchFieldPath(prefix = this, searchField = field)
      }
    } else if (nextNestedAndRest[0].length < nextFlattenedAndRest[0].length) {
      // A nested sublist comes before the first flattened one, or there is no flattened one at all.
      withSublistOrNull(nextNestedAndRest[0], false)?.resolveOrNull(nextNestedAndRest[1])
    } else {
      // The first part of the relative path is a flattened sublist.
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
   * @param sublistName The name of a sublist field that's defined in this prefix's table.
   */
  private fun withSublistOrNull(sublistName: String, flatten: Boolean): SearchFieldPrefix? {
    if (NESTED_SUBLIST_DELIMITER in sublistName || FLATTENED_SUBLIST_DELIMITER in sublistName) {
      throw IllegalArgumentException("Cannot resolve multiple sublists at once: $sublistName")
    }

    val sublist = searchTable.getSublistOrNull(sublistName) ?: return null
    val possiblyFlattenedSublist = if (flatten) sublist.asFlattened() else sublist

    if (isFlattened && !possiblyFlattenedSublist.isFlattened) {
      throw IllegalArgumentException("Flattened sublists cannot contain nested sublists")
    }

    val newSublists = sublists + possiblyFlattenedSublist

    return SearchFieldPrefix(root = root, sublists = newSublists)
  }

  /** Returns a copy of this prefix with an additional sublist at the end. */
  fun withSublist(sublistName: String): SearchFieldPrefix {
    return withSublistOrNull(sublistName, false)
        ?: throw IllegalArgumentException("Unknown name $sublistName under $this")
  }

  @JsonValue override fun toString() = sublists.joinToString("") { it.name + it.delimiter }
}

/**
 * The location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more sublists, and a scalar field.
 * The root and the list of sublists live in [SearchFieldPrefix] and the scalar field lives here.
 *
 * A filesystem analogy to help clarify the different parts:
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
class SearchFieldPath(val prefix: SearchFieldPrefix, val searchField: SearchField) {
  val sublists: List<SublistField>
    get() = prefix.sublists

  /**
   * True if there are nested sublists between the root table and this field. False if this field is
   * directly under the root table or if all the sublists are flattened.
   */
  val isNested: Boolean
    get() = prefix.isNested

  /**
   * True if all the sublists between the root table and this field are flattened, and there is at
   * least one sublist. False if this field is directly under the root table or if there are nested
   * sublists.
   */
  val isFlattened: Boolean
    get() = prefix.isFlattened

  val searchTable: SearchTable
    get() =
        if (searchField is AliasField) {
          searchField.targetPath.searchTable
        } else {
          prefix.searchTable
        }

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

    return SearchFieldPath(
        SearchFieldPrefix(
            root = otherPrefix.searchTable,
            sublists = prefix.sublists.drop(otherPrefix.sublists.size)),
        searchField = searchField)
  }

  @JsonValue
  override fun toString(): String {
    return "$prefix${searchField.fieldName}"
  }
}
