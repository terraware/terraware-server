package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_SECONDARY_COLLECTORS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class AccessionSecondaryCollectorsTable(
    tables: SearchTables,
    fuzzySearchOperators: FuzzySearchOperators
) : SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ACCESSION_SECONDARY_COLLECTORS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("name", "Collector name", ACCESSION_SECONDARY_COLLECTORS.NAME),
      )

  override val inheritsPermissionsFrom: SearchTable = tables.accessions

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(ACCESSION_SECONDARY_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
