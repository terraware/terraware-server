package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationBiomassQuadratSpeciesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_BIOMASS_QUADRAT_SPECIES_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observationBiomassSpecies.asSingleValueSublist(
              "biomassDetails",
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID
              ),
          ),
          observationBiomassSpecies.asSingleValueSublist(
              "biomassSpecies",
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(
                  OBSERVATION_BIOMASS_SPECIES.ID
              ),
          ),
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_PLOTS.OBSERVATION_PLOT_ID
              ),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("abundancePercent", OBSERVATION_BIOMASS_QUADRAT_SPECIES.ABUNDANCE_PERCENT),
          enumField("position", OBSERVATION_BIOMASS_QUADRAT_SPECIES.POSITION_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(OBSERVATIONS)
        .on(OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
