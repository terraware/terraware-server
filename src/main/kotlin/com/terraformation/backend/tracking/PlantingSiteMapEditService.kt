package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.PlantingSiteEditSummaryItem
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.edit.SubstratumEdit
import com.terraformation.backend.tracking.model.AnyPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteMapEditModel
import com.terraformation.backend.util.nearlyCoveredBy
import jakarta.inject.Named
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.TopologyException

@Named
class PlantingSiteMapEditService(
    private val entityLocker: EntityLocker,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
) {
  /**
   * Calculates the changes that would be needed to make a planting site's map match a version
   * edited by a user. This does not modify the site; it can be used as a dry run.
   *
   * @throws PlantingSiteMapInvalidException The edited map failed validation.
   * @throws IllegalArgumentException The edit was malformed or its geometries couldn't be compared.
   */
  fun calculateEdit(
      plantingSiteId: PlantingSiteId,
      mapEdit: PlantingSiteMapEditModel,
  ): PlantingSiteEdit {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    val existing = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    val desired =
        try {
          mapEdit.toDesiredModel(existing)
        } catch (e: TopologyException) {
          throw IllegalArgumentException("Planting site map has invalid geometry", e)
        }

    return calculateEdit(existing, desired)
  }

  /**
   * Edits a planting site's map to match a version edited by a user. The site is locked while the
   * edit is calculated and applied, so concurrent changes to the site can't be overwritten with
   * stale data.
   *
   * @param substrataToMarkIncomplete IDs of edited substrata whose planting completed flags should
   *   be cleared.
   * @throws PlantingSiteMapInvalidException The edited map failed validation. Nothing is modified.
   * @throws IllegalArgumentException The edit was malformed or its geometries couldn't be compared.
   */
  fun applyEdit(
      plantingSiteId: PlantingSiteId,
      mapEdit: PlantingSiteMapEditModel,
      substrataToMarkIncomplete: Set<SubstratumId> = emptySet(),
  ): ExistingPlantingSiteModel {
    return entityLocker.withLockedPlantingSite(plantingSiteId) {
      plantingSiteStore.applyPlantingSiteEdit(
          calculateEdit(plantingSiteId, mapEdit),
          substrataToMarkIncomplete,
      )
    }
  }

  /**
   * Calculates the changes that would be needed to make an existing planting site match a desired
   * version. This does not modify the site; it can be used as a dry run.
   *
   * @param validate If true, check that the desired site's map is valid.
   * @throws PlantingSiteMapInvalidException The desired map failed validation.
   */
  fun calculateEdit(
      existing: ExistingPlantingSiteModel,
      desired: AnyPlantingSiteModel,
      validate: Boolean = true,
  ): PlantingSiteEdit {
    if (validate) {
      desired.requireValid()
    }

    return PlantingSiteEditCalculator(existing, desired).calculateSiteEdit()
  }

  /**
   * Summarizes the changes a site edit would make, as a list of structured items.
   *
   * @param depth Only include items whose depth is at or above this level.
   */
  fun summarizeEdit(
      edit: PlantingSiteEdit,
      depth: PlantingSiteDepth,
  ): List<PlantingSiteEditSummaryItem> {
    val affectedObservationIds =
        observationStore.fetchActiveObservationIds(
            edit.existingModel.id,
            edit.stratumEdits.mapNotNull { it.existingModel?.id },
        )

    val items =
        listOfNotNull(
            if (affectedObservationIds.isNotEmpty()) {
              PlantingSiteEditSummaryItem.ObservationsAbandoned(affectedObservationIds)
            } else {
              null
            },
            PlantingSiteEditSummaryItem.SiteAreaChanged(edit.areaHaDifference),
        ) + edit.stratumEdits.flatMap { summarizeStratumEdit(it) }

    return items.filter { it.depth <= depth }
  }

  private fun summarizeStratumEdit(stratumEdit: StratumEdit): List<PlantingSiteEditSummaryItem> {
    val desiredModel = stratumEdit.desiredModel
    val existingModel = stratumEdit.existingModel
    val existingBoundary = existingModel?.boundary
    val stratumName = existingModel?.name ?: desiredModel!!.name

    val totalPermanent =
        stratumEdit.monitoringPlotEdits.size +
            stratumEdit.substratumEdits.sumOf { substratumEdit ->
              substratumEdit.monitoringPlotEdits.count { it.permanentIndex != null }
            }

    val stratumItem =
        if (existingModel != null) {
          if (desiredModel != null) {
            PlantingSiteEditSummaryItem.StratumUpdated(
                stratumName = stratumName,
                areaHaDifference = stratumEdit.areaHaDifference,
                overlapPercent = overlapPercent(existingModel.boundary, desiredModel.boundary),
                numPermanentPlots = totalPermanent,
            )
          } else {
            PlantingSiteEditSummaryItem.StratumDeleted(stratumName, existingModel.stableId)
          }
        } else {
          PlantingSiteEditSummaryItem.StratumCreated(
              stratumName = stratumName,
              stableId = desiredModel!!.stableId,
              numPermanentPlots = totalPermanent,
          )
        }

    val monitoringPlotCreations =
        stratumEdit.monitoringPlotEdits.map { plotEdit ->
          PlantingSiteEditSummaryItem.PermanentPlotCreated(
              stratumName = stratumName,
              substratumName = null,
              permanentIndex = plotEdit.permanentIndex,
              inExistingArea =
                  existingBoundary != null && plotEdit.region.nearlyCoveredBy(existingBoundary),
          )
        }

    val substratumItems =
        stratumEdit.substratumEdits.flatMap { summarizeSubstratumEdit(stratumName, it) }

    return listOf(stratumItem) + monitoringPlotCreations + substratumItems
  }

  private fun summarizeSubstratumEdit(
      stratumName: String,
      substratumEdit: SubstratumEdit,
  ): List<PlantingSiteEditSummaryItem> {
    val substratumName = substratumEdit.existingModel?.name ?: substratumEdit.desiredModel!!.name

    val substratumItem =
        when (substratumEdit) {
          is SubstratumEdit.Create ->
              PlantingSiteEditSummaryItem.SubstratumCreated(
                  stratumName,
                  substratumName,
                  substratumEdit.desiredModel.stableId,
              )
          is SubstratumEdit.Delete ->
              PlantingSiteEditSummaryItem.SubstratumDeleted(
                  stratumName,
                  substratumName,
                  substratumEdit.existingModel.stableId,
              )
          is SubstratumEdit.Update ->
              PlantingSiteEditSummaryItem.SubstratumUpdated(
                  stratumName = stratumName,
                  substratumName = substratumName,
                  areaHaDifference = substratumEdit.areaHaDifference,
                  overlapPercent =
                      overlapPercent(
                          substratumEdit.existingModel.boundary,
                          substratumEdit.desiredModel.boundary,
                      ),
              )
        }

    val monitoringPlotItems =
        substratumEdit.monitoringPlotEdits.map { plotEdit ->
          when (plotEdit) {
            is MonitoringPlotEdit.Create ->
                PlantingSiteEditSummaryItem.PermanentPlotCreated(
                    stratumName = stratumName,
                    substratumName = substratumName,
                    permanentIndex = plotEdit.permanentIndex,
                    inExistingArea = false,
                )
            is MonitoringPlotEdit.Adopt ->
                PlantingSiteEditSummaryItem.PlotAdopted(
                    stratumName,
                    substratumName,
                    plotEdit.monitoringPlotId,
                    plotEdit.permanentIndex,
                )
            is MonitoringPlotEdit.Eject ->
                PlantingSiteEditSummaryItem.PlotEjected(
                    stratumName,
                    substratumName,
                    plotEdit.monitoringPlotId,
                )
            is MonitoringPlotEdit.Accept ->
                PlantingSiteEditSummaryItem.PlotAccepted(
                    stratumName,
                    substratumName,
                    plotEdit.monitoringPlotId,
                    plotEdit.permanentIndex,
                )
          }
        }

    return listOf(substratumItem) + monitoringPlotItems
  }

  /** Returns the percentage of the desired geometry's area that overlaps the existing one. */
  private fun overlapPercent(existing: Geometry, desired: Geometry): Double {
    return existing.intersection(desired).area / desired.area * 100.0
  }
}
