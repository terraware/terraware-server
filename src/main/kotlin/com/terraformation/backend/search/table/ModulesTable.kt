package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
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
          events.asMultiValueSublist("events", EVENTS.MODULE_ID.eq(MODULES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("additionalResources", MODULES.ADDITIONAL_RESOURCES, nullable = false),
          timestampField("createdTime", MODULES.CREATED_TIME, nullable = false),
          idWrapperField("id", MODULES.ID) { ModuleId(it) },
          textField("liveSessionDescription", MODULES.LIVE_SESSION_DESCRIPTION, nullable = false),
          textField("name", MODULES.NAME),
          textField(
              "oneOnOneSessionDescription",
              MODULES.ONE_ON_ONE_SESSION_DESCRIPTION,
              nullable = false),
          textField("overview", MODULES.OVERVIEW, nullable = false),
          textField("preparationMaterials", MODULES.PREPARATION_MATERIALS, nullable = false),
          textField("workshopDescription", MODULES.WORKSHOP_DESCRIPTION, nullable = false),
      )

  override fun conditionForVisibility(): Condition =
      if (currentUser().canReadAllAcceleratorDetails()) {
        DSL.trueCondition()
      } else {
        DSL.exists(
            DSL.selectOne()
                .from(COHORT_MODULES)
                .join(PARTICIPANTS)
                .on(PARTICIPANTS.COHORT_ID.eq(COHORT_MODULES.COHORT_ID))
                .join(PROJECTS)
                .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
                .where(COHORT_MODULES.MODULE_ID.eq(MODULES.ID))
                .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)))
      }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(MODULES.ID)
}
