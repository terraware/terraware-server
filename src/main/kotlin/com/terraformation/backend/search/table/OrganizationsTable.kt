package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.references.COUNTRIES
import com.terraformation.backend.db.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class OrganizationsTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
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
          projects.asMultiValueSublist("projects", ORGANIZATIONS.ID.eq(PROJECTS.ORGANIZATION_ID)),
          organizationUsers.asMultiValueSublist(
              "users", ORGANIZATIONS.ID.eq(ORGANIZATION_USERS.ORGANIZATION_ID)),
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

  override fun conditionForPermissions(): Condition {
    return ORGANIZATIONS.ID.`in`(currentUser().organizationRoles.keys)
  }
}
