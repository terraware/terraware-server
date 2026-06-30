package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_BOTANICAL_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class CountryBotanicalCountriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = COUNTRY_BOTANICAL_COUNTRIES.COUNTRY_BOTANICAL_COUNTRY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          botanicalCountries.asSingleValueSublist(
              "botanicalCountry",
              COUNTRY_BOTANICAL_COUNTRIES.BOTANICAL_COUNTRY_ID.eq(BOTANICAL_COUNTRIES.ID),
          ),
          countries.asSingleValueSublist(
              "country",
              COUNTRY_BOTANICAL_COUNTRIES.COUNTRY_CODE.eq(COUNTRIES.CODE),
          ),
      )
    }
  }

  override val fields: List<SearchField> = emptyList()
}
