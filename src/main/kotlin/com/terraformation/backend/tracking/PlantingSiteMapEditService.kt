package com.terraformation.backend.tracking

import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import jakarta.inject.Named

@Named
class PlantingSiteMapEditService(
    private val plantingSiteStore: PlantingSiteStore,
) {
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

  /**
   * Applies a previously-calculated edit to a planting site.
   *
   * @param substrataToMarkIncomplete IDs of edited substrata whose planting completed flags should
   *   be cleared.
   */
  fun applyEdit(
      edit: PlantingSiteEdit,
      substrataToMarkIncomplete: Set<SubstratumId> = emptySet(),
  ): ExistingPlantingSiteModel {
    return plantingSiteStore.applyPlantingSiteEdit(edit, substrataToMarkIncomplete)
  }
}
