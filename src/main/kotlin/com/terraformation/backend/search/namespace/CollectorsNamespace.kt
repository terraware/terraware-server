package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SearchTables
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class CollectorsNamespace(searchTables: SearchTables) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            collectors.textField("name", "Collector name", COLLECTORS.NAME),
            collectors.textField("notes", "Collector notes", COLLECTORS.NOTES),
        )
      }
}
