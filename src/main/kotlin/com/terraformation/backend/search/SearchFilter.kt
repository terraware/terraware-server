package com.terraformation.backend.search

import com.terraformation.backend.search.field.AliasField
import org.jooq.Condition
import org.jooq.impl.DSL

enum class SearchFilterType {
  Exact,
  Fuzzy,
  Range
}

interface SearchNode {
  fun toCondition(): Condition
  fun referencedTables(): Set<SearchTable>
  fun referencedSublists(): Set<SublistField>
}

class OrNode(private val children: List<SearchNode>) : SearchNode {
  override fun toCondition(): Condition {
    val conditions = children.map { it.toCondition() }
    return if (conditions.size == 1) conditions[0] else DSL.or(conditions)
  }

  override fun referencedTables(): Set<SearchTable> {
    return children.flatMap { it.referencedTables() }.toSet()
  }

  override fun referencedSublists(): Set<SublistField> {
    return children.flatMap { it.referencedSublists() }.toSet()
  }
}

class AndNode(private val children: List<SearchNode>) : SearchNode {
  override fun toCondition(): Condition {
    val conditions = children.map { it.toCondition() }
    return if (conditions.size == 1) conditions[0] else DSL.and(conditions)
  }

  override fun referencedTables(): Set<SearchTable> {
    return children.flatMap { it.referencedTables() }.toSet()
  }

  override fun referencedSublists(): Set<SublistField> {
    return children.flatMap { it.referencedSublists() }.toSet()
  }
}

class NotNode(val child: SearchNode) : SearchNode {
  override fun toCondition(): Condition {
    return DSL.not(child.toCondition())
  }

  override fun referencedTables(): Set<SearchTable> {
    return child.referencedTables()
  }

  override fun referencedSublists(): Set<SublistField> {
    return child.referencedSublists()
  }
}

class FieldNode(
    val field: SearchFieldPath,
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact
) : SearchNode {
  override fun toCondition(): Condition {
    val conditions = field.searchField.getConditions(this)
    return if (conditions.size == 1) conditions[0] else DSL.and(conditions)
  }

  override fun referencedTables(): Set<SearchTable> {
    return setOf(field.searchField.table)
  }

  override fun referencedSublists(): Set<SublistField> {
    return if (field.searchField is AliasField) field.searchField.targetPath.sublists.toSet()
    else field.sublists.toSet()
  }
}

class NoConditionNode : SearchNode {
  override fun toCondition(): Condition {
    return DSL.noCondition()
  }

  override fun referencedTables(): Set<SearchTable> {
    return emptySet()
  }

  override fun referencedSublists(): Set<SublistField> {
    return emptySet()
  }
}
