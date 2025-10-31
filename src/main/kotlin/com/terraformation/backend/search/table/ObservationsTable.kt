package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ObservationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = OBSERVATIONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          observationBiomassDetails.asMultiValueSublist(
              "biomassDetails",
              OBSERVATIONS.ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID),
          ),
          observationPlots.asMultiValueSublist(
              "observationPlots",
              OBSERVATIONS.ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              OBSERVATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("completedTime", OBSERVATIONS.COMPLETED_TIME),
          timestampField("createdTime", OBSERVATIONS.CREATED_TIME),
          dateField("endDate", OBSERVATIONS.END_DATE),
          idWrapperField("id", OBSERVATIONS.ID) { ObservationId(it) },
          booleanField("isAdHoc", OBSERVATIONS.IS_AD_HOC),
          idWrapperField("plantingSiteHistoryId", OBSERVATIONS.PLANTING_SITE_HISTORY_ID) {
            PlantingSiteHistoryId(it)
          },
          dateField("startDate", OBSERVATIONS.START_DATE),
          nonLocalizableEnumField("state", OBSERVATIONS.STATE_ID),
          enumField("type", OBSERVATIONS.OBSERVATION_TYPE_ID),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(OBSERVATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
