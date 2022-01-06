package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class CollectorsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {

  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asMultiValueSublist(
              "accessions", COLLECTORS.ID.eq(ACCESSIONS.PRIMARY_COLLECTOR_ID)),
          facilities.asSingleValueSublist("facility", COLLECTORS.FACILITY_ID.eq(FACILITIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.collectors) {
        listOf(
            textField("name", "Collector name", COLLECTORS.NAME),
            textField("notes", "Collector notes", COLLECTORS.NOTES),
        )
      }
}
