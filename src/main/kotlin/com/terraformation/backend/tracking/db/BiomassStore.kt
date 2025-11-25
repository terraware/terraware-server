package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.tracking.event.BiomassDetailsCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class BiomassStore(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
) {
  fun insertBiomassDetails(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      model: NewBiomassDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    val plantingSiteId =
        parentStore.getPlantingSiteId(plotId) ?: throw PlotNotFoundException(plotId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId) ?: throw PlotNotFoundException(plotId)

    val (observationType, observationState) =
        with(OBSERVATION_PLOTS) {
          dslContext
              .select(
                  observations.OBSERVATION_TYPE_ID.asNonNullable(),
                  observations.STATE_ID.asNonNullable(),
              )
              .from(this)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(plotId))
              .fetchOne()
              ?: throw IllegalStateException(
                  "Plot $plotId is not part of observation $observationId"
              )
        }

    if (observationState == ObservationState.Completed) {
      throw IllegalStateException("Observation $observationId is already completed.")
    }

    if (observationType != ObservationType.BiomassMeasurements) {
      throw IllegalStateException("Observation $observationId is not a biomass measurement")
    }

    model.validate()

    dslContext.transaction { _ ->
      val observationBiomassDetailsRecord =
          ObservationBiomassDetailsRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  description = model.description,
                  forestTypeId = model.forestType,
                  smallTreesCountLow = model.smallTreeCountRange.first,
                  smallTreesCountHigh = model.smallTreeCountRange.second,
                  herbaceousCoverPercent = model.herbaceousCoverPercent,
                  soilAssessment = model.soilAssessment,
                  waterDepthCm =
                      if (model.forestType == BiomassForestType.Mangrove) model.waterDepthCm
                      else null,
                  salinityPpt =
                      if (model.forestType == BiomassForestType.Mangrove) model.salinityPpt
                      else null,
                  ph = if (model.forestType == BiomassForestType.Mangrove) model.ph else null,
                  tideId = if (model.forestType == BiomassForestType.Mangrove) model.tide else null,
                  tideTime =
                      if (model.forestType == BiomassForestType.Mangrove) model.tideTime else null,
              )
              .attach(dslContext)

      observationBiomassDetailsRecord.insert()

      eventPublisher.publishEvent(
          BiomassDetailsCreatedEvent(
              description = model.description,
              forestType = model.forestType,
              herbaceousCoverPercent = model.herbaceousCoverPercent,
              monitoringPlotId = plotId,
              observationId = observationId,
              organizationId = organizationId,
              ph = observationBiomassDetailsRecord.ph,
              plantingSiteId = plantingSiteId,
              salinityPpt = observationBiomassDetailsRecord.salinityPpt,
              smallTreesCountHigh = model.smallTreeCountRange.second,
              smallTreesCountLow = model.smallTreeCountRange.first,
              soilAssessment = model.soilAssessment,
              tide = observationBiomassDetailsRecord.tideId,
              tideTime = observationBiomassDetailsRecord.tideTime,
              waterDepthCm = observationBiomassDetailsRecord.waterDepthCm,
          )
      )

      model.species.forEach { speciesModel ->
        val record =
            ObservationBiomassSpeciesRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    commonName = speciesModel.commonName,
                    isInvasive = speciesModel.isInvasive,
                    isThreatened = speciesModel.isThreatened,
                    scientificName = speciesModel.scientificName,
                    speciesId = speciesModel.speciesId,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            BiomassSpeciesCreatedEvent(
                biomassSpeciesId = record.id!!,
                commonName = speciesModel.commonName,
                isInvasive = speciesModel.isInvasive,
                isThreatened = speciesModel.isThreatened,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                scientificName = speciesModel.scientificName,
                speciesId = speciesModel.speciesId,
            )
        )
      }

      val biomassSpeciesIdsBySpeciesIdentifiers =
          with(OBSERVATION_BIOMASS_SPECIES) {
            dslContext
                .select(ID.asNonNullable(), SCIENTIFIC_NAME, SPECIES_ID)
                .from(this)
                .where(OBSERVATION_ID.eq(observationId))
                .fetch()
                .associate {
                  BiomassSpeciesKey(it[SPECIES_ID], it[SCIENTIFIC_NAME]) to it[ID.asNonNullable()]
                }
          }

      model.quadrats.forEach { (position, details) ->
        val record =
            ObservationBiomassQuadratDetailsRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    positionId = position,
                    description = details.description,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            BiomassQuadratCreatedEvent(
                description = details.description,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                position = position,
            )
        )
      }

      val quadratSpeciesRecords =
          model.quadrats.flatMap { (position, details) ->
            details.species.map {
              ObservationBiomassQuadratSpeciesRecord(
                  observationId = observationId,
                  monitoringPlotId = plotId,
                  positionId = position,
                  biomassSpeciesId =
                      biomassSpeciesIdsBySpeciesIdentifiers[
                          BiomassSpeciesKey(it.speciesId, it.speciesName)]
                          ?: throw IllegalArgumentException(
                              "Biomass species ${it.speciesName ?: "#${it.speciesId}"} not found."
                          ),
                  abundancePercent = it.abundancePercent,
              )
            }
          }
      dslContext.batchInsert(quadratSpeciesRecords).execute()

      model.trees.forEach { treeModel ->
        val record =
            RecordedTreesRecord(
                    observationId = observationId,
                    monitoringPlotId = plotId,
                    biomassSpeciesId =
                        biomassSpeciesIdsBySpeciesIdentifiers[
                            BiomassSpeciesKey(treeModel.speciesId, treeModel.speciesName)]
                            ?: throw IllegalArgumentException(
                                "Biomass species ${treeModel.speciesName ?: "#${treeModel.speciesId}"} not found."
                            ),
                    treeNumber = treeModel.treeNumber,
                    trunkNumber = treeModel.trunkNumber,
                    treeGrowthFormId = treeModel.treeGrowthForm,
                    gpsCoordinates = treeModel.gpsCoordinates,
                    isDead = treeModel.isDead,
                    diameterAtBreastHeightCm =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.diameterAtBreastHeightCm
                        else null,
                    pointOfMeasurementM =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.pointOfMeasurementM
                        else null,
                    heightM =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.heightM
                        else null,
                    shrubDiameterCm =
                        if (treeModel.treeGrowthForm == TreeGrowthForm.Shrub)
                            treeModel.shrubDiameterCm
                        else null,
                    description = treeModel.description,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            RecordedTreeCreatedEvent(
                biomassSpeciesId = record.biomassSpeciesId!!,
                description = treeModel.description,
                diameterAtBreastHeightCm = record.diameterAtBreastHeightCm,
                gpsCoordinates = treeModel.gpsCoordinates,
                heightM = record.heightM,
                isDead = treeModel.isDead,
                monitoringPlotId = plotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                pointOfMeasurementM = record.pointOfMeasurementM,
                recordedTreeId = record.id!!,
                shrubDiameterCm = record.shrubDiameterCm,
                speciesId = treeModel.speciesId,
                speciesName = treeModel.speciesName,
                treeGrowthForm = treeModel.treeGrowthForm,
                treeNumber = treeModel.treeNumber,
                trunkNumber = treeModel.trunkNumber,
            )
        )
      }
    }
  }
}
