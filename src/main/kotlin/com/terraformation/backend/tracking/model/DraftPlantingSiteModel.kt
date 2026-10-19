package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.locationtech.jts.geom.MultiPolygon

data class DraftSubstratumModel(
    val id: DraftSubstratumId?,
    val name: String,
    val boundary: MultiPolygon,
)

data class DraftStratumModel(
    val id: DraftStratumId?,
    val name: String,
    val boundary: MultiPolygon,
    val initialPlantingDensity: BigDecimal,
    val substrata: List<DraftSubstratumModel>,
)

/** The map and wizard-state portion of a draft planting site, which is what `data` carries. */
data class DraftPlantingSiteMap(
    val siteType: DraftPlantingSiteType,
    val siteEditStep: String?,
    val boundarySource: String?,
    val boundary: MultiPolygon?,
    val exclusion: MultiPolygon?,
    val strata: List<DraftStratumModel>,
)

data class DraftPlantingSiteModel(
    val id: DraftPlantingSiteId?,
    val organizationId: OrganizationId,
    val projectId: ProjectId?,
    val name: String,
    val description: String?,
    val timeZone: ZoneId?,
    val map: DraftPlantingSiteMap,
    val createdBy: UserId? = null,
    val createdTime: Instant? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
) {
  val numStrata: Int
    get() = map.strata.size

  val numSubstrata: Int
    get() = map.strata.sumOf { it.substrata.size }

  /** Returns a list of validation problems with the draft's map, or null if it is valid. */
  fun validate(): List<PlantingSiteValidationFailure>? {
    val strata =
        map.strata.map { stratum ->
          StratumModel.create(
              boundary = stratum.boundary,
              exclusion = map.exclusion,
              initialPlantingDensity = stratum.initialPlantingDensity,
              name = stratum.name,
              substrata =
                  stratum.substrata.map { substratum ->
                    SubstratumModel.create(
                        boundary = substratum.boundary,
                        exclusion = map.exclusion,
                        fullName = "${stratum.name}-${substratum.name}",
                        name = substratum.name,
                    )
                  },
          )
        }

    return PlantingSiteModel.create(
            boundary = map.boundary,
            description = description,
            exclusion = map.exclusion,
            name = name,
            organizationId = organizationId,
            projectId = projectId,
            strata = strata,
            timeZone = timeZone,
        )
        .validate()
  }
}
