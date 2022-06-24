package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class BagsTable(private val tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BAGS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asSingleValueSublist("accession", BAGS.ACCESSION_ID.eq(ACCESSIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("number", "Bag number", BAGS.BAG_NUMBER),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.accessions

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(ACCESSIONS).on(BAGS.ACCESSION_ID.eq(ACCESSIONS.ID))
  }
}
