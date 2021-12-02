package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SearchTables
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class FamiliesNamespace(searchTables: SearchTables) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            families.textField("name", "Family name", FAMILIES.NAME),
        )
      }
}
