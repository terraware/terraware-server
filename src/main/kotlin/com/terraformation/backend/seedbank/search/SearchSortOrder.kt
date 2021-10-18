package com.terraformation.backend.seedbank.search

enum class SearchDirection {
  Ascending,
  Descending
}

/** Identifies a field to use for sorting search results, as well as which direction to sort. */
data class SearchSortField(
    val field: SearchField,
    val direction: SearchDirection = SearchDirection.Ascending
)
