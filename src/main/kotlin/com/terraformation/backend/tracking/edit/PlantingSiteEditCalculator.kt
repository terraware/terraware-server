package com.terraformation.backend.tracking.edit

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.AnyPlantingSubzoneModel
import com.terraformation.backend.tracking.model.AnyPlantingZoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSubzoneModel
import com.terraformation.backend.tracking.model.ExistingPlantingZoneModel
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.coveragePercent
import com.terraformation.backend.util.differenceNullable
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon

class PlantingSiteEditCalculator(
    private val existingSite: ExistingPlantingSiteModel,
    private val desiredSite: AnyPlantingSiteModel,
    private val plantedSubzoneIds: Set<PlantingSubzoneId>,
) {
  private val problems = mutableListOf<PlantingSiteEditProblem>()

  fun calculateSiteEdit(): PlantingSiteEdit {
    if (desiredSite.boundary == null) {
      throw IllegalArgumentException("Cannot remove map from site")
    }

    val zoneEdits =
        if (plantedSubzoneIds.isEmpty()) {
          calculateZoneEditsForUnplantedSite()
        } else {
          calculateZoneEdits()
        }

    return PlantingSiteEdit(
        areaHaDifference = calculateAreaHaDifference(existingSite.boundary, desiredSite.boundary),
        desiredModel = desiredSite,
        existingModel = existingSite,
        plantingZoneEdits = zoneEdits,
        problems = problems,
    )
  }

  private fun calculateZoneEdits(): List<PlantingZoneEdit> {
    val zoneMappings: Map<AnyPlantingZoneModel, ExistingPlantingZoneModel?> =
        desiredSite.plantingZones.associateWith { findExistingZone(it) }
    val existingZonesInUse = zoneMappings.values.filterNotNull().toSet()

    // If more than one desired zone maps to the same existing zone, it's an attempt to split.
    findDuplicateValues(zoneMappings).forEach { (existingZone, desiredZones) ->
      problems.add(
          PlantingSiteEditProblem.CannotSplitZone(
              conflictsWith = desiredZones.map { it.name }.toSet(), zoneName = existingZone.name))
    }

    val createEdits =
        zoneMappings
            .filterValues { it == null }
            .keys
            .map { newZone ->
              PlantingZoneEdit.Create(
                  desiredModel = newZone,
                  plantingSubzoneEdits =
                      newZone.plantingSubzones.map { newSubzone ->
                        PlantingSubzoneEdit.Create(newSubzone)
                      })
            }

    val deleteEdits =
        existingSite.plantingZones.toSet().minus(existingZonesInUse).map { existingZone ->
          val plantingSubzoneEdits =
              existingZone.plantingSubzones.map { existingSubzone ->
                PlantingSubzoneEdit.Delete(existingSubzone)
              }

          checkPlantedSubzoneDeletions(existingZone, plantingSubzoneEdits)

          PlantingZoneEdit.Delete(
              existingModel = existingZone,
              monitoringPlotsRemoved =
                  existingZone.plantingSubzones
                      .flatMap { it.monitoringPlots.map { plot -> plot.id } }
                      .toSet(),
              plantingSubzoneEdits = plantingSubzoneEdits,
          )
        }

    val updateEdits =
        zoneMappings
            .filterValues { it != null }
            .mapNotNull { (desiredZone, existingZone) ->
              val subzoneEdits = calculateSubzoneEdits(existingZone!!, desiredZone)

              val existingUsableBoundary =
                  existingZone.boundary.differenceNullable(existingSite.exclusion)
              val desiredUsableBoundary =
                  desiredZone.boundary.differenceNullable(desiredSite.exclusion)

              if (subzoneEdits.isEmpty() &&
                  existingZone.name == desiredZone.name &&
                  existingUsableBoundary.equalsExact(
                      desiredZone.boundary.differenceNullable(desiredSite.exclusion), 0.000001)) {
                null
              } else {
                val areaHaDifference =
                    calculateAreaHaDifference(existingUsableBoundary, desiredUsableBoundary)

                // Number of permanent clusters to add is:
                //   (current number of clusters) * (size of added region) / (size of existing zone)
                // rounded down with a minimum of 1, but only as many as the added region can fit.
                val addedRegion =
                    desiredUsableBoundary
                        .difference(existingUsableBoundary)
                        .toNormalizedMultiPolygon()
                val newClustersThatFitInAddedRegion =
                    if (!addedRegion.isEmpty && areaHaDifference > BigDecimal.ZERO) {
                      val addedRegionArea = addedRegion.calculateAreaHectares()
                      val existingArea = existingUsableBoundary.calculateAreaHectares()
                      val addedRegionAreaRatio = addedRegionArea / existingArea
                      val newClustersBasedOnAreaRatio =
                          addedRegionAreaRatio
                              .multiply(existingZone.numPermanentClusters.toBigDecimal())
                              .setScale(0, RoundingMode.DOWN)
                              .toInt()
                      val newClustersWithMinimum = max(newClustersBasedOnAreaRatio, 1)
                      desiredZone
                          .findUnusedSquares(
                              existingSite.gridOrigin!!,
                              count = newClustersWithMinimum,
                              exclusion = desiredSite.exclusion,
                              searchBoundary = addedRegion)
                          .size
                    } else {
                      0
                    }

                val removedRegion =
                    existingUsableBoundary
                        .difference(desiredUsableBoundary)
                        .toNormalizedMultiPolygon()
                val monitoringPlotsRemoved: Set<MonitoringPlotId> =
                    existingZone.plantingSubzones
                        .flatMap { it.monitoringPlots }
                        .filter { it.boundary.coveragePercent(removedRegion) > 0.00001 }
                        .map { it.id }
                        .toSet()

                PlantingZoneEdit.Update(
                    addedRegion = addedRegion,
                    areaHaDifference = areaHaDifference,
                    desiredModel = desiredZone,
                    existingModel = existingZone,
                    monitoringPlotsRemoved = monitoringPlotsRemoved,
                    numPermanentClustersToAdd = newClustersThatFitInAddedRegion,
                    plantingSubzoneEdits = subzoneEdits,
                    removedRegion = removedRegion,
                )
              }
            }

    return deleteEdits + createEdits + updateEdits
  }

  private fun calculateSubzoneEdits(
      existingZone: ExistingPlantingZoneModel,
      desiredZone: AnyPlantingZoneModel
  ): List<PlantingSubzoneEdit> {
    val subzoneMappings: Map<AnyPlantingSubzoneModel, ExistingPlantingSubzoneModel?> =
        desiredZone.plantingSubzones.associateWith {
          findExistingSubzone(existingZone, desiredZone, it)
        }
    val existingSubzonesInUse = subzoneMappings.values.filterNotNull().toSet()

    // If more than one desired subzone maps to the same existing subzone, it's an attempt to split.
    findDuplicateValues(subzoneMappings).forEach { (existingSubzone, desiredSubzones) ->
      problems.add(
          PlantingSiteEditProblem.CannotSplitSubzone(
              conflictsWith = desiredSubzones.map { it.name }.toSet(),
              subzoneName = existingSubzone.name,
              zoneName = existingZone.name))
    }

    val createEdits =
        subzoneMappings
            .filterValues { it == null }
            .keys
            .map { newSubzone -> PlantingSubzoneEdit.Create(newSubzone) }

    val deleteEdits =
        existingZone.plantingSubzones.toSet().minus(existingSubzonesInUse).map { existingSubzone ->
          PlantingSubzoneEdit.Delete(existingSubzone)
        }

    val updateEdits =
        subzoneMappings
            .filterValues { it != null }
            .mapNotNull { (desiredSubzone, existingSubzone) ->
              val desiredUsableBoundary =
                  desiredSubzone.boundary.differenceNullable(desiredSite.exclusion)
              val existingUsableBoundary =
                  existingSubzone!!.boundary.differenceNullable(existingSite.exclusion)

              if (existingSubzone.name == desiredSubzone.name &&
                  existingSubzone.boundary.equalsExact(desiredSubzone.boundary, 0.00001) &&
                  existingUsableBoundary.equalsExact(desiredUsableBoundary, 0.00001)) {
                null
              } else {
                PlantingSubzoneEdit.Update(
                    addedRegion =
                        desiredUsableBoundary
                            .difference(existingUsableBoundary)
                            .toNormalizedMultiPolygon(),
                    areaHaDifference =
                        calculateAreaHaDifference(
                            existingSubzone.boundary, desiredSubzone.boundary),
                    desiredModel = desiredSubzone,
                    existingModel = existingSubzone,
                    removedRegion =
                        existingUsableBoundary
                            .difference(desiredUsableBoundary)
                            .toNormalizedMultiPolygon(),
                )
              }
            }

    checkPlantedSubzoneDeletions(existingZone, deleteEdits)

    return deleteEdits + createEdits + updateEdits
  }

  /**
   * If a site has no plants, we allow unrestricted editing of its map. This is modeled as deletion
   * of all its existing zones and subzones and creation of all the desired ones, with none of the
   * validation checks for things like changes to borders between zones.
   */
  private fun calculateZoneEditsForUnplantedSite(): List<PlantingZoneEdit> {
    val deletions =
        existingSite.plantingZones.map { existingZone ->
          PlantingZoneEdit.Delete(
              existingModel = existingZone,
              monitoringPlotsRemoved = emptySet(),
              plantingSubzoneEdits =
                  existingZone.plantingSubzones.map { existingSubzone ->
                    PlantingSubzoneEdit.Delete(existingSubzone)
                  },
          )
        }

    val creations =
        desiredSite.plantingZones.map { desiredZone ->
          PlantingZoneEdit.Create(
              desiredModel = desiredZone,
              plantingSubzoneEdits =
                  desiredZone.plantingSubzones.map { desiredSubzone ->
                    PlantingSubzoneEdit.Create(desiredSubzone)
                  })
        }

    return deletions + creations
  }

  private fun checkPlantedSubzoneDeletions(
      plantingZone: AnyPlantingZoneModel,
      subzoneEdits: Collection<PlantingSubzoneEdit>
  ) {
    subzoneEdits.forEach { subzoneEdit ->
      if (subzoneEdit is PlantingSubzoneEdit.Delete &&
          subzoneEdit.existingModel.id in plantedSubzoneIds) {
        problems.add(
            PlantingSiteEditProblem.CannotRemovePlantedSubzone(
                subzoneEdit.existingModel.name, plantingZone.name))
      }
    }
  }

  private fun findExistingZone(desiredZone: AnyPlantingZoneModel): ExistingPlantingZoneModel? {
    val overlappingZones =
        existingSite.plantingZones.filter {
          it.boundary.coveragePercent(desiredZone.boundary) > 0.1
        }

    return if (overlappingZones.isEmpty()) {
      null
    } else if (overlappingZones.size == 1) {
      overlappingZones.first()
    } else {
      problems.add(
          PlantingSiteEditProblem.ZoneBoundaryChanged(
              overlappingZones.map { it.name }.toSet(), desiredZone.name))
      null
    }
  }

  private fun findExistingSubzone(
      existingZone: ExistingPlantingZoneModel,
      desiredZone: AnyPlantingZoneModel,
      desiredSubzone: AnyPlantingSubzoneModel
  ): ExistingPlantingSubzoneModel? {
    val overlappingSubzones =
        existingZone.plantingSubzones.filter {
          it.boundary.coveragePercent(desiredSubzone.boundary) > 0.1
        }

    return if (overlappingSubzones.isEmpty()) {
      null
    } else if (overlappingSubzones.size == 1) {
      overlappingSubzones.first()
    } else {
      problems.add(
          PlantingSiteEditProblem.SubzoneBoundaryChanged(
              overlappingSubzones.map { it.name }.toSet(), desiredSubzone.name, desiredZone.name))
      null
    }
  }

  private fun calculateAreaHaDifference(existing: Geometry?, desired: Geometry): BigDecimal {
    val desiredArea = desired.differenceNullable(desiredSite.exclusion).calculateAreaHectares()
    return if (existing != null) {
      desiredArea - existing.differenceNullable(existingSite.exclusion).calculateAreaHectares()
    } else {
      desiredArea
    }
  }

  private fun <A, B> findDuplicateValues(original: Map<A, B?>): Map<B, List<A>> {
    return original.entries
        .filter { it.value != null }
        .groupBy({ it.value!! }, { it.key })
        .filter { it.value.size > 1 }
  }

  private fun MultiPolygon.orEmpty(): MultiPolygon =
      if (isEmpty) factory.createMultiPolygon() else this

  private fun Geometry.toNormalizedMultiPolygon() = norm().toMultiPolygon().orEmpty()
}
