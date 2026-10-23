package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteMapEditModel
import jakarta.inject.Named
import org.locationtech.jts.geom.TopologyException

@Named
class PlantingSiteMapEditService(
    private val plantingSiteStore: PlantingSiteStore,
) {
  /**
   * Calculates the changes that would be needed to make a planting site's map match a version
   * edited by a user. This does not modify the site; it can be used as a dry run.
   *
   * @throws PlantingSiteMapInvalidException The edited map failed validation.
   * @throws IllegalArgumentException The edit was malformed or its geometries couldn't be compared.
   */
  fun calculateEdit(
      plantingSiteId: PlantingSiteId,
      mapEdit: PlantingSiteMapEditModel,
  ): PlantingSiteEdit {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val existing = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    val desired =
        try {
          mapEdit.toDesiredModel(existing)
        } catch (e: TopologyException) {
          throw IllegalArgumentException("Planting site map has invalid geometry", e)
        }

    return calculateEdit(existing, desired)
  }

  /**
   * Edits a planting site's map to match a version edited by a user.
   *
   * @param substrataToMarkIncomplete IDs of edited substrata whose planting completed flags should
   *   be cleared.
   * @throws PlantingSiteMapInvalidException The edited map failed validation. Nothing is modified.
   * @throws IllegalArgumentException The edit was malformed or its geometries couldn't be compared.
   */
  fun applyEdit(
      plantingSiteId: PlantingSiteId,
      mapEdit: PlantingSiteMapEditModel,
      substrataToMarkIncomplete: Set<SubstratumId> = emptySet(),
  ): ExistingPlantingSiteModel {
    return plantingSiteStore.applyPlantingSiteEdit(
        calculateEdit(plantingSiteId, mapEdit),
        substrataToMarkIncomplete,
    )
  }

  /**
   * Calculates the changes that would be needed to make an existing planting site match a desired
   * version. This does not modify the site; it can be used as a dry run.
   *
   * @param validate If true, check that the desired site's map is valid.
   * @throws PlantingSiteMapInvalidException The desired map failed validation.
   */
  fun calculateEdit(
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      validate: Boolean = true,
  ): PlantingSiteEdit {
    if (validate) {
      desired.requireValid()
    }

    return PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()
  }
}
