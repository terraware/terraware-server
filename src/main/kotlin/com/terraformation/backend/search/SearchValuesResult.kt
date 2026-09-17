package com.terraformation.backend.search

data class SearchValuesResult(
    /** The value of the requested field. */
    val value: String?,
    /** Values of the requested sort-order fields. */
    val sortValues: List<String?> = emptyList(),
)
