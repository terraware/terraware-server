package com.terraformation.backend.search

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.search.field.SearchField

/**
 * An element of a search field path prefix.
 *
 * Currently, there is only one kind of element (sublists) but later revisions of this code will
 * introduce an additional type.
 */
interface SearchFieldPrefixElement {
  /**
   * Name of the element as it appears in the field path. This will often, but not always, be the
   * same as the name of the namespace.
   */
  val name: String

  /**
   * Namespace that the path element points to. This determines which field names are valid after a
   * given prefix.
   */
  val namespace: SearchFieldNamespace
}

/**
 * A search field path element representing a sublist (1:N relationship). In search results, a
 * sublist turns into a list of values.
 */
data class SublistPrefixElement(
    override val name: String,
    override val namespace: SearchFieldNamespace
) : SearchFieldPrefixElement

/**
 * A partial location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more path elements, and a scalar
 * field. This class is where the root and the path elements live.
 *
 * See [SearchFieldPath] for more details about how prefixes work.
 */
data class SearchFieldPrefix(
    val root: SearchFieldNamespace,
    val elements: List<SearchFieldPrefixElement> = emptyList()
) {
  /**
   * True if this prefix is at the root of the path, that is, there aren't any additional path
   * elements.
   */
  val isRoot: Boolean
    get() = elements.isEmpty()

  /**
   * The namespace of this prefix as a whole. This is the namespace of the last path element, or the
   * root namespace if this is a root prefix and there thus aren't any elements.
   */
  val namespace: SearchFieldNamespace
    get() = elements.lastOrNull()?.namespace ?: root

  /**
   * Resolves a period-delimited path string relative to this prefix. This method will create new
   * [SearchFieldPrefix] objects if the path has multiple elements.
   *
   * Returns null if one of the elements of the path wasn't a valid field name.
   */
  fun resolveOrNull(relativePath: String): SearchFieldPath? {
    val nextAndRest = relativePath.split('.', limit = 2)

    return if (nextAndRest.size == 1) {
      namespace[nextAndRest[0]]?.let { field ->
        SearchFieldPath(prefix = this, searchField = field)
      }
    } else {
      withSublistOrNull(nextAndRest[0])?.resolveOrNull(nextAndRest[1])
    }
  }

  /**
   * Resolves a period-delimited path string relative to this prefix. This method will create new
   * [SearchFieldPrefix] objects if the path has multiple elements.
   *
   * @throws IllegalArgumentException One of the elements of the path wasn't a valid field name.
   */
  fun resolve(relativePath: String): SearchFieldPath {
    return resolveOrNull(relativePath)
        ?: throw IllegalArgumentException("Unknown field name $relativePath")
  }

  private fun withSublistOrNull(sublistName: String): SearchFieldPrefix? {
    if ('.' in sublistName) {
      throw IllegalArgumentException("Cannot resolve multiple path elements: $sublistName")
    }

    val sublist =
        namespace.sublists[sublistName]?.let { SublistPrefixElement(sublistName, it) }
            ?: return null

    val newContainers = elements.toMutableList()
    newContainers.add(sublist)
    return SearchFieldPrefix(root = root, elements = newContainers)
  }

  /** Returns a copy of this prefix with an additional sublist path element at the end. */
  fun withSublist(sublistName: String): SearchFieldPrefix {
    return withSublistOrNull(sublistName)
        ?: throw IllegalArgumentException("Unknown name $sublistName under $this")
  }

  @JsonValue override fun toString() = elements.joinToString(".") { it.name }
}

/**
 * The location of a search field in the application's data hierarchy.
 *
 * The full path of a search field consists of a root, zero or more path elements, and a scalar
 * field. The root and the path elements live in [SearchFieldPrefix] and the scalar field lives
 * here.
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
 * The path elements are like a series of subdirectories in a relative path, `d/e` in the analogy.
 * The first one needs to exist in the root (working directory), the second needs to exist in the
 * first, and so on.
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
  val containers = prefix.elements

  /**
   * True if there are intermediate path elements between the root namespace and this field. False
   * if this field is directly under the root namespace.
   */
  val isNested: Boolean
    get() = prefix.elements.isNotEmpty()

  /**
   * Strips elements from the beginning of this path's prefix. Returns a copy of this path that's
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

    if (otherPrefix.elements != prefix.elements.subList(0, otherPrefix.elements.size)) {
      throw IllegalArgumentException("$otherPrefix is not a prefix of $this")
    }

    if (otherPrefix.isRoot) {
      return this
    }

    return SearchFieldPath(
        SearchFieldPrefix(
            root = otherPrefix.namespace,
            elements = prefix.elements.drop(otherPrefix.elements.size)),
        searchField = searchField)
  }

  @JsonValue
  override fun toString(): String {
    val prefix = "$prefix"
    return if (prefix.isEmpty()) searchField.fieldName else "$prefix.${searchField.fieldName}"
  }
}
