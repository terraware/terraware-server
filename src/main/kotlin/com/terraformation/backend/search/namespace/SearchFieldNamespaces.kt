package com.terraformation.backend.search.namespace

import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.seedbank.search.SearchTables
import javax.annotation.ManagedBean

/**
 * Manages the hierarchy of [SearchFieldNamespace]s.
 *
 * Namespaces that are directly referenced by the application are exposed as properties here.
 */
@ManagedBean
class SearchFieldNamespaces(val searchTables: SearchTables) {
  val accessions = AccessionsNamespace(searchTables)
}
