package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity

data class ProjectSpeciesOverride(
    val overriddenJustification: String,
    val overriddenNativity: SpeciesNativity,
    val projectId: ProjectId?,
    val speciesId: SpeciesId,
)
