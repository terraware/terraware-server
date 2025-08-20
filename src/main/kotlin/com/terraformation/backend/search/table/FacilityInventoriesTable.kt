package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.FACILITY_INVENTORIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class FacilityInventoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = FACILITY_INVENTORIES.FACILITY_INVENTORY_ID

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            FACILITY_INVENTORIES.ORGANIZATION_ID,
            FACILITY_INVENTORIES.SPECIES_ID,
            FACILITY_INVENTORIES.FACILITY_ID,
        )

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asMultiValueSublist(
              "batches",
              FACILITY_INVENTORIES.FACILITY_ID.eq(BATCHES.FACILITY_ID)
                  .and(FACILITY_INVENTORIES.SPECIES_ID.eq(BATCHES.SPECIES_ID)),
          ),
          facilities.asSingleValueSublist(
              "facility",
              FACILITY_INVENTORIES.FACILITY_ID.eq(FACILITIES.ID),
          ),
          species.asSingleValueSublist("species", FACILITY_INVENTORIES.SPECIES_ID.eq(SPECIES.ID)),
          organizations.asSingleValueSublist(
              "organization",
              FACILITY_INVENTORIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          longField(
              "activeGrowthQuantity",
              FACILITY_INVENTORIES.ACTIVE_GROWTH_QUANTITY,
              nullable = false,
          ),
          longField(
              "germinatingQuantity",
              FACILITY_INVENTORIES.GERMINATING_QUANTITY,
              nullable = false,
          ),
          longField(
              "hardeningOffQuantity",
              FACILITY_INVENTORIES.HARDENING_OFF_QUANTITY,
              nullable = false,
          ),
          longField(
              "notReadyQuantity",
              FACILITY_INVENTORIES.ACTIVE_GROWTH_QUANTITY,
              nullable = false,
          ),
          longField("readyQuantity", FACILITY_INVENTORIES.READY_QUANTITY, nullable = false),
          longField("totalQuantity", FACILITY_INVENTORIES.TOTAL_QUANTITY, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return FACILITY_INVENTORIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
