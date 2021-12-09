package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class StorageLocationsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(namespaces.searchTables.storageLocations) {
        listOf(
            textField("name", "Storage location name", STORAGE_LOCATIONS.NAME),
        )
      }
}
