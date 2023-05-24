package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class StorageLocationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = STORAGE_LOCATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist(
              "accessions", STORAGE_LOCATIONS.ID.eq(ACCESSIONS.STORAGE_LOCATION_ID)),
          facilities.asSingleValueSublist(
              "facility", STORAGE_LOCATIONS.FACILITY_ID.eq(FACILITIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", STORAGE_LOCATIONS.ID) { StorageLocationId(it) },
          textField("name", STORAGE_LOCATIONS.NAME),
      )

  override fun conditionForVisibility(): Condition {
    return STORAGE_LOCATIONS.FACILITY_ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return STORAGE_LOCATIONS.facilities.ORGANIZATION_ID.eq(organizationId)
  }
}
