package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSeasonSpeciesTargetsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_SPECIES_TARGETS_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSeasons.asSingleValueSublist(
              "plantingSeason",
              PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID),
          ),
          species.asSingleValueSublist(
              "species",
              PLANTING_SEASON_SPECIES_TARGETS.SPECIES_ID.eq(SPECIES.ID),
          ),
          substrata.asSingleValueSublist(
              "substratum",
              PLANTING_SEASON_SPECIES_TARGETS.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("quantity", PLANTING_SEASON_SPECIES_TARGETS.QUANTITY),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSeasons

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SEASONS)
        .on(PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID))
  }
}
