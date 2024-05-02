package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.tables.references.EVENTS
import com.terraformation.backend.db.accelerator.tables.references.EVENT_PROJECTS
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class EventsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = EVENTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          modules.asSingleValueSublist("module", MODULES.ID.eq(EVENTS.MODULE_ID)),
          projects.asMultiValueSublist(
              "projects", eventProjectsCondition, isTraversedForAllFields = false))
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", EVENTS.ID) { EventId(it) },
          enumField("status", EVENTS.EVENT_STATUS_ID, nullable = false),
          enumField("type", EVENTS.EVENT_TYPE_ID, nullable = false),
          timestampField("startTime", EVENTS.START_TIME, nullable = false),
          timestampField("endTime", EVENTS.END_TIME, nullable = false),
          uriField("meetingUrl", EVENTS.MEETING_URL),
          uriField("recordingUrl", EVENTS.RECORDING_URL),
          uriField("slidesUrl", EVENTS.SLIDES_URL),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(EVENT_PROJECTS)
              .join(PROJECTS)
              .on(EVENT_PROJECTS.PROJECT_ID.eq(PROJECTS.ID))
              .where(EVENT_PROJECTS.EVENT_ID.eq(EVENTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)))
    }
  }

  private val eventProjectsCondition =
      DSL.exists(
          DSL.selectOne()
              .from(EVENT_PROJECTS)
              .where(EVENT_PROJECTS.PROJECT_ID.eq(PROJECTS.ID))
              .and(EVENT_PROJECTS.EVENT_ID.eq(EVENTS.ID)))
}
