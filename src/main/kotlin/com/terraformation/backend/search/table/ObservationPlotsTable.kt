package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationPlotsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATION_PLOTS.OBSERVATION_PLOT_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observationBiomassDetails.asSingleValueSublist(
              "biomassDetails",
              OBSERVATION_PLOTS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_BIOMASS_DETAILS.OBSERVATION_PLOT_ID
              ),
              isRequired = false,
          ),
          observationPlotConditions.asMultiValueSublist(
              "conditions",
              OBSERVATION_PLOTS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_PLOT_CONDITIONS.OBSERVATION_PLOT_ID
              ),
          ),
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observations.asSingleValueSublist(
              "observation",
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
          observationBiomassQuadratSpecies.asMultiValueSublist(
              "quadratSpecies",
              OBSERVATION_PLOTS.OBSERVATION_PLOT_ID.eq(
                  OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_PLOT_ID
              ),
          ),
          recordedTrees.asMultiValueSublist(
              "recordedTrees",
              OBSERVATION_PLOTS.OBSERVATION_PLOT_ID.eq(RECORDED_TREES.OBSERVATION_PLOT_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("claimedTime", OBSERVATION_PLOTS.CLAIMED_TIME),
          timestampField("completedTime", OBSERVATION_PLOTS.COMPLETED_TIME),
          booleanField("isPermanent", OBSERVATION_PLOTS.IS_PERMANENT),
          textField("notes", OBSERVATION_PLOTS.NOTES),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(OBSERVATION_PLOTS.OBSERVATION_ID, OBSERVATION_PLOTS.MONITORING_PLOT_ID)

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(OBSERVATIONS).on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
