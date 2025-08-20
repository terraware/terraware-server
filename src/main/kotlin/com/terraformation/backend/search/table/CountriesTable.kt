package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class CountriesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COUNTRIES.CODE

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asMultiValueSublist(
              "organizations",
              COUNTRIES.CODE.eq(ORGANIZATIONS.COUNTRY_CODE),
          ),
          countrySubdivisions.asMultiValueSublist(
              "subdivisions",
              COUNTRIES.CODE.eq(COUNTRY_SUBDIVISIONS.COUNTRY_CODE),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("code", COUNTRIES.CODE),
          localizedTextField("name", COUNTRIES.CODE, "i18n.Countries"),
          enumField("region", COUNTRIES.REGION_ID),
      )
}
