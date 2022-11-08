package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry

data class PlotModel(
    val boundary: Geometry,
    val id: PlotId,
    val fullName: String,
    val name: String,
) {
  constructor(
      record: Record,
  ) : this(
      record[PLOTS.BOUNDARY]!!,
      record[PLOTS.ID]!!,
      record[PLOTS.FULL_NAME]!!,
      record[PLOTS.NAME]!!,
  )
}

data class PlantingZoneModel(
    val boundary: Geometry,
    val id: PlantingZoneId,
    val name: String,
    val plots: List<PlotModel>,
) {
  constructor(
      record: Record,
      plotsMultiset: Field<List<PlotModel>>
  ) : this(
      record[PLANTING_ZONES.BOUNDARY]!!,
      record[PLANTING_ZONES.ID]!!,
      record[PLANTING_ZONES.NAME]!!,
      record[plotsMultiset]!!,
  )
}

data class PlantingSiteModel(
    val boundary: Geometry?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val plantingZones: List<PlantingZoneModel>,
) {
  constructor(
      row: PlantingSitesRow,
      plantingZones: List<PlantingZoneModel>
  ) : this(row.boundary, row.description, row.id!!, row.name!!, plantingZones)

  constructor(
      record: Record,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      record[PLANTING_SITES.BOUNDARY],
      record[PLANTING_SITES.DESCRIPTION],
      record[PLANTING_SITES.ID]!!,
      record[PLANTING_SITES.NAME]!!,
      plantingZonesMultiset?.let { record[it] } ?: emptyList())
}
