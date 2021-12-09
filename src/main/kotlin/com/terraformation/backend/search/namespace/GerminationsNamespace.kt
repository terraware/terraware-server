package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class GerminationsNamespace(
    namespaces: SearchFieldNamespaces,
) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          germinationTests.asSingleValueSublist(
              "germinationTest", GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID)))
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.germinations) {
        listOf(
            dateField(
                "recordingDate",
                "Recording date of germination test result",
                GERMINATIONS.RECORDING_DATE),
            integerField(
                "seedsGerminated", "Number of seeds germinated", GERMINATIONS.SEEDS_GERMINATED),
        )
      }
}
