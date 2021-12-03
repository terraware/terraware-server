package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.FAMILIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class FamiliesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(namespaces.searchTables.families) {
        listOf(
            textField("name", "Family name", FAMILIES.NAME),
        )
      }
}
