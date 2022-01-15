package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.COLLECTORS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class CollectorsTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COLLECTORS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist(
              "accessions", COLLECTORS.ID.eq(ACCESSIONS.PRIMARY_COLLECTOR_ID)),
          facilities.asSingleValueSublist("facility", COLLECTORS.FACILITY_ID.eq(FACILITIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("name", "Collector name", COLLECTORS.NAME),
          textField("notes", "Collector notes", COLLECTORS.NOTES),
      )

  override fun conditionForPermissions(): Condition {
    return COLLECTORS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }
}
