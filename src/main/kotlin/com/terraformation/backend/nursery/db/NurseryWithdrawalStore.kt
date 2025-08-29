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
import java.math.BigDecimal
import org.jooq.DSLContext

@Named
class NurseryWithdrawalStore(
    private val dslContext: DSLContext,
) {
  fun fetchSiteSpeciesByPlot(plantingSiteId: PlantingSiteId): List<PlotSpeciesModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    with(MONITORING_PLOTS) {
      return dslContext
          .select(
              ID,
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID,
              (PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS / PLANTING_SUBZONES.AREA_HA).`as`(
                  "density_per_hectare"
              ),
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
                    records.map {
                      NurserySpeciesModel(
                          speciesId = it[PLANTING_SUBZONE_POPULATIONS.SPECIES_ID]!!,
                          density = it.get("density_per_hectare", BigDecimal::class.java)!!,
                      )
                    },
            )
          }
    }
  }
}
