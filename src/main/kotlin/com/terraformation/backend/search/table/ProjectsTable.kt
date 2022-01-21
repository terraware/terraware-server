package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class ProjectsTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PROJECTS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization", PROJECTS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          sites.asMultiValueSublist("sites", PROJECTS.ID.eq(SITES.PROJECT_ID)),
          projectTypeSelections.asMultiValueSublist(
              "types", PROJECTS.ID.eq(PROJECT_TYPE_SELECTIONS.PROJECT_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField(
              "createdTime", "Project created time", PROJECTS.CREATED_TIME, nullable = false),
          textField("description", "Project description", PROJECTS.DESCRIPTION),
          idWrapperField("id", "Project ID", PROJECTS.ID) { ProjectId(it) },
          textField("name", "Project name", PROJECTS.NAME, nullable = false),
          booleanField("perUser", "Project is per-user", PROJECTS.PER_USER, nullable = false),
          dateField("startDate", "Project start date", PROJECTS.START_DATE),
          enumField("status", "Project status", PROJECTS.STATUS_ID),
      )

  override fun conditionForPermissions(): Condition {
    return PROJECTS.ID.`in`(currentUser().projectRoles.keys)
  }
}
