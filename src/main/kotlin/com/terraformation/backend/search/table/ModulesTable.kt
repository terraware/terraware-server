package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ModulesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = MODULES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          cohortModules.asMultiValueSublist(
              "cohortModules",
              MODULES.ID.eq(COHORT_MODULES.MODULE_ID),
          ),
          deliverables.asMultiValueSublist("deliverables", MODULES.ID.eq(DELIVERABLES.MODULE_ID)),
          events.asMultiValueSublist("events", MODULES.ID.eq(EVENTS.MODULE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("additionalResources", MODULES.ADDITIONAL_RESOURCES),
          idWrapperField("id", MODULES.ID) { ModuleId(it) },
          textField("liveSessionDescription", MODULES.LIVE_SESSION_DESCRIPTION),
          textField("name", MODULES.NAME),
          textField("oneOnOneSessionDescription", MODULES.ONE_ON_ONE_SESSION_DESCRIPTION),
          textField("overview", MODULES.OVERVIEW),
          enumField("phase", MODULES.PHASE_ID),
          textField("preparationMaterials", MODULES.PREPARATION_MATERIALS),
          textField("workshopDescription", MODULES.WORKSHOP_DESCRIPTION),
      )

  override fun conditionForVisibility(): Condition =
      if (currentUser().canReadAllAcceleratorDetails()) {
        DSL.trueCondition()
      } else {
        DSL.exists(
            DSL.selectOne()
                .from(COHORT_MODULES)
                .join(PROJECTS)
                .on(PROJECTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
                .where(COHORT_MODULES.MODULE_ID.eq(MODULES.ID))
                .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
        )
      }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(MODULES.ID)
}
