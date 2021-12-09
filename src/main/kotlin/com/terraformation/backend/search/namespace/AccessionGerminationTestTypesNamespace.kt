package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class AccessionGerminationTestTypesNamespace(namespaces: SearchFieldNamespaces) :
    SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(namespaces.searchTables.accessionGerminationTestTypes) {
        listOf(
            enumField(
                "type",
                "Viability test type (accession)",
                ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID),
        )
      }
}
