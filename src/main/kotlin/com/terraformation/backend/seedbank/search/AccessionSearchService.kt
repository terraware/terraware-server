package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.SearchSortField
import com.terraformation.backend.search.table.AccessionsTable
import com.terraformation.backend.search.table.SearchTables
import javax.annotation.ManagedBean

@ManagedBean
class AccessionSearchService(tables: SearchTables, private val searchService: SearchService) {
  private val accessionsTable: AccessionsTable = tables.accessions
  private val rootPrefix = SearchFieldPrefix(root = accessionsTable)
  private val facilityIdField = rootPrefix.resolve("facility.id")
  private val mandatoryFields =
      setOf(
          rootPrefix.resolve("id"),
          rootPrefix.resolve("accessionNumber"),
      )

  /**
   * Queries the values of a list of fields on accessions that match a list of filter criteria.
   *
   * This is a thin wrapper around [SearchService.search] that adds a couple fields to the fields
   * list and filters by facility ID, for compatibility with the accession search API.
   */
  fun search(
      facilityId: FacilityId,
      fields: Collection<SearchFieldPath>,
      criteria: SearchNode,
      sortOrder: List<SearchSortField> = emptyList(),
      cursor: String? = null,
      limit: Int = Int.MAX_VALUE,
  ): SearchResults {
    val fullFieldList = mandatoryFields + fields.toSet()
    val facilityIdCriterion = FieldNode(facilityIdField, listOf("$facilityId"))
    val fullCriteria = AndNode(listOf(criteria, facilityIdCriterion))

    return searchService.search(rootPrefix, fullFieldList, fullCriteria, sortOrder, cursor, limit)
  }
}
