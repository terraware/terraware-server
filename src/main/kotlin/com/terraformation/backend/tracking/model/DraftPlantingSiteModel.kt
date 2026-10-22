package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import com.terraformation.backend.tracking.db.DraftStratumNotFoundException
import com.terraformation.backend.tracking.db.DraftSubstratumNotFoundException
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
) {
  /**
   * Checks that every substratum ID refers to a substratum of [existing] and appears only once, and
   * that substratum names are unique within the stratum, which the database requires.
   *
   * @param existing The stratum as currently stored, or null if this is a new stratum.
   */
  fun requireValid(existing: DraftStratumModel?) {
    val existingSubstratumIds = existing?.substrata?.map { it.id }?.toSet() ?: emptySet()
    val seenSubstratumIds = mutableSetOf<DraftSubstratumId>()
    val seenSubstratumNames = mutableSetOf<String>()

    substrata.forEach { substratum ->
      if (!seenSubstratumNames.add(substratum.name)) {
        throw IllegalArgumentException(
            "Substratum name ${substratum.name} appears more than once in stratum $name"
        )
      }
      if (substratum.id != null) {
        if (substratum.id !in existingSubstratumIds) {
          throw DraftSubstratumNotFoundException(substratum.id)
        }
        if (!seenSubstratumIds.add(substratum.id)) {
          throw IllegalArgumentException("Substratum ID ${substratum.id} appears more than once")
        }
      }
    }
  }
}

/** The map and wizard-state portion of a draft planting site, which is what `data` carries. */
data class DraftPlantingSiteMap(
    val siteType: DraftPlantingSiteType,
    val siteEditStep: String?,
    val boundarySource: String?,
    val boundary: MultiPolygon?,
    val exclusion: MultiPolygon?,
    val strata: List<DraftStratumModel>,
) {
  /**
   * Checks that the map can be stored as a replacement for [existing]. Simple sites may not have
   * strata, and the site type may not change. Every stratum and substratum ID must refer to an
   * existing stratum or substratum in the right place, and no ID may appear more than once. Stratum
   * names must be unique within the draft and substratum names unique within each stratum, which
   * the database requires.
   *
   * @param existing The map as currently stored, or null if this is a new draft.
   */
  fun requireValid(existing: DraftPlantingSiteMap?) {
    if (existing != null && siteType != existing.siteType) {
      throw IllegalArgumentException("Site type cannot be changed")
    }
    if (siteType == DraftPlantingSiteType.Simple && strata.isNotEmpty()) {
      throw IllegalArgumentException("Simple planting sites cannot have strata")
    }

    val existingStrataById = existing?.strata?.associateBy { it.id } ?: emptyMap()
    val seenStratumIds = mutableSetOf<DraftStratumId>()
    val seenStratumNames = mutableSetOf<String>()

    strata.forEach { stratum ->
      val existingStratum =
          stratum.id?.let { existingStrataById[it] ?: throw DraftStratumNotFoundException(it) }
      if (stratum.id != null && !seenStratumIds.add(stratum.id)) {
        throw IllegalArgumentException("Stratum ID ${stratum.id} appears more than once")
      }
      if (!seenStratumNames.add(stratum.name)) {
        throw IllegalArgumentException("Stratum name ${stratum.name} appears more than once")
      }

      stratum.requireValid(existingStratum)
    }
  }
}

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
  fun validate(): List<PlantingSiteValidationFailure>? = toPlantingSiteModel().validate()

  /** Returns a planting site model with the draft's map. */
  fun toPlantingSiteModel(): NewPlantingSiteModel {
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
  }
}
