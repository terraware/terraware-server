package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.GERMINATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.seedbank.search.SearchTables

class GerminationsNamespace(
    searchTables: SearchTables,
    germinationTestsNamespace: GerminationTestsNamespace
) : SearchFieldNamespace() {
  override val sublists: List<SublistField> =
      listOf(
          germinationTestsNamespace.asSingleValueSublist(
              "germinationTest", GERMINATIONS.TEST_ID.eq(GERMINATION_TESTS.ID)))

  override val fields: List<SearchField> =
      with(searchTables) {
        listOf(
            germinations.dateField(
                "recordingDate",
                "Recording date of germination test result",
                GERMINATIONS.RECORDING_DATE),
            germinations.integerField(
                "seedsGerminated", "Number of seeds germinated", GERMINATIONS.SEEDS_GERMINATED),
        )
      }
}
