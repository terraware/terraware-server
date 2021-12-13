package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.tables.references.COUNTRIES
import com.terraformation.backend.db.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class OrganizationsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          countries.asSingleValueSublist("country", ORGANIZATIONS.COUNTRY_CODE.eq(COUNTRIES.CODE)),
          countrySubdivisions.asSingleValueSublist(
              "countrySubdivision",
              ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE.eq(COUNTRY_SUBDIVISIONS.CODE)),
          projects.asMultiValueSublist("projects", ORGANIZATIONS.ID.eq(PROJECTS.ORGANIZATION_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.organizations) {
        listOf(
            textField("countryCode", "Organization country code", ORGANIZATIONS.COUNTRY_CODE),
            textField(
                "countrySubdivisionCode",
                "Organization country subdivision code",
                ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE),
            idWrapperField("id", "Organization ID", ORGANIZATIONS.ID) { OrganizationId(it) },
            textField("name", "Organization name", ORGANIZATIONS.NAME, nullable = false),
        )
      }
}
