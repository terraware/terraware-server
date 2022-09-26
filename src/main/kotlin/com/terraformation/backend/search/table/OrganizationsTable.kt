package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class OrganizationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countries.asSingleValueSublist(
              "country", ORGANIZATIONS.COUNTRY_CODE.eq(COUNTRIES.CODE), isRequired = false),
          countrySubdivisions.asSingleValueSublist(
              "countrySubdivision",
              ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE.eq(COUNTRY_SUBDIVISIONS.CODE),
              isRequired = false),
          facilities.asMultiValueSublist(
              "facilities", ORGANIZATIONS.ID.eq(FACILITIES.ORGANIZATION_ID)),
          organizationUsers.asMultiValueSublist(
              "members", ORGANIZATIONS.ID.eq(ORGANIZATION_USERS.ORGANIZATION_ID)),
          species.asMultiValueSublist("species", ORGANIZATIONS.ID.eq(SPECIES.ORGANIZATION_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("countryCode", "Organization country code", ORGANIZATIONS.COUNTRY_CODE),
          textField(
              "countrySubdivisionCode",
              "Organization country subdivision code",
              ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE),
          timestampField(
              "createdTime",
              "Organization created time",
              ORGANIZATIONS.CREATED_TIME,
              nullable = false),
          idWrapperField("id", "Organization ID", ORGANIZATIONS.ID) { OrganizationId(it) },
          textField("name", "Organization name", ORGANIZATIONS.NAME, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    return ORGANIZATIONS.ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> ORGANIZATIONS.ID.eq(scope.organizationId)
      is FacilityIdScope ->
          ORGANIZATIONS.ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
