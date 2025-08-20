package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_SUBDIVISIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class CountrySubdivisionsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COUNTRY_SUBDIVISIONS.CODE

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countries.asSingleValueSublist(
              "country",
              COUNTRY_SUBDIVISIONS.COUNTRY_CODE.eq(COUNTRIES.CODE),
          ),
          organizations.asMultiValueSublist(
              "organizations",
              COUNTRY_SUBDIVISIONS.CODE.eq(ORGANIZATIONS.COUNTRY_SUBDIVISION_CODE),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("code", COUNTRY_SUBDIVISIONS.CODE),
          localizedTextField("name", COUNTRY_SUBDIVISIONS.CODE, "i18n.CountrySubdivisions"),
      )
}
