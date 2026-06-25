package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ECOREGIONS
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class EcoregionCountriesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ECOREGION_COUNTRIES.ECOREGION_COUNTRY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countries.asSingleValueSublist(
              "country",
              ECOREGION_COUNTRIES.COUNTRY_CODE.eq(COUNTRIES.CODE),
          ),
          ecoregions.asSingleValueSublist(
              "ecoregion",
              ECOREGION_COUNTRIES.ECOREGION_ID.eq(ECOREGIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> = emptyList()
}
