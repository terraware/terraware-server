package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class RecordedTreesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = RECORDED_TREES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observationBiomassSpecies.asSingleValueSublist(
              "biomassSpecies",
              RECORDED_TREES.BIOMASS_SPECIES_ID.eq(OBSERVATION_BIOMASS_SPECIES.ID),
          ),
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              RECORDED_TREES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID),
          ),
          observationPlots.asSingleValueSublist(
              "observationPlot",
              RECORDED_TREES.OBSERVATION_PLOT_ID.eq(OBSERVATION_PLOTS.OBSERVATION_PLOT_ID),
          ),
          observations.asSingleValueSublist(
              "observation",
              RECORDED_TREES.OBSERVATION_ID.eq(OBSERVATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          textField("description", RECORDED_TREES.DESCRIPTION),
          bigDecimalField("diameterAtBreastHeight", RECORDED_TREES.DIAMETER_AT_BREAST_HEIGHT_CM),
          enumField("growthForm", RECORDED_TREES.TREE_GROWTH_FORM_ID),
          bigDecimalField("height", RECORDED_TREES.HEIGHT_M),
          idWrapperField("id", RECORDED_TREES.ID) { RecordedTreeId(it) },
          booleanField("isDead", RECORDED_TREES.IS_DEAD),
          bigDecimalField("pointOfMeasurement", RECORDED_TREES.POINT_OF_MEASUREMENT_M),
          integerField("shrubDiameter", RECORDED_TREES.SHRUB_DIAMETER_CM),
          integerField("treeNumber", RECORDED_TREES.TREE_NUMBER),
          integerField("trunkNumber", RECORDED_TREES.TRUNK_NUMBER),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.observations

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(OBSERVATIONS).on(RECORDED_TREES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
  }
}
