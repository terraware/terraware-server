package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_INDICATORS
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

class ProjectIndicatorsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECT_INDICATORS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asSingleValueSublist("project", PROJECT_INDICATORS.PROJECT_ID.eq(PROJECTS.ID)),
          acceleratorReportProjectIndicators.asMultiValueSublist(
              "reportIndicators",
              PROJECT_INDICATORS.ID.eq(REPORT_PROJECT_INDICATORS.PROJECT_INDICATOR_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          booleanField("active", PROJECT_INDICATORS.ACTIVE),
          enumField("category", PROJECT_INDICATORS.CATEGORY_ID),
          enumField("class", PROJECT_INDICATORS.CLASS_ID),
          textField("description", PROJECT_INDICATORS.DESCRIPTION),
          enumField("frequency", PROJECT_INDICATORS.FREQUENCY_ID),
          idWrapperField("id", PROJECT_INDICATORS.ID) { ProjectIndicatorId(it) },
          enumField("level", PROJECT_INDICATORS.LEVEL_ID),
          textField("name", PROJECT_INDICATORS.NAME),
          integerField("precision", PROJECT_INDICATORS.PRECISION),
          booleanField("publishable", PROJECT_INDICATORS.IS_PUBLISHABLE),
          textField("refId", PROJECT_INDICATORS.REF_ID),
          textField("unit", PROJECT_INDICATORS.UNIT),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PROJECT_INDICATORS.REF_ID, PROJECT_INDICATORS.ID)

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      val organizationIds =
          currentUser().organizationRoles.filter { it.value != Role.Contributor }.keys

      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECT_INDICATORS.PROJECT_ID.eq(PROJECTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(organizationIds))
      )
    }
  }
}
