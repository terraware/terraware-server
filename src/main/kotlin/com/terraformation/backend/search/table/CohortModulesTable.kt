package com.terraformation.backend.search.table

import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.COHORT_MODULES
import com.terraformation.backend.db.accelerator.tables.references.MODULES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class CohortModulesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COHORT_MODULES.COHORT_MODULE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          cohorts.asSingleValueSublist("cohort", COHORT_MODULES.COHORT_ID.eq(COHORTS.ID)),
          modules.asSingleValueSublist("module", COHORT_MODULES.MODULE_ID.eq(MODULES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("title", COHORT_MODULES.TITLE),
          dateField("startDate", COHORT_MODULES.START_DATE),
          dateField("endDate", COHORT_MODULES.END_DATE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.cohorts

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> =
      query.join(COHORTS).on(COHORT_MODULES.COHORT_ID.eq(COHORTS.ID))
}
