package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class FacilitiesTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = FACILITIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          accessions.asMultiValueSublist("accessions", FACILITIES.ID.eq(ACCESSIONS.FACILITY_ID)),
          sites.asSingleValueSublist("site", FACILITIES.SITE_ID.eq(SITES.ID)),
          storageLocations.asMultiValueSublist(
              "storageLocations", FACILITIES.ID.eq(STORAGE_LOCATIONS.FACILITY_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField(
              "createdTime", "Facility created time", FACILITIES.CREATED_TIME, nullable = false),
          textField("description", "Facility description", FACILITIES.DESCRIPTION),
          idWrapperField("id", "Facility ID", FACILITIES.ID) { FacilityId(it) },
          textField("name", "Facility name", FACILITIES.NAME, nullable = false),
          enumField("type", "Facility type", FACILITIES.TYPE_ID, nullable = false),
      )

  override fun conditionForPermissions(): Condition {
    return FACILITIES.ID.`in`(currentUser().facilityRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition? {
    return when (scope) {
      is OrganizationIdScope ->
          FACILITIES.sites().projects().ORGANIZATION_ID.eq(scope.organizationId)
      else -> null
    }
  }
}
