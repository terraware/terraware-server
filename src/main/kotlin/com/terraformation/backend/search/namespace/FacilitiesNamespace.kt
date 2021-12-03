package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SearchTables
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class FacilitiesNamespace(searchTables: SearchTables, accessionsNamespace: AccessionsNamespace) :
    SearchFieldNamespace() {
  override val sublists: List<SublistField> =
      listOf(
          accessionsNamespace.asMultiValueSublist(
              "accessions", ACCESSIONS.FACILITY_ID.eq(FACILITIES.ID)))

  override val fields: List<SearchField> =
      with(searchTables.facilities) {
        listOf(
            idWrapperField("id", "Facility ID", FACILITIES.ID) { FacilityId(it) },
            textField("name", "Facility name", FACILITIES.NAME, nullable = false),
            enumField("type", "Facility type", FACILITIES.TYPE_ID, nullable = false),
        )
      }
}
