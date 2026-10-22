package com.terraformation.backend.tracking.model

/** Returns a map edit that leaves the site's map unchanged. Tests can modify it as needed. */
fun ExistingPlantingSiteModel.toMapEdit() =
    PlantingSiteMapEditModel(
        boundary = boundary,
        exclusion = exclusion,
        strata =
            strata.map { stratum ->
              StratumMapEditModel(
                  id = stratum.id,
                  name = stratum.name,
                  substrata =
                      stratum.substrata.map { substratum ->
                        SubstratumMapEditModel(substratum.id, substratum.name, substratum.boundary)
                      },
              )
            },
    )
