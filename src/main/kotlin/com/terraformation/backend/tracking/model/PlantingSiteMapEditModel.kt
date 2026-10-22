package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.gis.mergeToMultiPolygon
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.locationtech.jts.geom.MultiPolygon

data class SubstratumMapEditModel(
    /** The existing substratum this represents, or null to create a new substratum. */
    val id: SubstratumId?,
    val name: String,
    val boundary: MultiPolygon,
) {
  companion object {
    fun of(existing: ExistingSubstratumModel) =
        SubstratumMapEditModel(id = existing.id, name = existing.name, boundary = existing.boundary)
  }
}

/**
 * A model representing a stratum's edits to be applied.
 *
 * This excludes boundaries, because a stratum's boundary is determined using the union of its
 * substrata's boundaries.
 */
data class StratumMapEditModel(
    /** The existing stratum this represents, or null to create a new stratum. */
    val id: StratumId?,
    val name: String,
    val substrata: List<SubstratumMapEditModel>,
) {
  companion object {
    fun of(existing: ExistingStratumModel) =
        StratumMapEditModel(
            id = existing.id,
            name = existing.name,
            substrata = existing.substrata.map { SubstratumMapEditModel.of(it) },
        )
  }
}

/**
 * The full desired map of an existing planting site, as edited by a user.
 *
 * Edits refer to existing strata and substrata by ID. Statistical settings that determine
 * monitoring plot counts are carried over from the existing strata or set to defaults for new ones.
 */
