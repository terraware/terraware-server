package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.EcoregionId
import com.terraformation.backend.db.default_schema.tables.references.ECOREGIONS
import com.terraformation.backend.db.default_schema.tables.references.ECOREGION_COUNTRIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.TableField

class EcoregionsTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = ECOREGIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          ecoregionCountries.asMultiValueSublist(
              "ecoregionCountries",
              ECOREGIONS.ID.eq(ECOREGION_COUNTRIES.ECOREGION_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", ECOREGIONS.ID) { EcoregionId(it) },
          localizedTextField("name", ECOREGIONS.ID, "i18n.Ecoregions", "ecoregions."),
      )
}
