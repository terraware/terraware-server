package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class FacilitiesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          accessions.asMultiValueSublist("accessions", FACILITIES.ID.eq(ACCESSIONS.FACILITY_ID)),
          sites.asSingleValueSublist("site", FACILITIES.SITE_ID.eq(SITES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.facilities) {
        listOf(
            idWrapperField("id", "Facility ID", FACILITIES.ID) { FacilityId(it) },
            textField("name", "Facility name", FACILITIES.NAME, nullable = false),
            enumField("type", "Facility type", FACILITIES.TYPE_ID, nullable = false),
        )
      }
}
