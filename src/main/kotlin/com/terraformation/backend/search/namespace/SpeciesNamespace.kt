package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class SpeciesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {

  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asMultiValueSublist("accessions", SPECIES.ID.eq(ACCESSIONS.SPECIES_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.species) {
        listOf(
            textField("name", "Species name", SPECIES.NAME),
        )
      }
}
