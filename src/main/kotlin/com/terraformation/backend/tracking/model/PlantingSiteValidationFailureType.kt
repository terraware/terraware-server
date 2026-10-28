package com.terraformation.backend.tracking.model

enum class PlantingSiteValidationFailureType {
  DuplicateSubstratumName,
  DuplicateStratumName,
  ExclusionInvalid,
  ExclusionWithoutBoundary,
  SiteBoundaryInvalid,
  SiteTooLarge,
  SubstratumBoundaryInvalid,
  SubstratumBoundaryOverlaps,
  SubstratumInExclusionArea,
  SubstratumNotInStratum,
  StratumBoundaryInvalid,
  StratumBoundaryOverlaps,
  StratumHasNoSubstrata,
  StratumNotInSite,
  StratumTooSmall,
  StrataWithoutSiteBoundary,
}
