package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.BATCH_SUMMARIES
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class OrganizationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asMultiValueSublist(
              "batches", ORGANIZATIONS.ID.eq(BATCH_SUMMARIES.ORGANIZATION_ID)),
          countries.asSingleValueSublist(
              "country", ORGANIZATIONS.COUNTRY_CODE.eq(COUNTRIES.CODE), isRequired = false),
          countrySubdivisions.asSingleValueSublist(
              "countrySubdivision",
              ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE.eq(COUNTRY_SUBDIVISIONS.CODE),
              isRequired = false),
          draftPlantingSites.asMultiValueSublist(
              "draftPlantingSites", ORGANIZATIONS.ID.eq(DRAFT_PLANTING_SITES.ORGANIZATION_ID)),
          facilities.asMultiValueSublist(
              "facilities", ORGANIZATIONS.ID.eq(FACILITIES.ORGANIZATION_ID)),
          inventories.asMultiValueSublist(
              "inventories", ORGANIZATIONS.ID.eq(INVENTORIES.ORGANIZATION_ID)),
          organizationUsers.asMultiValueSublist(
              "members", ORGANIZATIONS.ID.eq(ORGANIZATION_USERS.ORGANIZATION_ID)),
          nurseryWithdrawals.asMultiValueSublist(
              "nurseryWithdrawals", ORGANIZATIONS.ID.eq(WITHDRAWAL_SUMMARIES.ORGANIZATION_ID)),
          plantingSites.asMultiValueSublist(
              "plantingSites", ORGANIZATIONS.ID.eq(PLANTING_SITE_SUMMARIES.ORGANIZATION_ID)),
          projects.asMultiValueSublist("projects", ORGANIZATIONS.ID.eq(PROJECTS.ORGANIZATION_ID)),
          reports.asMultiValueSublist("reports", ORGANIZATIONS.ID.eq(REPORTS.ORGANIZATION_ID)),
          species.asMultiValueSublist("species", ORGANIZATIONS.ID.eq(SPECIES.ORGANIZATION_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("countryCode", ORGANIZATIONS.COUNTRY_CODE),
          textField("countrySubdivisionCode", ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE),
          timestampField("createdTime", ORGANIZATIONS.CREATED_TIME, nullable = false),
          idWrapperField("id", ORGANIZATIONS.ID) { OrganizationId(it) },
          textField("name", ORGANIZATIONS.NAME, nullable = false),
          zoneIdField("timeZone", ORGANIZATIONS.TIME_ZONE),
      )

  override fun conditionForVisibility(): Condition {
    return ORGANIZATIONS.ID.`in`(currentUser().organizationRoles.keys)
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return ORGANIZATIONS.ID.eq(organizationId)
  }
}
