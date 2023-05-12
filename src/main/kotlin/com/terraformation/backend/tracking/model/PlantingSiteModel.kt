package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.time.Month
import java.time.ZoneId
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.MultiPolygon

data class PlantingSiteModel(
    val areaHa: BigDecimal? = null,
    val boundary: MultiPolygon?,
    val description: String?,
    val id: PlantingSiteId,
    val name: String,
    val organizationId: OrganizationId,
    val plantingSeasonEndMonth: Month? = null,
    val plantingSeasonStartMonth: Month? = null,
    val plantingZones: List<PlantingZoneModel>,
    val timeZone: ZoneId? = null,
) {
  constructor(
      record: Record,
      plantingZonesMultiset: Field<List<PlantingZoneModel>>? = null
  ) : this(
      areaHa = record[PLANTING_SITES.AREA_HA],
      boundary = record[PLANTING_SITES.BOUNDARY] as? MultiPolygon,
      description = record[PLANTING_SITES.DESCRIPTION],
      id = record[PLANTING_SITES.ID]!!,
      name = record[PLANTING_SITES.NAME]!!,
      organizationId = record[PLANTING_SITES.ORGANIZATION_ID]!!,
      plantingSeasonEndMonth = record[PLANTING_SITES.PLANTING_SEASON_END_MONTH],
      plantingSeasonStartMonth = record[PLANTING_SITES.PLANTING_SEASON_START_MONTH],
      plantingZones = plantingZonesMultiset?.let { record[it] } ?: emptyList(),
      timeZone = record[PLANTING_SITES.TIME_ZONE],
  )

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingSiteModel &&
        description == other.description &&
        id == other.id &&
        name == other.name &&
        timeZone == other.timeZone &&
        plantingSeasonEndMonth == other.plantingSeasonEndMonth &&
        plantingSeasonStartMonth == other.plantingSeasonStartMonth &&
        plantingZones.size == other.plantingZones.size &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        plantingZones.zip(other.plantingZones).all { it.first.equals(it.second, tolerance) } &&
        (boundary == null && other.boundary == null ||
            boundary != null &&
                other.boundary != null &&
                boundary.equalsExact(other.boundary, tolerance))
  }
}