data class PlantingSiteMapEditModel(
    /**
     * The site boundary. Only used for sites without strata. The boundary of a site with strata is
     * the union of its strata's boundaries.
     */
    val boundary: MultiPolygon?,
    val exclusion: MultiPolygon?,
    val strata: List<StratumMapEditModel>,
) {
  /**
   * Returns the planting site that would result from applying this edit to [existing].
   *
   * Existing strata and substrata keep their stable IDs and settings. New strata and substrata are
   * given stable IDs that aren't used by anything in the existing site, so a new stratum or
   * substratum never inherits the history of one that's being deleted.
   *
   * Use [PlantingSiteModel.validate] to check the geometry of the resulting site model.
   *
   * @throws StratumNotFoundException A stratum ID doesn't refer to a stratum of the existing site.
   * @throws SubstratumNotFoundException A substratum ID doesn't refer to a substratum of the
   *   existing site.
   * @throws IllegalArgumentException A stratum or substratum ID appears more than once, or the edit
   *   would remove the map or all the strata from the site.
   * @throws PlantingSiteMapInvalidException A stratum has no substrata.
   */
  fun toDesiredModel(existing: ExistingPlantingSiteModel): NewPlantingSiteModel {
    requireValidIds(existing)

    if (existing.strata.isNotEmpty() && strata.isEmpty()) {
      throw IllegalArgumentException("Cannot remove all strata from a planting site")
    }

    strata
        .filter { it.substrata.isEmpty() }
        .map { PlantingSiteValidationFailure.stratumHasNoSubstrata(it.name) }
        .ifEmpty { null }
        ?.let { throw PlantingSiteMapInvalidException(it) }

    val existingStrataById = existing.strata.associateBy { it.id }
    val existingSubstrataById = existing.strata.flatMap { it.substrata }.associateBy { it.id }
    val usedStableIds =
        existing.strata.flatMap { stratum ->
          listOf(stratum.stableId) + stratum.substrata.map { it.stableId }
        }
    val stableIdAllocator = StableIdAllocator(usedStableIds)

    val desiredStrata =
        runBlocking(Dispatchers.Default) {
          strata.map { stratum ->
            val existingStratum = stratum.id?.let { existingStrataById[it] }
            val substrata =
                stratum.substrata.map { substratum ->
                  val existingSubstratum = substratum.id?.let { existingSubstrataById[it] }
                  val fullName = "${stratum.name}-${substratum.name}"

                  SubstratumModel.create(
                      boundary = substratum.boundary,
                      exclusion = exclusion,
                      fullName = fullName,
                      name = substratum.name,
                      plantingCompletedTime = existingSubstratum?.plantingCompletedTime,
                      stableId =
                          existingSubstratum?.stableId ?: stableIdAllocator.allocate(fullName),
                  )
                }

            val boundary =
                mergeToMultiPolygon(substrata.map { it.boundary })
                    .unlessSameShapeAs(existingStratum?.boundary)

            if (existingStratum == null) {
              StratumModel.create(
                  boundary = boundary,
                  exclusion = exclusion,
                  name = stratum.name,
                  stableId = stableIdAllocator.allocate(stratum.name),
                  substrata = substrata,
              )
            } else {
              existingStratum.toNew(
                  areaHa = boundary.differenceNullable(exclusion).calculateAreaHectares(),
                  boundary = boundary,
                  name = stratum.name,
                  substrata = substrata,
              )
            }
          }
        }

    val siteBoundary =
        if (desiredStrata.isNotEmpty()) {
          runBlocking(Dispatchers.Default) {
                mergeToMultiPolygon(desiredStrata.map { it.boundary })
              }
              .unlessSameShapeAs(existing.boundary)
        } else {
          boundary ?: throw IllegalArgumentException("Cannot remove map from planting site")
        }

    return PlantingSiteModel.create(
        boundary = siteBoundary,
        description = existing.description,
        exclusion = exclusion,
        gridOrigin = existing.gridOrigin,
        name = existing.name,
        organizationId = existing.organizationId,
        projectId = existing.projectId,
        strata = desiredStrata,
        timeZone = existing.timeZone,
    )
  }

  private fun requireValidIds(existing: ExistingPlantingSiteModel) {
    val existingStratumIds = existing.strata.map { it.id }.toSet()
    val existingSubstratumIds = existing.strata.flatMap { it.substrata }.map { it.id }.toSet()
    val seenStratumIds = mutableSetOf<StratumId>()
    val seenSubstratumIds = mutableSetOf<SubstratumId>()

    strata.forEach { stratum ->
      if (stratum.id != null) {
        if (stratum.id !in existingStratumIds) {
          throw StratumNotFoundException(stratum.id)
        }
        if (!seenStratumIds.add(stratum.id)) {
          throw IllegalArgumentException("Stratum ID ${stratum.id} appears more than once")
        }
      }

      stratum.substrata.forEach { substratum ->
        if (substratum.id != null) {
          if (substratum.id !in existingSubstratumIds) {
            throw SubstratumNotFoundException(substratum.id)
          }
          if (!seenSubstratumIds.add(substratum.id)) {
            throw IllegalArgumentException("Substratum ID ${substratum.id} appears more than once")
          }
        }
      }
    }
  }

  /**
   * Returns [existing] if it covers exactly the same area as this geometry, or this geometry if
   * not. Merging polygons can add or move vertices without changing the shape, and the edit
   * calculator compares boundaries vertex by vertex, so this prevents unchanged boundaries from
   * being treated as edited.
   */
  private fun MultiPolygon.unlessSameShapeAs(existing: MultiPolygon?): MultiPolygon {
    val isSameShape = existing != null && runCatching { equalsTopo(existing) }.getOrDefault(false)
    return if (isSameShape) existing else this
  }

  /**
   * Generates stable IDs based on names, adding numeric suffixes as needed to avoid collisions with
   * existing stable IDs or ones that were already allocated.
   */
  private class StableIdAllocator(usedStableIds: Collection<StableId>) {
    private val used = usedStableIds.map { it.value }.toMutableSet()

    fun allocate(name: String): StableId {
      val value =
          generateSequence(1) { it + 1 }
              .map { if (it == 1) name else "$name-$it" }
              .first { it !in used }
      used.add(value)
      return StableId(value)
    }
  }

  companion object {
    /** Returns a map edit that would leave an existing planting site's map unchanged. */
    fun of(existing: ExistingPlantingSiteModel) =
        PlantingSiteMapEditModel(
            boundary = existing.boundary,
            exclusion = existing.exclusion,
            strata = existing.strata.map { StratumMapEditModel.of(it) },
        )
  }
}
