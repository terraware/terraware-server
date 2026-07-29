package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATORS
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class AcceleratorReportsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = REPORTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          acceleratorReportAutoCalculatedIndicators.asMultiValueSublist(
              "autoCalculatedIndicators",
              REPORTS.ID.eq(REPORT_AUTO_CALCULATED_INDICATORS.REPORT_ID),
          ),
          acceleratorReportCommonIndicators.asMultiValueSublist(
              "commonIndicators",
              REPORTS.ID.eq(REPORT_COMMON_INDICATORS.REPORT_ID),
          ),
          projects.asSingleValueSublist("project", REPORTS.PROJECT_ID.eq(PROJECTS.ID)),
          acceleratorReportProjectIndicators.asMultiValueSublist(
              "projectIndicators",
              REPORTS.ID.eq(REPORT_PROJECT_INDICATORS.REPORT_ID),
          ),
          users.asSingleValueSublist(
              "submittedBy",
              REPORTS.SUBMITTED_BY.eq(USERS.ID),
              isRequired = false,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("additionalComments", REPORTS.ADDITIONAL_COMMENTS),
          idWrapperField("createdBy", REPORTS.CREATED_BY) { UserId(it) },
          timestampField("createdTime", REPORTS.CREATED_TIME),
          dateField("endDate", REPORTS.END_DATE),
          textField("feedback", REPORTS.FEEDBACK),
          textField("financialSummaries", REPORTS.FINANCIAL_SUMMARIES),
          textField("highlights", REPORTS.HIGHLIGHTS),
          idWrapperField("id", REPORTS.ID) { ReportId(it) },
          idWrapperField("modifiedBy", REPORTS.MODIFIED_BY) { UserId(it) },
          timestampField("modifiedTime", REPORTS.MODIFIED_TIME),
          enumField("quarter", REPORTS.REPORT_QUARTER_ID),
          dateField("startDate", REPORTS.START_DATE),
          enumField("status", REPORTS.STATUS_ID),
          timestampField("submittedTime", REPORTS.SUBMITTED_TIME),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(REPORTS.START_DATE, REPORTS.ID)

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      val organizationIds =
          currentUser().organizationRoles.filter { it.value != Role.Contributor }.keys

      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(REPORTS.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(organizationIds))
      )
    }
  }
}
