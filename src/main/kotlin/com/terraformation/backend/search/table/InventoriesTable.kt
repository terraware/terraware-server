package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.FACILITY_INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.SPECIES_PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField

class InventoriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = INVENTORIES.SPECIES_ID

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(INVENTORIES.ORGANIZATION_ID, INVENTORIES.SPECIES_ID)

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist("species", INVENTORIES.SPECIES_ID.eq(SPECIES.ID)),
          organizations.asSingleValueSublist(
              "organization", INVENTORIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          nurserySpeciesProjects.asMultiValueSublist(
              "projects", INVENTORIES.SPECIES_ID.eq(SPECIES_PROJECTS.SPECIES_ID)),
          facilityInventories.asMultiValueSublist(
              "facilityInventories",
              INVENTORIES.ORGANIZATION_ID.eq(FACILITY_INVENTORIES.ORGANIZATION_ID)
                  .and(INVENTORIES.SPECIES_ID.eq(FACILITY_INVENTORIES.SPECIES_ID))),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          longField("germinatingQuantity", INVENTORIES.GERMINATING_QUANTITY, nullable = false),
          longField("hardeningOffQuantity", INVENTORIES.HARDENING_OFF_QUANTITY, nullable = false),
          longField("notReadyQuantity", INVENTORIES.ACTIVE_GROWTH_QUANTITY, nullable = false),
          longField("readyQuantity", INVENTORIES.READY_QUANTITY, nullable = false),
          longField("totalQuantity", INVENTORIES.TOTAL_QUANTITY, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return INVENTORIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
