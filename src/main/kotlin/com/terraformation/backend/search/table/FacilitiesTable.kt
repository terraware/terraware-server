package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.FACILITY_INVENTORY_TOTALS
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class FacilitiesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = FACILITIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist("accessions", FACILITIES.ID.eq(ACCESSIONS.FACILITY_ID)),
          batches.asMultiValueSublist("batches", FACILITIES.ID.eq(BATCHES.FACILITY_ID)),
          facilityInventoryTotals.asMultiValueSublist(
              "facilityInventoryTotals", FACILITIES.ID.eq(FACILITY_INVENTORY_TOTALS.FACILITY_ID)),
          nurseryWithdrawals.asMultiValueSublist(
              "nurseryWithdrawals", FACILITIES.ID.eq(WITHDRAWAL_SUMMARIES.FACILITY_ID)),
          organizations.asSingleValueSublist(
              "organization", FACILITIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          subLocations.asMultiValueSublist(
              "subLocations", FACILITIES.ID.eq(SUB_LOCATIONS.FACILITY_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("buildCompletedDate", FACILITIES.BUILD_COMPLETED_DATE),
          dateField("buildStartedDate", FACILITIES.BUILD_STARTED_DATE),
          integerField("capacity", FACILITIES.CAPACITY),
          enumField("connectionState", FACILITIES.CONNECTION_STATE_ID),
          timestampField("createdTime", FACILITIES.CREATED_TIME),
          textField("description", FACILITIES.DESCRIPTION),
          integerField("facilityNumber", FACILITIES.FACILITY_NUMBER),
          idWrapperField("id", FACILITIES.ID) { FacilityId(it) },
          textField("name", FACILITIES.NAME),
          dateField("operationStartedDate", FACILITIES.OPERATION_STARTED_DATE),
          zoneIdField("timeZone", FACILITIES.TIME_ZONE),
          enumField("type", FACILITIES.TYPE_ID),
      )

  override fun conditionForVisibility(): Condition {
    return FACILITIES.ID.`in`(currentUser().facilityRoles.keys)
  }
}
