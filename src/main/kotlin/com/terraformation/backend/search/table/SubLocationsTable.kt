package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class SubLocationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SUB_LOCATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist(
              "accessions",
              SUB_LOCATIONS.ID.eq(ACCESSIONS.SUB_LOCATION_ID),
          ),
          facilities.asSingleValueSublist("facility", SUB_LOCATIONS.FACILITY_ID.eq(FACILITIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", SUB_LOCATIONS.ID) { SubLocationId(it) },
          textField("name", SUB_LOCATIONS.NAME),
      )

  override fun conditionForVisibility(): Condition {
    return SUB_LOCATIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }
}
