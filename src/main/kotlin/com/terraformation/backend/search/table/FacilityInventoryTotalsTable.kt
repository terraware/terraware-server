package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.nursery.tables.references.FACILITY_INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.FACILITY_INVENTORY_TOTALS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class FacilityInventoryTotalsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = FACILITY_INVENTORY_TOTALS.FACILITY_ID

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(FACILITY_INVENTORY_TOTALS.ORGANIZATION_ID, FACILITY_INVENTORY_TOTALS.FACILITY_ID)

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          facilities.asSingleValueSublist(
              "facility", FACILITY_INVENTORY_TOTALS.FACILITY_ID.eq(FACILITIES.ID)),
          facilityInventories.asMultiValueSublist(
              "facilityInventories",
              FACILITY_INVENTORY_TOTALS.FACILITY_ID.eq(FACILITY_INVENTORIES.FACILITY_ID)),
          organizations.asSingleValueSublist(
              "organization", FACILITY_INVENTORY_TOTALS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          longField(
              "germinatingQuantity",
              FACILITY_INVENTORY_TOTALS.GERMINATING_QUANTITY,
              nullable = false),
          longField(
              "hardeningOffQuantity",
              FACILITY_INVENTORY_TOTALS.HARDENING_OFF_QUANTITY,
              nullable = false),
          longField(
              "notReadyQuantity", FACILITY_INVENTORY_TOTALS.NOT_READY_QUANTITY, nullable = false),
          longField("readyQuantity", FACILITY_INVENTORY_TOTALS.READY_QUANTITY, nullable = false),
          longField("totalQuantity", FACILITY_INVENTORY_TOTALS.TOTAL_QUANTITY, nullable = false),
          longField("totalSpecies", FACILITY_INVENTORY_TOTALS.TOTAL_SPECIES, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return FACILITY_INVENTORY_TOTALS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
