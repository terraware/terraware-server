package com.terraformation.backend.search.table

import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_COLLECTORS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class AccessionCollectorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ACCESSION_COLLECTORS.ACCESSION_COLLECTOR_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist(
              "accession", ACCESSION_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("name", "Collector name", ACCESSION_COLLECTORS.NAME),
          integerField("position", "Collector list position", ACCESSION_COLLECTORS.POSITION),
      )

  override val inheritsVisibilityFrom: SearchTable = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(ACCESSION_COLLECTORS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
