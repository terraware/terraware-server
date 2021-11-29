package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import javax.annotation.ManagedBean

@ManagedBean
class AccessionSearchService(private val searchService: SearchService) {
  /**
   * Queries the values of a list of fields on accessions that match a list of filter criteria.
   *
   * This is a thin wrapper around [SearchService.search] to maintain compatibility with the
   * accession search API as [SearchService] is generalized.
   */
  fun search(
      facilityId: FacilityId,
      fields: List<SearchFieldPath>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE,
  ): SearchResults {
    return searchService.search(facilityId, fields, criteria, sortOrder, cursor, limit)
  }
}
