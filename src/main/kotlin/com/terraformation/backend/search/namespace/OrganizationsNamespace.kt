package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class OrganizationsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          projects.asMultiValueSublist("projects", ORGANIZATIONS.ID.eq(PROJECTS.ORGANIZATION_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.organizations) {
        listOf(
            idWrapperField("id", "Organization ID", ORGANIZATIONS.ID) { OrganizationId(it) },
            textField("name", "Organization name", ORGANIZATIONS.NAME, nullable = false),
        )
      }
}
