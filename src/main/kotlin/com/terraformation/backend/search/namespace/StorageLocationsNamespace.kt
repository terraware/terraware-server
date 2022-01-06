package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class StorageLocationsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {

  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asMultiValueSublist(
              "accessions", STORAGE_LOCATIONS.ID.eq(ACCESSIONS.STORAGE_LOCATION_ID)),
          facilities.asSingleValueSublist(
              "facility", STORAGE_LOCATIONS.FACILITY_ID.eq(FACILITIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.storageLocations) {
        listOf(
            enumField("condition", "Storage location condition", STORAGE_LOCATIONS.CONDITION_ID),
            textField("name", "Storage location name", STORAGE_LOCATIONS.NAME),
        )
      }
}
