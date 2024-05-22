package com.terraformation.backend.tracking.edit

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import jakarta.inject.Named

@Named
class PlantingSiteEditService(
    val plantingSiteStore: PlantingSiteStore,
) {
  fun applyPlantingSiteEdit(plantingSiteEdit: PlantingSiteEdit): ExistingPlantingSiteModel {
    requirePermissions { updatePlantingSite(plantingSiteEdit.existingModel.id) }

    return plantingSiteStore.fetchSiteById(
        plantingSiteEdit.existingModel.id, PlantingSiteDepth.Plot)
  }
}
