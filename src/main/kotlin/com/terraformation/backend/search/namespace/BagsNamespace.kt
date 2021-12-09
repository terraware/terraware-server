package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class BagsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asSingleValueSublist("accession", BAGS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.bags) {
        listOf(
            textField("number", "Bag number", BAGS.BAG_NUMBER),
        )
      }
}
