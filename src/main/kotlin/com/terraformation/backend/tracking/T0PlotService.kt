package com.terraformation.backend.tracking

import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.model.PlotT0DataModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class T0PlotService(
    private val dslContext: DSLContext,
    private val t0PlotStore: T0PlotStore,
) {
  fun assignT0PlotsData(plotsList: List<PlotT0DataModel>) {
    dslContext.transaction { _ ->
      plotsList.forEach { model ->
        if (model.observationId == null) {
          t0PlotStore.assignT0PlotSpeciesDensities(model.monitoringPlotId, model.densityData)
        } else {
          t0PlotStore.assignT0PlotObservation(model.monitoringPlotId, model.observationId)
        }
      }
    }
  }
}
