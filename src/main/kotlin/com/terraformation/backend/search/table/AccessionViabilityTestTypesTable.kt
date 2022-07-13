package com.terraformation.backend.search.table

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_VIABILITY_TEST_TYPES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class AccessionViabilityTestTypesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ACCESSION_VIABILITY_TEST_TYPES.ACCESSION_ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          enumField(
              "type",
              "Viability test type (accession)",
              ACCESSION_VIABILITY_TEST_TYPES.VIABILITY_TEST_TYPE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(ACCESSION_VIABILITY_TEST_TYPES.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
