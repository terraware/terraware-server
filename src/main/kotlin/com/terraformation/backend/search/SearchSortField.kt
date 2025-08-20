package com.terraformation.backend.search

import org.jooq.SortOrder

enum class SearchDirection(val sortOrder: SortOrder) {
  Ascending(SortOrder.ASC),
  Descending(SortOrder.DESC),
}

/** Identifies a field to use for sorting search results, as well as which direction to sort. */
data class SearchSortField(
    val field: SearchFieldPath,
    val direction: SearchDirection = SearchDirection.Ascending,
)
