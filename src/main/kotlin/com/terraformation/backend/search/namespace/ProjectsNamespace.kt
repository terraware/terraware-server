package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class ProjectsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
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
      with(namespaces.searchTables.projects) {
        listOf(
            textField("description", "Project description", PROJECTS.DESCRIPTION),
            idWrapperField("id", "Project ID", PROJECTS.ID) { ProjectId(it) },
            textField("name", "Project name", PROJECTS.NAME, nullable = false),
            dateField("startDate", "Project start date", PROJECTS.START_DATE),
            enumField("status", "Project status", PROJECTS.STATUS_ID),
        )
      }
}
