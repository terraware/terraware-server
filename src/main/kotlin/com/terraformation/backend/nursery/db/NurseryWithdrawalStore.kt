package com.terraformation.backend.nursery.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.nursery.model.NurserySpeciesModel
import com.terraformation.backend.nursery.model.PlotSpeciesModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

@Named
class NurseryWithdrawalStore(
    private val dslContext: DSLContext,
) {
  fun fetchSiteSpeciesByPlot(plantingSiteId: PlantingSiteId): List<PlotSpeciesModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    with(MONITORING_PLOTS) {
      val densityField =
          DSL.round(
              PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS.cast(SQLDataType.NUMERIC) /
                  PLANTING_SUBZONES.AREA_HA
          )

      return dslContext
          .select(
              ID,
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID,
              densityField,
          )
          .from(this)
          .join(PLANTING_SUBZONE_POPULATIONS)
          .on(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID))
          .join(PLANTING_SUBZONES)
          .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
          .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
          .fetchGroups(ID.asNonNullable())
          .map { (plotId, records) ->
            PlotSpeciesModel(
                monitoringPlotId = plotId,
                species =
                    records.map { record ->
                      NurserySpeciesModel(
                          speciesId = record[PLANTING_SUBZONE_POPULATIONS.SPECIES_ID]!!,
                          density = record[densityField]!!,
                      )
                    },
            )
          }
    }
  }
}
