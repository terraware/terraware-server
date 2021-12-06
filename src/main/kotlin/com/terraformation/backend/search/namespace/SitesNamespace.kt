package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class SitesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          facilities.asMultiValueSublist("facilities", SITES.ID.eq(FACILITIES.SITE_ID)),
          layers.asMultiValueSublist("layers", SITES.ID.eq(LAYERS.SITE_ID)),
          projects.asSingleValueSublist("project", SITES.PROJECT_ID.eq(PROJECTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.sites) {
        listOf(
            idWrapperField("id", "Site ID", SITES.ID) { SiteId(it) },
            textField("name", "Site name", SITES.NAME, nullable = false),
        )
      }
}
