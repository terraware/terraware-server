package com.terraformation.seedbank.search

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotEmpty

enum class SearchFilterType {
  Exact,
  Fuzzy,
  Range
}

data class SearchFilter(
    val field: SearchField<*>,
    @ArraySchema(
        schema = Schema(nullable = true),
        arraySchema =
            Schema(
                minLength = 1,
                description =
                    "List of values to match. For exact and fuzzy searches, a list of at least " +
                        "one value to search for; the list may include null to match accessions " +
                        "where the field does not have a value. For range searches, the list " +
                        "must contain exactly two values, the minimum and maximum."))
    @NotEmpty
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact
) {
  fun toFieldConditions() = field.getConditions(this)
}
