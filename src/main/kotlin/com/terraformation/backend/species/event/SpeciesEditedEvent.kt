package com.terraformation.backend.species.event

import com.terraformation.backend.species.model.ExistingSpeciesModel

data class SpeciesEditedEvent(val species: ExistingSpeciesModel)
