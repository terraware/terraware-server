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

  /**
   * True if this element represents a one-to-many relationship such that the search results should
   * include a list of values for this path element. False if it represents a one-to-one or
   * many-to-one relationship such that the search results should include a single value for this
   * path element.
   *
   * For example, for the "projects" path element, a reference to the "sites" path element would be
   * one-to-many because there can be many sites for a single project. But from that same "projects"
   * path element, a reference to "organization" would _not_ be one-to-many because each project
   * only has one organization.
   */
  val isMultiValue: Boolean
}

/**
 * A search field path element representing a 1:N relationship with the previous element. In search
 * results, a sublist turns into a list of values.
 */
data class MultiValuePrefixElement(
    override val name: String,
    override val namespace: SearchFieldNamespace
) : SearchFieldPrefixElement {
  override val isMultiValue: Boolean
    get() = true
}

/**
 * A search field path element representing an N:1 relationship with the previous element. In search
 * results, this turns into a child object.
 */
data class SingleValuePrefixElement(
    override val name: String,
    override val namespace: SearchFieldNamespace
) : SearchFieldPrefixElement {
  override val isMultiValue: Boolean
    get() = false
}

/**
 * A partial location of a search field in the application's data hierarchy. The prefix specifies
 * the starting point (root) of a relative path, and zero or more path elements underneath the root.
 */
data class SearchFieldPrefix(
    val root: SearchFieldNamespace,
    val elements: List<SearchFieldPrefixElement> = emptyList()
) {
  /**
   * True if this prefix represents a 1:N relationship with its parent prefix. Always false for a
   * root prefix since there is no parent.
   */
  val isMultiValue: Boolean
    get() = elements.isNotEmpty() && elements.last().isMultiValue

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
      withElementOrNull(nextAndRest[0])?.resolveOrNull(nextAndRest[1])
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

  private fun withElementOrNull(elementName: String): SearchFieldPrefix? {
    if ('.' in elementName) {
      throw IllegalArgumentException("Cannot resolve multiple path elements: $elementName")
    }

    val element =
        namespace.multiValueSublists[elementName]?.let { MultiValuePrefixElement(elementName, it) }
            ?: namespace.singleValueSublists[elementName]?.let {
              SingleValuePrefixElement(elementName, it)
            }
                ?: return null

    val newElements = elements.toMutableList()
    newElements.add(element)
    return SearchFieldPrefix(root = root, elements = newElements)
  }

  /** Returns a copy of this prefix with an additional sublist path element at the end. */
  fun withSublist(sublistName: String): SearchFieldPrefix {
    return withElementOrNull(sublistName)
        ?: throw IllegalArgumentException("Unknown name $sublistName under $this")
  }

  @JsonValue override fun toString() = elements.joinToString(".") { it.name }
}

/** The location of a search field in the application's data hierarchy. */
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
