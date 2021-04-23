package com.terraformation.seedbank.search

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotEmpty

enum class SearchFilterType {
  Exact,
  Fuzzy,
  Range
}

/**
 * A filter criterion to use when searching for accessions.
 *
 * @see SearchService
 */
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
                        "must contain exactly two values, the minimum and maximum; one of the " +
                        "values may be null to search for all values above a minimum or below a " +
                        "maximum."))
    @NotEmpty
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact
) {
  fun toFieldConditions() = field.getConditions(this)
}
