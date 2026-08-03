package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_PROJECT_INDICATORS
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class AcceleratorReportProjectIndicatorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = REPORT_PROJECT_INDICATORS.REPORT_PROJECT_INDICATOR_ID

  /**
   * Aliased because the unaliased table is used by the "indicator" sublist, and because a reverse
   * sublist from the indicators needs to refer to the outer indicator rather than this one.
   */
  private val indicatorsForOrder = PROJECT_INDICATORS.`as`("project_indicators_for_order")

  override val fromTable
    get() =
        REPORT_PROJECT_INDICATORS.join(indicatorsForOrder)
            .on(REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID.eq(indicatorsForOrder.ID))

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projectIndicators.asSingleValueSublist(
              "indicator",
              REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID.eq(PROJECT_INDICATORS.ID),
          ),
          acceleratorReports.asSingleValueSublist(
              "report",
              REPORT_PROJECT_INDICATORS.REPORT_ID.eq(REPORTS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID) {
            ProjectIndicatorId(it)
          },
          timestampField("modifiedTime", REPORT_PROJECT_INDICATORS.MODIFIED_TIME),
          textField("projectsComments", REPORT_PROJECT_INDICATORS.PROJECTS_COMMENTS),
          enumField("status", REPORT_PROJECT_INDICATORS.STATUS_ID),
          uriField(
              "supportingDocumentUrl",
              REPORT_PROJECT_INDICATORS.SUPPORTING_DOCUMENT_URL,
          ),
          bigDecimalField("value", REPORT_PROJECT_INDICATORS.VALUE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            indicatorsForOrder.REF_ID,
            indicatorsForOrder.ID,
            REPORT_PROJECT_INDICATORS.REPORT_ID,
        )

  // Not inherited from the reports table because these rows are also reachable as sublists of the
  // indicators, which aren't restricted to an organization, and sublists only apply a table's own
  // visibility condition.
  override fun conditionForVisibility(): Condition {
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
              .where(REPORTS.ID.eq(REPORT_PROJECT_INDICATORS.REPORT_ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(organizationIds))
      )
    }
  }
}
