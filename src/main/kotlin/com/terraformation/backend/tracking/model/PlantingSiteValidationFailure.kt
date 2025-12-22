package com.terraformation.backend.tracking.model

data class PlantingSiteValidationFailure(
    val type: PlantingSiteValidationFailureType,
    val stratumName: String? = null,
    val substratumName: String? = null,
    val conflictsWith: Set<String>? = null,
) {
  companion object {
    fun duplicateSubstratumName(substratumName: String, stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.DuplicateSubstratumName,
            stratumName,
            substratumName,
        )

    fun duplicateStratumName(stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.DuplicateStratumName,
            stratumName,
        )

    fun exclusionWithoutBoundary() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.ExclusionWithoutBoundary)

    fun siteTooLarge() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.SiteTooLarge)

    fun substratumBoundaryOverlaps(
        conflictsWith: Set<String>,
        substratumName: String,
        stratumName: String,
    ) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubstratumBoundaryOverlaps,
            stratumName,
            substratumName,
            conflictsWith,
        )

    fun substratumInExclusionArea(substratumName: String, stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubstratumInExclusionArea,
            stratumName,
            substratumName,
        )

    fun substratumNotInStratum(substratumName: String, stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.SubstratumNotInStratum,
            stratumName,
            substratumName,
        )

    fun stratumBoundaryOverlaps(conflictsWith: Set<String>, stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.StratumBoundaryOverlaps,
            stratumName,
            conflictsWith = conflictsWith,
        )

    fun stratumHasNoSubstrata(stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.StratumHasNoSubstrata,
            stratumName,
        )

    fun stratumNotInSite(stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.StratumNotInSite,
            stratumName,
        )

    fun strataWithoutSiteBoundary() =
        PlantingSiteValidationFailure(PlantingSiteValidationFailureType.StrataWithoutSiteBoundary)

    fun stratumTooSmall(stratumName: String) =
        PlantingSiteValidationFailure(
            PlantingSiteValidationFailureType.StratumTooSmall,
            stratumName,
        )
  }
}
