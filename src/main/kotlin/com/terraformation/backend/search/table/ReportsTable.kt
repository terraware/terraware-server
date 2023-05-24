package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ReportsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = REPORTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization", REPORTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          users.asSingleValueSublist(
              "lockedBy", REPORTS.LOCKED_BY.eq(USERS.ID), isRequired = false),
          users.asSingleValueSublist(
              "submittedBy", REPORTS.SUBMITTED_BY.eq(USERS.ID), isRequired = false),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", REPORTS.ID) { ReportId(it) },
          integerField("quarter", REPORTS.QUARTER, nullable = false),
          enumField("status", REPORTS.STATUS_ID, nullable = false),
          integerField("year", REPORTS.YEAR, nullable = false),
      )

  override fun conditionForVisibility(): Condition {
    val user = currentUser()
    val organizationIds = user.organizationRoles.keys.filter { user.canListReports(it) }

    return if (organizationIds.isNotEmpty()) {
      REPORTS.ORGANIZATION_ID.`in`(organizationIds)
    } else {
      DSL.falseCondition()
    }
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return REPORTS.ORGANIZATION_ID.eq(organizationId)
  }
}
