package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.BOTANICAL_COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ECOREGIONS
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_BOTANICAL_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class EcoregionBotanicalCountriesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ECOREGION_BOTANICAL_COUNTRIES.ECOREGION_BOTANICAL_COUNTRY_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          botanicalCountries.asSingleValueSublist(
              "botanicalCountry",
              ECOREGION_BOTANICAL_COUNTRIES.BOTANICAL_COUNTRY_ID.eq(BOTANICAL_COUNTRIES.ID),
          ),
          ecoregions.asSingleValueSublist(
              "ecoregion",
              ECOREGION_BOTANICAL_COUNTRIES.ECOREGION_ID.eq(ECOREGIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> = emptyList()
}
