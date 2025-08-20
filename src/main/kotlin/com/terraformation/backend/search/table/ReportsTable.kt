package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
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
    get() = SEED_FUND_REPORTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization",
              SEED_FUND_REPORTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          users.asSingleValueSublist(
              "lockedBy",
              SEED_FUND_REPORTS.LOCKED_BY.eq(USERS.ID),
              isRequired = false,
          ),
          users.asSingleValueSublist(
              "submittedBy",
              SEED_FUND_REPORTS.SUBMITTED_BY.eq(USERS.ID),
              isRequired = false,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", SEED_FUND_REPORTS.ID) { SeedFundReportId(it) },
          integerField("quarter", SEED_FUND_REPORTS.QUARTER),
          enumField("status", SEED_FUND_REPORTS.STATUS_ID),
          integerField("year", SEED_FUND_REPORTS.YEAR),
      )

  override fun conditionForVisibility(): Condition {
    val user = currentUser()
    val organizationIds = user.organizationRoles.keys.filter { user.canListSeedFundReports(it) }

    return if (organizationIds.isNotEmpty()) {
      SEED_FUND_REPORTS.ORGANIZATION_ID.`in`(organizationIds)
    } else {
      DSL.falseCondition()
    }
  }
}
