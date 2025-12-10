package com.terraformation.backend.tracking.model

enum class PlantingSiteValidationFailureType {
  DuplicateSubstratumName,
  DuplicateStratumName,
  ExclusionWithoutBoundary,
  SiteTooLarge,
  SubstratumBoundaryOverlaps,
  SubstratumInExclusionArea,
  SubstratumNotInStratum,
  StratumBoundaryOverlaps,
  StratumHasNoSubstrata,
  StratumNotInSite,
  StratumTooSmall,
  StrataWithoutSiteBoundary,
}
