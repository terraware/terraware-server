package com.terraformation.backend.search

import com.terraformation.backend.seedbank.search.SearchService

/** Return value from [SearchService.search]. */
data class SearchResults(
    /**
     * List of results containing the fields specified by the caller. Each element of the list is a
     * map of field name to non-null value. If an accession does not have a value for a particular
     * field, it is omitted from the map.
     *
     * Each value is either `String` or `List<Map<String, Any>>`.
     */
    val results: List<Map<String, Any>>,

    /**
     * Cursor that can be passed to [SearchService.search] to retrieve additional results. If
     * [results] contains the full set of results, this will be null.
     */
    val cursor: String?
)
