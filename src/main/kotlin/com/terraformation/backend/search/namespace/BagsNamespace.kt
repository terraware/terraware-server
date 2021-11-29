package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables

class BagsNamespace(searchTables: SearchTables, accessionsNamespace: AccessionsNamespace) :
    SearchFieldNamespace() {
  override val sublists: List<SublistField> =
      listOf(
          accessionsNamespace.asSingleValueSublist(
              "accession", BAGS.ACCESSION_ID.eq(ACCESSIONS.ID)))

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            bags.textField("number", "Bag number", BAGS.BAG_NUMBER),
        )
      }
}
