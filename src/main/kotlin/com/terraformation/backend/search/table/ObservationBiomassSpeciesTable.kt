package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

class ObservationBiomassSpeciesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_BIOMASS_SPECIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_BIOMASS_SPECIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              OBSERVATION_BIOMASS_SPECIES.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_PLOTS.OBSERVATION_PLOT_ID
              ),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_BIOMASS_SPECIES.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          observationBiomassQuadratSpecies.asMultiValueSublist(
              "quadratSpecies",
              OBSERVATION_BIOMASS_SPECIES.ID.eq(
                  OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID
              ),
          ),
          species.asSingleValueSublist(
              "species",
              OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(SPECIES.ID),
              isRequired = false,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("commonName", OBSERVATION_BIOMASS_SPECIES.COMMON_NAME),
          booleanField("isInvasive", OBSERVATION_BIOMASS_SPECIES.IS_INVASIVE),
          booleanField("isThreatened", OBSERVATION_BIOMASS_SPECIES.IS_THREATENED),
          // Emulate the web app's coalescing of observation-level and org-level species names.
          textField(
              "name",
              DSL.coalesce(
                      OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
                      DSL.select(SPECIES.SCIENTIFIC_NAME)
                          .from(SPECIES)
                          .where(SPECIES.ID.eq(OBSERVATION_BIOMASS_SPECIES.SPECIES_ID)),
                  )
                  .cast(SQLDataType.VARCHAR),
          ),
          textField("scientificName", OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_BIOMASS_SPECIES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
