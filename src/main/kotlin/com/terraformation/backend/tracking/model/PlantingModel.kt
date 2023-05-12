package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import org.jooq.Record

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
