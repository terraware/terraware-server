package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class AccessionGerminationTestTypesTable(
    private val tables: SearchTables,
    fuzzySearchOperators: FuzzySearchOperators
) : SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          enumField(
              "type",
              "Viability test type (accession)",
              ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID),
      )

  override val inheritsPermissionsFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(ACCESSIONS)
        .on(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
