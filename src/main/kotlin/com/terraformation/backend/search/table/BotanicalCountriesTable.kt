package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class BotanicalCountriesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = BOTANICAL_COUNTRIES.ID

  override val sublists: List<SublistField> = emptyList()

  override val fields: List<SearchField> =
      listOf(
          textField("level3Code", BOTANICAL_COUNTRIES.LEVEL3_CODE),
          localizedTextField("name", BOTANICAL_COUNTRIES.LEVEL3_CODE, "i18n.BotanicalCountries"),
      )
}
