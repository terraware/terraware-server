package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_SPECIES_TARGETS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSiteSpeciesTargetsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_SPECIES_TARGET_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          species.asSingleValueSublist(
              "species",
              PLANTING_SITE_SPECIES_TARGETS.SPECIES_ID.eq(SPECIES.ID),
          ),
          stratumSpeciesTargets.asMultiValueSublist(
              "stratumSpeciesTargets",
              DSL.and(
                  PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_ID.eq(
                      STRATUM_SPECIES_TARGETS.PLANTING_SITE_ID
                  ),
                  PLANTING_SITE_SPECIES_TARGETS.SPECIES_ID.eq(STRATUM_SPECIES_TARGETS.SPECIES_ID),
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("speciesId", PLANTING_SITE_SPECIES_TARGETS.SPECIES_ID) { SpeciesId(it) },
          longField("targetPlants", PLANTING_SITE_SPECIES_TARGETS.TARGET_PLANTS),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
