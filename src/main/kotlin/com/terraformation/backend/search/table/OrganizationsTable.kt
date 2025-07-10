package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.nursery.tables.references.BATCHES
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
import org.jooq.impl.DSL

class OrganizationsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ORGANIZATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          batches.asMultiValueSublist("batches", ORGANIZATIONS.ID.eq(BATCHES.ORGANIZATION_ID)),
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
          organizationInternalTags.asMultiValueSublist(
              "internalTags", ORGANIZATIONS.ID.eq(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)),
          inventories.asMultiValueSublist(
              "inventories", ORGANIZATIONS.ID.eq(INVENTORIES.ORGANIZATION_ID)),
          organizationUsers.asMultiValueSublist(
              "members", ORGANIZATIONS.ID.eq(ORGANIZATION_USERS.ORGANIZATION_ID)),
          nurseryWithdrawals.asMultiValueSublist(
              "nurseryWithdrawals", ORGANIZATIONS.ID.eq(WITHDRAWAL_SUMMARIES.ORGANIZATION_ID)),
          plantingSites.asMultiValueSublist(
              "plantingSites", ORGANIZATIONS.ID.eq(PLANTING_SITE_SUMMARIES.ORGANIZATION_ID)),
          projects.asMultiValueSublist("projects", ORGANIZATIONS.ID.eq(PROJECTS.ORGANIZATION_ID)),
          reports.asMultiValueSublist(
              "reports", ORGANIZATIONS.ID.eq(SEED_FUND_REPORTS.ORGANIZATION_ID)),
          species.asMultiValueSublist("species", ORGANIZATIONS.ID.eq(SPECIES.ORGANIZATION_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("countryCode", ORGANIZATIONS.COUNTRY_CODE),
          textField("countrySubdivisionCode", ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE),
          timestampField("createdTime", ORGANIZATIONS.CREATED_TIME),
          idWrapperField("id", ORGANIZATIONS.ID) { OrganizationId(it) },
          textField("name", ORGANIZATIONS.NAME),
          zoneIdField("timeZone", ORGANIZATIONS.TIME_ZONE),
      )

  override fun conditionForVisibility(): Condition {
    val acceleratorCondition =
        if (currentUser().canReadAllAcceleratorDetails()) {
          DSL.exists(
              DSL.selectOne()
                  .from(ORGANIZATION_INTERNAL_TAGS)
                  .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                  .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(InternalTagIds.Accelerator)))
        } else {
          null
        }

    return DSL.or(
        listOfNotNull(
            ORGANIZATIONS.ID.`in`(currentUser().organizationRoles.keys), acceleratorCondition))
  }
}
