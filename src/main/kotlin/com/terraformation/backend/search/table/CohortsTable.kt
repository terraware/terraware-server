package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class CohortsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COHORTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          projects.asMultiValueSublist("projects", COHORTS.ID.eq(PROJECTS.COHORT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", COHORTS.ID) { CohortId(it) },
          textField("name", COHORTS.NAME),
          enumField("phase", COHORTS.PHASE_ID),
      )

  override fun conditionForVisibility(): Condition {
    return if (currentUser().canReadAllAcceleratorDetails()) {
      DSL.trueCondition()
    } else {
      DSL.exists(
          DSL.selectOne()
              .from(PROJECTS)
              .where(PROJECTS.COHORT_ID.eq(COHORTS.ID))
              .and(PROJECTS.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys))
      )
    }
  }
}
