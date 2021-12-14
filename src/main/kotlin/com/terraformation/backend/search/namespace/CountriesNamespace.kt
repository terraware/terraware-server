package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.COUNTRIES
import com.terraformation.backend.db.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class CountriesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          organizations.asMultiValueSublist(
              "organizations", COUNTRIES.CODE.eq(ORGANIZATIONS.COUNTRY_CODE)),
          countrySubdivisions.asMultiValueSublist(
              "subdivisions", COUNTRIES.CODE.eq(COUNTRY_SUBDIVISIONS.COUNTRY_CODE)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.countries) {
        listOf(
            textField("code", "Country code", COUNTRIES.CODE),
            textField("name", "Country name", COUNTRIES.NAME),
        )
      }
}
