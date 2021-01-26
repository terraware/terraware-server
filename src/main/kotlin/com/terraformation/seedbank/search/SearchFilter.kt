package com.terraformation.seedbank.search

import javax.validation.constraints.NotEmpty

enum class SearchFilterType {
  Exact,
  Fuzzy,
  Range
}

data class SearchFilter(
    val field: SearchField<*>,
    @NotEmpty val values: List<String>,
    val type: SearchFilterType = SearchFilterType.Exact
) {
  fun toFieldConditions() = field.getConditions(this)
}
