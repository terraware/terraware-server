package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class ParticipantsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PARTICIPANTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          cohorts.asSingleValueSublist(
              "cohort",
              PARTICIPANTS.COHORT_ID.eq(COHORTS.ID),
              isRequired = false,
          ),
          projects.asMultiValueSublist("projects", PARTICIPANTS.ID.eq(PROJECTS.PARTICIPANT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", PARTICIPANTS.ID) { ParticipantId(it) },
          textField("name", PARTICIPANTS.NAME),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }
}
