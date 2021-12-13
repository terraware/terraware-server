package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.tables.references.COUNTRIES
import com.terraformation.backend.db.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class CountrySubdivisionsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          countries.asSingleValueSublist(
              "country", COUNTRY_SUBDIVISIONS.COUNTRY_CODE.eq(COUNTRIES.CODE)),
          organizations.asMultiValueSublist(
              "organizations",
              COUNTRY_SUBDIVISIONS.CODE.eq(ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.countrySubdivisions) {
        listOf(
            textField("code", "Country subdivision code", COUNTRY_SUBDIVISIONS.CODE),
            textField("name", "Country subdivision name", COUNTRY_SUBDIVISIONS.NAME),
        )
      }
}
