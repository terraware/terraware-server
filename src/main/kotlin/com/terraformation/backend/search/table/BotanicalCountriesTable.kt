package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.COUNTRY_BOTANICAL_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class BotanicalCountriesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BOTANICAL_COUNTRIES.LEVEL3_CODE

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countryBotanicalCountries.asMultiValueSublist(
              "countries",
              BOTANICAL_COUNTRIES.LEVEL3_CODE.eq(
                  COUNTRY_BOTANICAL_COUNTRIES.BOTANICAL_COUNTRY_CODE
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("code", BOTANICAL_COUNTRIES.LEVEL3_CODE),
          localizedTextField("name", BOTANICAL_COUNTRIES.LEVEL3_CODE, "i18n.BotanicalCountries"),
      )
}
