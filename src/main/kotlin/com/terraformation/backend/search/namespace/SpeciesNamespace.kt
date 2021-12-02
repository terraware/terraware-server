package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables

class SpeciesNamespace(searchTables: SearchTables) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            species.textField("name", "Species name", SPECIES.NAME),
        )
      }
}
