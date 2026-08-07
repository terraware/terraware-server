package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_SPECIES_TARGETS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class StratumSpeciesTargetsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = STRATUM_SPECIES_TARGETS.STRATUM_SPECIES_TARGET_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSites.asSingleValueSublist(
              "plantingSite",
              STRATUM_SPECIES_TARGETS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSiteSpeciesTargets.asSingleValueSublist(
              "plantingSiteSpeciesTarget",
              DSL.and(
                  STRATUM_SPECIES_TARGETS.PLANTING_SITE_ID.eq(
                      PLANTING_SITE_SPECIES_TARGETS.PLANTING_SITE_ID
                  ),
                  STRATUM_SPECIES_TARGETS.SPECIES_ID.eq(PLANTING_SITE_SPECIES_TARGETS.SPECIES_ID),
              ),
          ),
          species.asSingleValueSublist(
              "species",
              STRATUM_SPECIES_TARGETS.SPECIES_ID.eq(SPECIES.ID),
          ),
          strata.asSingleValueSublist(
              "stratum",
              STRATUM_SPECIES_TARGETS.STRATUM_ID.eq(STRATA.ID),
          ),
      )
    }
  }

  // Expose the species and stratum IDs as standalone fields as well as sublists so clients can
  // query one of the IDs as a way to cheaply check the existence of a species/stratum pair without
  // having to include additional joins in the database query.
  override val fields: List<SearchField> =
      listOf(
          idWrapperField("speciesId", STRATUM_SPECIES_TARGETS.SPECIES_ID) { SpeciesId(it) },
          idWrapperField("stratumId", STRATUM_SPECIES_TARGETS.STRATUM_ID) { StratumId(it) },
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(STRATUM_SPECIES_TARGETS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
