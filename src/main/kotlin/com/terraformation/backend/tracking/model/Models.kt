package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.PlotId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry

data class PlotModel(
    val boundary: Geometry,
    val id: PlotId,
    val fullName: String,
    val name: String,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlotModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingZoneModel(
    val boundary: Geometry,
    val id: PlantingZoneId,
    val name: String,
    val plots: List<PlotModel>,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        plots.size == other.plots.size &&
        plots.zip(other.plots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingSiteModel(
    val boundary: Geometry?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val plantingZones: List<PlantingZoneModel>,
) {
  constructor(
      record: Record,
      plantingSitesBoundaryField: Field<Geometry?>,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      record[plantingSitesBoundaryField],
      record[PLANTING_SITES.DESCRIPTION],
      record[PLANTING_SITES.ID]!!,
      record[PLANTING_SITES.NAME]!!,
      plantingZonesMultiset?.let { record[it] } ?: emptyList())

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        plantingZones.size == other.plantingZones.size &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        (boundary == null && other.boundary == null ||
            boundary != null &&
                other.boundary != null &&
                boundary.equalsExact(other.boundary, tolerance))
  }
}
