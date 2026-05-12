package com.terraformation.backend.search

import com.terraformation.backend.search.field.AliasField
import org.jooq.Condition
import org.jooq.impl.DSL

enum class SearchFilterType {
  Exact,
  Fuzzy,
  Partial,
  PartialOrFuzzy,
  PhraseMatch,
  Range,
}

interface SearchNode {
  fun toCondition(): Condition

  /**
   * Converts this node and all its descendents from partial-or-fuzzy to partial filtering. If this
   * node does not currently contain any partial-or-fuzzy filter criteria, this method _must_ return
   * an object that tests equal to `this` (e.g., by just returning `this`).
   */
  fun toPartialSearch(): SearchNode

  fun referencedSublists(): Set<SublistField>
}

data class OrNode(private val children: List<SearchNode>) : SearchNode {
  override fun toCondition(): Condition {
    val conditions = children.map { it.toCondition() }
    return if (conditions.size == 1) conditions[0] else DSL.or(conditions)
  }

  override fun toPartialSearch(): OrNode {
    val partialChildren = children.map { it.toPartialSearch() }

    return if (partialChildren != children) {
      OrNode(partialChildren)
    } else {
      this
    }
  }

  override fun referencedSublists(): Set<SublistField> {
    return children.flatMap { it.referencedSublists() }.toSet()
  }

  override fun toString(): String {
    return "OrNode(${children.joinToString()})"
  }
}

data class AndNode(private val children: List<SearchNode>) : SearchNode {
  override fun toCondition(): Condition {
    val conditions = children.map { it.toCondition() }
    return if (conditions.size == 1) conditions[0] else DSL.and(conditions)
  }

  override fun toPartialSearch(): AndNode {
    val partialChildren = children.map { it.toPartialSearch() }

    return if (partialChildren != children) {
      AndNode(partialChildren)
    } else {
      this
    }
  }

  override fun referencedSublists(): Set<SublistField> {
    return children.flatMap { it.referencedSublists() }.toSet()
  }

  override fun toString(): String {
    return "AndNode(${children.joinToString()})"
  }
}

data class NotNode(val child: SearchNode) : SearchNode {
  override fun toCondition(): Condition {
    return DSL.not(child.toCondition())
  }

  override fun toPartialSearch(): NotNode {
    val partialChild = child.toPartialSearch()

    return if (partialChild != child) {
      NotNode(partialChild)
    } else {
      this
    }
  }

  override fun referencedSublists(): Set<SublistField> {
    return child.referencedSublists()
  }

  override fun toString(): String {
    return "NotNode($child)"
  }
}

data class FieldNode(
    val field: SearchFieldPath,
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact,
) : SearchNode {
  init {
    if (values.isEmpty()) {
      throw IllegalArgumentException("Search must include at least one value")
    }
  }

  override fun toCondition(): Condition {
    val conditions = field.searchField.getConditions(this)
    return when {
      isFuzzySearchForNull -> DSL.trueCondition()
      conditions.size == 1 -> conditions[0]
      else -> DSL.and(conditions)
    }
  }

  override fun referencedSublists(): Set<SublistField> {
    return when {
      isFuzzySearchForNull -> emptySet()
      field.searchField is AliasField ->
          field.sublists.toSet() + field.searchField.targetPath.sublists.toSet()
      else -> field.sublists.toSet()
    }
  }

  override fun toPartialSearch(): FieldNode {
    return if (type == SearchFilterType.PartialOrFuzzy && values.any { it != null }) {
      FieldNode(field, values.filterNotNull(), SearchFilterType.Partial)
    } else {
      this
    }
  }

  override fun toString(): String {
    return "FieldNode($field $type [${values.joinToString()}])"
  }

  private val isFuzzySearchForNull: Boolean
    get() = type == SearchFilterType.Fuzzy && values.any { it == null }
}

class NoConditionNode : SearchNode {
  override fun toCondition(): Condition {
    return DSL.noCondition()
  }

  override fun toPartialSearch(): NoConditionNode {
    return this
  }

  override fun referencedSublists(): Set<SublistField> {
    return emptySet()
  }

  override fun equals(other: Any?): Boolean {
    return other is NoConditionNode
  }

  override fun hashCode(): Int {
    return javaClass.hashCode()
  }

  override fun toString(): String {
    return "NoConditionNode()"
  }
}
