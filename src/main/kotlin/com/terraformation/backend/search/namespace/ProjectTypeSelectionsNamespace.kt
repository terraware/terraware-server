package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class ProjectTypeSelectionsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      with(namespaces.searchTables.projectTypeSelections) {
        listOf(
            enumField("type", "Project type", PROJECT_TYPE_SELECTIONS.PROJECT_TYPE_ID),
        )
      }
}
