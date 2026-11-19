package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_COMMON_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_COMMON_INDICATORS
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import com.terraformation.backend.search.field.column
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

class AcceleratorReportCommonIndicatorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = REPORT_COMMON_INDICATORS.REPORT_COMMON_INDICATOR_ID

  private val reportsForSettings = REPORTS.`as`("reports_for_indicator_settings")
  private val settings = PROJECT_COMMON_INDICATORS.`as`("project_common_indicators_for_search")

  override val fromTable
    get() =
        DSL.select(
                REPORT_COMMON_INDICATORS.asterisk(),
                settings.ACTIVE,
                settings.IS_PUBLISHABLE,
                settings.REF_ID,
            )
            .from(REPORT_COMMON_INDICATORS)
            .join(reportsForSettings)
            .on(REPORT_COMMON_INDICATORS.REPORT_ID.eq(reportsForSettings.ID))
            .leftJoin(settings)
            .on(settings.PROJECT_ID.eq(reportsForSettings.PROJECT_ID))
            .and(settings.COMMON_INDICATOR_ID.eq(REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID))
            .asTable("report_common_indicators_with_settings")

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          commonIndicators.asSingleValueSublist(
              "indicator",
              REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID,
              COMMON_INDICATORS.ID,
          ),
          acceleratorReports.asSingleValueSublist(
              "report",
              REPORT_COMMON_INDICATORS.REPORT_ID,
              REPORTS.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          booleanField("active", settings.ACTIVE),
          idWrapperField("id", REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID) {
            CommonIndicatorId(it)
          },
          timestampField("modifiedTime", REPORT_COMMON_INDICATORS.MODIFIED_TIME),
          textField("projectsComments", REPORT_COMMON_INDICATORS.PROJECTS_COMMENTS),
          booleanField("publishable", settings.IS_PUBLISHABLE),
          textField("refId", settings.REF_ID),
          enumField("status", REPORT_COMMON_INDICATORS.STATUS_ID),
          uriField(
              "supportingDocumentUrl",
              REPORT_COMMON_INDICATORS.SUPPORTING_DOCUMENT_URL,
          ),
          bigDecimalField("value", REPORT_COMMON_INDICATORS.VALUE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            settings.REF_ID.asc().nullsLast(),
            REPORT_COMMON_INDICATORS.COMMON_INDICATOR_ID,
            REPORT_COMMON_INDICATORS.REPORT_ID,
        )

  // Not inherited from the reports table because these rows are also reachable as sublists of the
  // indicators, which aren't restricted to an organization, and sublists only apply a table's own
  // visibility condition.
  override fun conditionForVisibility(table: Table<*>): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      val organizationIds =
          currentUser().organizationRoles.filter { it.value != Role.Contributor }.keys

      DSL.exists(
          DSL.selectOne()
              .from(REPORTS)
              .join(PROJECTS)
              .on(REPORTS.PROJECT_ID.eq(PROJECTS.ID))
              .where(REPORTS.ID.eq(table.column(REPORT_COMMON_INDICATORS.REPORT_ID)))
              .and(PROJECTS.ORGANIZATION_ID.`in`(organizationIds))
      )
    }
  }
}
