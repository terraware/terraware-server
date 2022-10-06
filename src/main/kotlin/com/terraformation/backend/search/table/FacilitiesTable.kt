package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
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
          batches.asMultiValueSublist("batches", FACILITIES.ID.eq(BATCH_SUMMARIES.FACILITY_ID)),
          organizations.asSingleValueSublist(
              "organization", FACILITIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          storageLocations.asMultiValueSublist(
              "storageLocations", FACILITIES.ID.eq(STORAGE_LOCATIONS.FACILITY_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField(
              "connectionState",
              "Facility connection state",
              FACILITIES.CONNECTION_STATE_ID,
              nullable = false),
          timestampField(
              "createdTime", "Facility created time", FACILITIES.CREATED_TIME, nullable = false),
          textField("description", "Facility description", FACILITIES.DESCRIPTION),
          idWrapperField("id", "Facility ID", FACILITIES.ID) { FacilityId(it) },
          textField("name", "Facility name", FACILITIES.NAME, nullable = false),
          enumField("type", "Facility type", FACILITIES.TYPE_ID, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return FACILITIES.ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> FACILITIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope -> FACILITIES.ID.eq(scope.facilityId)
    }
  }
}
