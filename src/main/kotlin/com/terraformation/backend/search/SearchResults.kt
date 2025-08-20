package com.terraformation.backend.search

/** Return value from [SearchService.search]. */
data class SearchResults(
    /**
     * List of results containing the fields specified by the caller. Each element of the list is a
     * map of field name to non-null value. If a result does not have a value for a particular
     * field, it is omitted from the map.
     *
     * Each value is either `String` or `List<Map<String, Any>>`.
     */
    val results: List<Map<String, Any>>,

    /**
     * Cursor that can be passed to [SearchService.search] to retrieve additional results. If
     * [results] contains the full set of results, this will be null.
     */
    val cursor: String? = null,
) {
  /**
   * Turns nested field values into CRLF-delimited strings suitable for exporting to a CSV file.
   *
   * For example, if the client searched for fields `foo.bar` and `foo.oof` and the search results
   * are
   *
   * ```
   * [
   *   {
   *     "foo": [
   *       { "bar": "bar 1", "oof": "oof 1" },
   *       { "bar": "bar 2"  }
   *     ]
   *   }
   * ]
   * ```
   *
   * this function will return
   *
   * ```
   * [
   *   {
   *     "foo.bar": "bar 1\r\nbar 2",
   *     "foo.oof": "oof 1"
   *   }
   * ]
   * ```
   */
  fun flattenForCsv(): SearchResults {
    val flattenedResults = results.map { flatten(it) }
    return SearchResults(flattenedResults, cursor)
  }

  /** Flattens the values of a single search result. */
  private fun flatten(result: Map<*, *>): Map<String, String> {
    return expandNestedFields(result)
        .groupBy { it.first }
        .mapValues { (_, pairs) -> pairs.joinToString("\r\n") { it.second } }
  }

  /** Converts nested search results into a flat list of pairs of field names and values. */
  private fun expandNestedFields(
      result: Map<*, *>,
      prefix: String = "",
  ): List<Pair<String, String>> {
    return result.entries.flatMap { (key, value) ->
      when (value) {
        is String -> listOf(prefix + key to value)
        is Map<*, *> -> expandNestedFields(value, "$prefix$key.")
        is List<*> -> value.flatMap { expandNestedFields(it as Map<*, *>, "$prefix$key.") }
        null -> emptyList()
        else ->
            throw IllegalArgumentException("Unexpected value of type ${value.javaClass} at $prefix")
      }
    }
  }
}
