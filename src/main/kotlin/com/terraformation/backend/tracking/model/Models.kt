package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

data class MonitoringPlotModel(
    val boundary: MultiPolygon,
    val id: MonitoringPlotId,
    val fullName: String,
    val name: String,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is MonitoringPlotModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingSubzoneModel(
    val boundary: MultiPolygon,
    val id: PlantingSubzoneId,
    val fullName: String,
    val name: String,
    val monitoringPlots: List<MonitoringPlotModel>,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSubzoneModel &&
        id == other.id &&
        fullName == other.fullName &&
        name == other.name &&
        monitoringPlots.zip(other.monitoringPlots).all { it.first.equals(it.second, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingZoneModel(
    val boundary: MultiPolygon,
    val id: PlantingZoneId,
    val name: String,
    val plantingSubzones: List<PlantingSubzoneModel>,
) {
  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        plantingSubzones.size == other.plantingSubzones.size &&
        plantingSubzones.zip(other.plantingSubzones).all {
          it.first.equals(it.second, tolerance)
        } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}

data class PlantingSiteModel(
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    val plantingZones: List<PlantingZoneModel>,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record,
      plantingSitesBoundaryField: Field<Geometry?>,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      record[plantingSitesBoundaryField] as? MultiPolygon,
      record[PLANTING_SITES.DESCRIPTION],
      record[PLANTING_SITES.ID]!!,
      record[PLANTING_SITES.NAME]!!,
      record[PLANTING_SITES.ORGANIZATION_ID]!!,
      plantingZonesMultiset?.let { record[it] } ?: emptyList(),
      record[PLANTING_SITES.TIME_ZONE],
  )

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        plantingZones.size == other.plantingZones.size &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        (boundary == null && other.boundary == null ||
            boundary != null &&
                other.boundary != null &&
                boundary.equalsExact(other.boundary, tolerance))
  }
}

data class PlantingModel(
    val id: PlantingId,
    val notes: String? = null,
    val numPlants: Int,
    val plantingSubzoneId: PlantingSubzoneId? = null,
    val speciesId: SpeciesId,
    val type: PlantingType,
) {
  constructor(
      record: Record
  ) : this(
      record[PLANTINGS.ID]!!,
      record[PLANTINGS.NOTES],
      record[PLANTINGS.NUM_PLANTS]!!,
      record[PLANTINGS.PLANTING_SUBZONE_ID],
      record[PLANTINGS.SPECIES_ID]!!,
      record[PLANTINGS.PLANTING_TYPE_ID]!!,
  )
}

data class DeliveryModel(
    val id: DeliveryId,
    val plantings: List<PlantingModel>,
    val plantingSiteId: PlantingSiteId,
    val withdrawalId: WithdrawalId,
) {
  constructor(
      record: Record,
      plantingsMultisetField: Field<List<PlantingModel>>
  ) : this(
      record[DELIVERIES.ID]!!,
      record[plantingsMultisetField],
      record[DELIVERIES.PLANTING_SITE_ID]!!,
      record[DELIVERIES.WITHDRAWAL_ID]!!,
  )
}
