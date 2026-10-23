package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassAdditionalSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratDetailsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassQuadratSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationBiomassSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedTreesRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_ADDITIONAL_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.event.BiomassDetailsCreatedEvent
import com.terraformation.backend.tracking.event.BiomassDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratDetailsUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesUpdatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesDeletedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeDeletedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeUpdatedEventValues
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.EditableBiomassDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratDetailsModel
import com.terraformation.backend.tracking.model.EditableBiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.EditableBiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import com.terraformation.backend.tracking.model.RecordedTreeModel
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class BiomassStore(
    private val dslContext: DSLContext,
    private val entityLocker: EntityLocker,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()

  fun fetchRecordedTree(
      observationId: ObservationId,
      recordedTreeId: RecordedTreeId,
  ): ExistingRecordedTreeModel {
    requirePermissions { readObservation(observationId) }

    return with(RECORDED_TREES) {
      dslContext
          .select(
              ID,
              BOLE_HEIGHT_M,
              DESCRIPTION,
              DIAMETER_AT_BREAST_HEIGHT_CM,
              GPS_COORDINATES,
              HEIGHT_M,
              IS_DEAD,
              POINT_OF_MEASUREMENT_M,
              SHRUB_DIAMETER_CM,
              recordedTreesBiomassSpeciesIdFkey.SPECIES_ID,
              recordedTreesBiomassSpeciesIdFkey.SCIENTIFIC_NAME,
              TREE_CROWN_DIAMETER_CM,
              TREE_GROWTH_FORM_ID,
              TREE_NUMBER,
              TRUNK_NUMBER,
          )
          .from(RECORDED_TREES)
          .where(ID.eq(recordedTreeId))
          .and(OBSERVATION_ID.eq(observationId))
          .fetchOne { RecordedTreeModel.of(it) }
          ?: throw RecordedTreeNotFoundException(recordedTreeId)
    }
  }

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
                  soilTypeId = model.soilType,
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
                  nextTreeNumber = (model.trees.maxOfOrNull { it.treeNumber } ?: 0) + 1,
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
              soilType = model.soilType,
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
                speciesName = getBiomassSpeciesName(record.speciesId, record.scientificName),
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

      val measuredSpeciesKeys =
          model.trees.map { BiomassSpeciesKey(it.speciesId, it.speciesName) }.toSet() +
              model.quadrats.values.flatMap { quadrat ->
                quadrat.species.map { BiomassSpeciesKey(it.speciesId, it.speciesName) }
              }
      val additionalSpeciesRecords =
          model.species
              .map { BiomassSpeciesKey(it.speciesId, it.scientificName) }
              .filter { it !in measuredSpeciesKeys }
              .map { key ->
                ObservationBiomassAdditionalSpeciesRecord(
                    biomassSpeciesId = biomassSpeciesIdsBySpeciesIdentifiers[key],
                    monitoringPlotId = plotId,
                    observationId = observationId,
                )
              }
      if (additionalSpeciesRecords.isNotEmpty()) {
        dslContext.batchInsert(additionalSpeciesRecords).execute()
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
                  abundanceCount = it.abundanceCount,
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
                    boleHeightM = treeModel.boleHeightM,
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
                    treeCrownDiameterCm =
                        if (
                            treeModel.treeGrowthForm == TreeGrowthForm.Tree ||
                                treeModel.treeGrowthForm == TreeGrowthForm.Trunk
                        )
                            treeModel.treeCrownDiameterCm
                        else null,
                    description = treeModel.description,
                )
                .attach(dslContext)

        record.insert()

        eventPublisher.publishEvent(
            RecordedTreeCreatedEvent(
                biomassSpeciesId = record.biomassSpeciesId!!,
                boleHeightM = record.boleHeightM,
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
                treeCrownDiameterCm = record.treeCrownDiameterCm,
                treeGrowthForm = treeModel.treeGrowthForm,
                treeNumber = treeModel.treeNumber,
                trunkNumber = treeModel.trunkNumber,
            )
        )
      }
    }
  }

  fun createRecordedTree(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      model: NewRecordedTreeModel,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }
    require((model.speciesId == null) xor (model.speciesName == null)) {
      "One of species ID or scientific name must be specified"
    }
    require(model.treeGrowthForm != TreeGrowthForm.Trunk) {
      "Trunk growth form is not allowed for a new recorded tree"
    }

    val plantingSiteId =
        parentStore.getPlantingSiteId(monitoringPlotId)
            ?: throw PlotNotFoundException(monitoringPlotId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw PlotNotFoundException(monitoringPlotId)

    entityLocker.withLockedObservation(observationId) { _ ->
      val isObservationPlot =
          dslContext.fetchExists(
              OBSERVATION_PLOTS,
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      if (!isObservationPlot) {
        throw ObservationPlotNotFoundException(observationId, monitoringPlotId)
      }

      val treeNumber =
          dslContext
              .select(OBSERVATION_BIOMASS_DETAILS.NEXT_TREE_NUMBER)
              .from(OBSERVATION_BIOMASS_DETAILS)
              .where(
                  OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                      .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
              )
              .fetchOne()
              ?.value1() ?: throw ObservationPlotNotFoundException(observationId, monitoringPlotId)

      val newTree = model.copy(treeNumber = treeNumber, trunkNumber = 1)
      newTree.validate()

      val biomassSpeciesId =
          getOrCreateMeasuredBiomassSpecies(
              observationId,
              monitoringPlotId,
              newTree.speciesId,
              newTree.speciesName,
          )

      dslContext
          .update(OBSERVATION_BIOMASS_DETAILS)
          .set(OBSERVATION_BIOMASS_DETAILS.NEXT_TREE_NUMBER, treeNumber + 1)
          .where(
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
          )
          .execute()

      val record =
          RecordedTreesRecord(
                  biomassSpeciesId = biomassSpeciesId,
                  boleHeightM = newTree.boleHeightM,
                  description = newTree.description,
                  diameterAtBreastHeightCm = newTree.diameterAtBreastHeightCm,
                  gpsCoordinates = newTree.gpsCoordinates,
                  heightM = newTree.heightM,
                  isDead = newTree.isDead,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  pointOfMeasurementM = newTree.pointOfMeasurementM,
                  shrubDiameterCm = newTree.shrubDiameterCm,
                  treeCrownDiameterCm = newTree.treeCrownDiameterCm,
                  treeGrowthFormId = newTree.treeGrowthForm,
                  treeNumber = newTree.treeNumber,
                  trunkNumber = newTree.trunkNumber,
              )
              .attach(dslContext)
      record.insert()

      eventPublisher.publishEvent(
          RecordedTreeCreatedEvent(
              biomassSpeciesId = biomassSpeciesId,
              boleHeightM = newTree.boleHeightM,
              description = newTree.description,
              diameterAtBreastHeightCm = newTree.diameterAtBreastHeightCm,
              gpsCoordinates = newTree.gpsCoordinates,
              heightM = newTree.heightM,
              isDead = newTree.isDead,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = plantingSiteId,
              pointOfMeasurementM = newTree.pointOfMeasurementM,
              recordedTreeId = record.id!!,
              shrubDiameterCm = newTree.shrubDiameterCm,
              speciesId = newTree.speciesId,
              speciesName = newTree.speciesName,
              treeCrownDiameterCm = newTree.treeCrownDiameterCm,
              treeGrowthForm = newTree.treeGrowthForm,
              treeNumber = newTree.treeNumber,
              trunkNumber = newTree.trunkNumber,
          )
      )
    }
  }

  @Deprecated("Call this on ObservationService instead.")
  fun mergeOtherSpecies(
      observationId: ObservationId,
      otherSpeciesName: String,
      speciesId: SpeciesId,
  ) {

    val otherBiomassSpeciesId =
        with(OBSERVATION_BIOMASS_SPECIES) {
          dslContext.fetchValue(
              ID,
              SCIENTIFIC_NAME.eq(otherSpeciesName).and(OBSERVATION_ID.eq(observationId)),
          )
        }

    if (otherBiomassSpeciesId == null) {
      log.warn(
          "Biomass observation $observationId does not contain species name $otherSpeciesName; " +
              "merge is a no-op"
      )
      return
    }

    val targetBiomassSpeciesId =
        with(OBSERVATION_BIOMASS_SPECIES) {
          dslContext.fetchValue(ID, SPECIES_ID.eq(speciesId).and(OBSERVATION_ID.eq(observationId)))
        }

    if (targetBiomassSpeciesId == null) {
      // The target species wasn't present at all in the observation, so there's no need to merge
      // anything: we can just update the biomass species to point to the target species ID instead
      // of using a name.
      with(OBSERVATION_BIOMASS_SPECIES) {
        dslContext
            .update(OBSERVATION_BIOMASS_SPECIES)
            .set(SPECIES_ID, speciesId)
            .setNull(SCIENTIFIC_NAME)
            .where(ID.eq(otherBiomassSpeciesId))
            .execute()
      }

      return
    }

    // Recorded trees are a simple replacement of the biomass species ID.
    with(RECORDED_TREES) {
      dslContext
          .update(RECORDED_TREES)
          .set(BIOMASS_SPECIES_ID, targetBiomassSpeciesId)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .execute()
    }

    // For quadrat species, we need to add the abundance square counts of the numbered species and
    // the "Other" one if both exist in the quadrat. If only the "Other" one exists, then we can
    // just switch its biomass species ID in place. The total is capped at 25.
    with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
      val quadratSpeciesTable2 = OBSERVATION_BIOMASS_QUADRAT_SPECIES.`as`("quadrat2")

      dslContext
          .update(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .set(BIOMASS_SPECIES_ID, targetBiomassSpeciesId)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .andNotExists(
              DSL.selectOne()
                  .from(quadratSpeciesTable2)
                  .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
                  .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
          )
          .execute()

      dslContext
          .update(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .set(
              ABUNDANCE_COUNT,
              DSL.least(
                  ABUNDANCE_COUNT.plus(
                      DSL.field(
                          DSL.select(quadratSpeciesTable2.ABUNDANCE_COUNT)
                              .from(quadratSpeciesTable2)
                              .where(
                                  quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId)
                              )
                              .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
                      )
                  ),
                  DSL.value(25),
              ),
          )
          .where(BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
          .andExists(
              DSL.selectOne()
                  .from(quadratSpeciesTable2)
                  .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
                  .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
          )
          .execute()
    }

    // Invasive and threatened should be true if they're true on either version of the species.
    with(OBSERVATION_BIOMASS_SPECIES) {
      dslContext
          .update(OBSERVATION_BIOMASS_SPECIES)
          .set(
              IS_INVASIVE,
              DSL.select(DSL.boolOr(IS_INVASIVE))
                  .from(OBSERVATION_BIOMASS_SPECIES)
                  .where(ID.`in`(otherBiomassSpeciesId, targetBiomassSpeciesId)),
          )
          .set(
              IS_THREATENED,
              DSL.select(DSL.boolOr(IS_THREATENED))
                  .from(OBSERVATION_BIOMASS_SPECIES)
                  .where(ID.`in`(otherBiomassSpeciesId, targetBiomassSpeciesId)),
          )
          .where(ID.eq(targetBiomassSpeciesId))
          .execute()
    }

    // Remove remaining quadrat rows for the "Other" species.
    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
        .where(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
        .execute()

    // The target species is additional in each plot where either version of the species was
    // additional, unless the target species has measurements (trees or quadrat abundance) in
    // the plot.
    with(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES) {
      val additionalSpeciesTable2 = OBSERVATION_BIOMASS_ADDITIONAL_SPECIES.`as`("additional2")

      dslContext
          .update(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES)
          .set(BIOMASS_SPECIES_ID, targetBiomassSpeciesId)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .andNotExists(
              DSL.selectOne()
                  .from(additionalSpeciesTable2)
                  .where(additionalSpeciesTable2.BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
                  .and(additionalSpeciesTable2.OBSERVATION_ID.eq(OBSERVATION_ID))
                  .and(additionalSpeciesTable2.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
          )
          .execute()

      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES)
          .where(BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
          .execute()

      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES)
          .where(BIOMASS_SPECIES_ID.eq(targetBiomassSpeciesId))
          .and(
              DSL.exists(
                      DSL.selectOne()
                          .from(RECORDED_TREES)
                          .where(RECORDED_TREES.BIOMASS_SPECIES_ID.eq(BIOMASS_SPECIES_ID))
                          .and(RECORDED_TREES.OBSERVATION_ID.eq(OBSERVATION_ID))
                          .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
                  )
                  .or(
                      DSL.exists(
                          DSL.selectOne()
                              .from(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
                              .where(
                                  OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(
                                      BIOMASS_SPECIES_ID
                                  )
                              )
                              .and(
                                  OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(
                                      OBSERVATION_ID
                                  )
                              )
                              .and(
                                  OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID.eq(
                                      MONITORING_PLOT_ID
                                  )
                              )
                      )
                  )
          )
          .execute()
    }

    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
        .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(otherBiomassSpeciesId))
        .execute()
  }

  fun updateBiomassDetails(
      observationId: ObservationId,
      plotId: MonitoringPlotId,
      updateFunc: (EditableBiomassDetailsModel) -> EditableBiomassDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    entityLocker.withLockedObservation(observationId) { _ ->
      val existing =
          with(OBSERVATION_BIOMASS_DETAILS) {
            dslContext
                .selectFrom(OBSERVATION_BIOMASS_DETAILS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(plotId))
                .fetchOne { EditableBiomassDetailsModel.of(it) }
                ?: throw ObservationPlotNotFoundException(observationId, plotId)
          }

      val updated = updateFunc(existing).sanitizeForForestType()

      val changedFrom = existing.toEventValues(updated)
      val changedTo = updated.toEventValues(existing)

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_DETAILS) {
          dslContext
              .update(OBSERVATION_BIOMASS_DETAILS)
              .set(DESCRIPTION, updated.description)
              .set(FOREST_TYPE_ID, updated.forestType)
              .set(HERBACEOUS_COVER_PERCENT, updated.herbaceousCoverPercent)
              .set(PH, updated.ph)
              .set(SALINITY_PPT, updated.salinityPpt)
              .set(SMALL_TREES_COUNT_LOW, updated.smallTreeCountRange.first)
              .set(SMALL_TREES_COUNT_HIGH, updated.smallTreeCountRange.second)
              .set(SOIL_ASSESSMENT, updated.soilAssessment)
              .set(SOIL_TYPE_ID, updated.soilType)
              .set(TIDE_ID, updated.tide)
              .set(TIDE_TIME, updated.tideTime)
              .set(WATER_DEPTH_CM, updated.waterDepthCm)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(plotId))
              .execute()

          val (plantingSiteId, organizationId) =
              dslContext
                  .select(
                      monitoringPlots.plantingSites.ID.asNonNullable(),
                      monitoringPlots.plantingSites.ORGANIZATION_ID.asNonNullable(),
                  )
                  .from(OBSERVATION_BIOMASS_DETAILS)
                  .where(OBSERVATION_ID.eq(observationId))
                  .and(MONITORING_PLOT_ID.eq(plotId))
                  .fetchSingle()

          eventPublisher.publishEvent(
              BiomassDetailsUpdatedEvent(
                  changedFrom = changedFrom,
                  changedTo = changedTo,
                  monitoringPlotId = plotId,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = plantingSiteId,
              )
          )
        }
      }
    }
  }

  fun updateBiomassSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId? = null,
      scientificName: String? = null,
      updateFunc: (EditableBiomassSpeciesModel) -> EditableBiomassSpeciesModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    if (
        speciesId == null && scientificName == null || speciesId != null && scientificName != null
    ) {
      throw IllegalArgumentException("One of species ID or scientific name must be specified")
    }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    entityLocker.withLockedObservation(observationId) { _ ->
      val biomassSpeciesRecord =
          fetchBiomassSpecies(speciesId, scientificName, observationId, monitoringPlotId)

      val biomassSpeciesId = biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.ID]!!

      val existing = EditableBiomassSpeciesModel.of(biomassSpeciesRecord)
      val updated = updateFunc(existing)

      val changedFrom = existing.toEventValues(updated)
      val changedTo = updated.toEventValues(existing)

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_SPECIES) {
          dslContext
              .update(OBSERVATION_BIOMASS_SPECIES)
              .set(IS_INVASIVE, updated.isInvasive)
              .set(IS_THREATENED, updated.isThreatened)
              .where(ID.eq(biomassSpeciesId))
              .execute()
        }

        eventPublisher.publishEvent(
            BiomassSpeciesUpdatedEvent(
                changedFrom = changedFrom,
                changedTo = changedTo,
                biomassSpeciesId = biomassSpeciesId,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = plantingSiteId,
                speciesId = biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                speciesName =
                    getBiomassSpeciesName(
                        biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                        biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                    ),
            )
        )
      }
    }
  }

  fun createBiomassSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId?,
      scientificName: String?,
      commonName: String?,
      isInvasive: Boolean,
      isThreatened: Boolean,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }
    require((speciesId == null) xor (scientificName == null)) {
      "One of species ID or scientific name must be specified"
    }

    entityLocker.withLockedObservation(observationId) { _ ->
      val isObservationPlot =
          dslContext.fetchExists(
              OBSERVATION_PLOTS,
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      if (!isObservationPlot) {
        throw ObservationPlotNotFoundException(observationId, monitoringPlotId)
      }

      val hasBiomassDetails =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_DETAILS,
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      if (!hasBiomassDetails) {
        throw ObservationPlotNotFoundException(observationId, monitoringPlotId)
      }

      val organizationId =
          parentStore.getOrganizationId(observationId)
              ?: throw ObservationNotFoundException(observationId)
      if (speciesId != null && parentStore.getOrganizationId(speciesId) != organizationId) {
        throw SpeciesNotFoundException(speciesId)
      }

      val speciesCondition =
          speciesId?.let { OBSERVATION_BIOMASS_SPECIES.SPECIES_ID.eq(it) }
              ?: OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME.eq(scientificName)
      val alreadyExists =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_SPECIES,
              OBSERVATION_BIOMASS_SPECIES.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_SPECIES.MONITORING_PLOT_ID.eq(monitoringPlotId))
                  .and(speciesCondition),
          )
      if (alreadyExists) {
        throw IllegalArgumentException("Biomass species already exists in observation plot")
      }

      val biomassSpeciesId =
          insertBiomassSpecies(
              observationId,
              monitoringPlotId,
              speciesId,
              scientificName,
              commonName,
              isInvasive,
              isThreatened,
              organizationId = organizationId,
          )

      with(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES) {
        dslContext
            .insertInto(this)
            .set(BIOMASS_SPECIES_ID, biomassSpeciesId)
            .set(MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVATION_ID, observationId)
            .execute()
      }
    }
  }

  fun deleteBiomassSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId?,
      scientificName: String?,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }
    require((speciesId == null) xor (scientificName == null)) {
      "One of species ID or scientific name must be specified"
    }

    entityLocker.withLockedObservation(observationId) { _ ->
      val biomassSpecies =
          fetchBiomassSpecies(speciesId, scientificName, observationId, monitoringPlotId)
      val biomassSpeciesId = biomassSpecies.id!!
      val hasRecordedTrees =
          dslContext.fetchExists(
              RECORDED_TREES,
              RECORDED_TREES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId),
          )
      val hasQuadratSpecies =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_QUADRAT_SPECIES,
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId),
          )
      if (hasRecordedTrees || hasQuadratSpecies) {
        throw IllegalArgumentException(
            "Biomass species is still referenced by trees or quadrat entries"
        )
      }

      deleteBiomassSpeciesRow(biomassSpecies, isImplicit = false)
    }
  }

  fun updateBiomassQuadratDetails(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition,
      updateFunc: (EditableBiomassQuadratDetailsModel) -> EditableBiomassQuadratDetailsModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    entityLocker.withLockedObservation(observationId) { _ ->
      val existing =
          with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
            dslContext
                .select(DESCRIPTION)
                .from(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(POSITION_ID.eq(position))
                .fetchOne { EditableBiomassQuadratDetailsModel.of(it) }
          }

      val editable = existing ?: EditableBiomassQuadratDetailsModel()
      val updated = updateFunc(editable)

      val changedFrom = editable.toEventValues(updated)
      val changedTo = updated.toEventValues(editable)

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
          if (existing == null) {
            dslContext
                .insertInto(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .set(DESCRIPTION, updated.description)
                .set(MONITORING_PLOT_ID, monitoringPlotId)
                .set(OBSERVATION_ID, observationId)
                .set(POSITION_ID, position)
                .execute()

            eventPublisher.publishEvent(
                BiomassQuadratCreatedEvent(
                    updated.description,
                    monitoringPlotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                    position,
                )
            )
          } else {
            dslContext
                .update(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
                .set(DESCRIPTION, updated.description)
                .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(OBSERVATION_ID.eq(observationId))
                .and(POSITION_ID.eq(position))
                .execute()

            eventPublisher.publishEvent(
                BiomassQuadratDetailsUpdatedEvent(
                    changedFrom,
                    changedTo,
                    monitoringPlotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                    position,
                )
            )
          }
        }
      }
    }
  }

  fun updateBiomassQuadratSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition,
      speciesId: SpeciesId?,
      scientificName: String?,
      updateFunc: (EditableBiomassQuadratSpeciesModel) -> EditableBiomassQuadratSpeciesModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    if (
        speciesId == null && scientificName == null || speciesId != null && scientificName != null
    ) {
      throw IllegalArgumentException("One of species ID or scientific name must be specified")
    }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    entityLocker.withLockedObservation(observationId) { observation ->
      val biomassSpeciesRecord =
          fetchBiomassSpecies(speciesId, scientificName, observationId, monitoringPlotId)

      val biomassSpeciesId = biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.ID]!!

      val existing =
          with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
            dslContext
                .select(ABUNDANCE_COUNT)
                .from(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
                .where(OBSERVATION_ID.eq(observationId))
                .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                .and(POSITION_ID.eq(position))
                .and(BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
                .fetchOne { EditableBiomassQuadratSpeciesModel.of(it) }
                ?: throw BiomassSpeciesNotFoundException(speciesId, scientificName)
          }

      val updated = updateFunc(existing)

      val changedFrom = existing.toEventValues(updated)
      val changedTo = updated.toEventValues(existing)

      if (changedFrom != changedTo) {
        with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
          dslContext
              .update(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
              .set(ABUNDANCE_COUNT, updated.abundance)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
              .and(POSITION_ID.eq(position))
              .and(BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
              .execute()
        }

        eventPublisher.publishEvent(
            BiomassQuadratSpeciesUpdatedEvent(
                biomassSpeciesId = biomassSpeciesId,
                changedFrom = changedFrom,
                changedTo = changedTo,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = observation.plantingSiteId,
                position = position,
                speciesId = biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                speciesName =
                    getBiomassSpeciesName(
                        biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                        biomassSpeciesRecord[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                    ),
            )
        )
      }
    }
  }

  fun createBiomassQuadratSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition,
      speciesId: SpeciesId?,
      scientificName: String?,
      abundance: Int,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }

    if (
        speciesId == null && scientificName == null || speciesId != null && scientificName != null
    ) {
      throw IllegalArgumentException("One of species ID or scientific name must be specified")
    }
    require(abundance in 0..25) { "Abundance must be between 0 and 25" }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    entityLocker.withLockedObservation(observationId) { observation ->
      val isObservationPlot =
          dslContext.fetchExists(
              OBSERVATION_PLOTS,
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      if (!isObservationPlot) {
        throw ObservationPlotNotFoundException(observationId, monitoringPlotId)
      }

      val hasBiomassDetails =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_DETAILS,
              OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId)),
          )
      if (!hasBiomassDetails) {
        throw ObservationPlotNotFoundException(observationId, monitoringPlotId)
      }

      val biomassSpeciesId =
          getOrCreateMeasuredBiomassSpecies(
              observationId,
              monitoringPlotId,
              speciesId,
              scientificName,
          )
      val hasQuadratDetails =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_QUADRAT_DETAILS,
              OBSERVATION_BIOMASS_QUADRAT_DETAILS.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_QUADRAT_DETAILS.MONITORING_PLOT_ID.eq(monitoringPlotId))
                  .and(OBSERVATION_BIOMASS_QUADRAT_DETAILS.POSITION_ID.eq(position)),
          )
      if (!hasQuadratDetails) {
        dslContext
            .insertInto(OBSERVATION_BIOMASS_QUADRAT_DETAILS)
            .set(OBSERVATION_BIOMASS_QUADRAT_DETAILS.DESCRIPTION, null as String?)
            .set(OBSERVATION_BIOMASS_QUADRAT_DETAILS.MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVATION_BIOMASS_QUADRAT_DETAILS.OBSERVATION_ID, observationId)
            .set(OBSERVATION_BIOMASS_QUADRAT_DETAILS.POSITION_ID, position)
            .execute()

        eventPublisher.publishEvent(
            BiomassQuadratCreatedEvent(
                description = null,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = observation.plantingSiteId,
                position = position,
            )
        )
      }

      val alreadyExists =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_QUADRAT_SPECIES,
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID.eq(monitoringPlotId))
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.POSITION_ID.eq(position))
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId)),
          )
      if (alreadyExists) {
        throw IllegalArgumentException("Biomass species is already present in quadrat")
      }

      dslContext
          .insertInto(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .set(OBSERVATION_BIOMASS_QUADRAT_SPECIES.ABUNDANCE_COUNT, abundance)
          .set(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID, biomassSpeciesId)
          .set(OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID, monitoringPlotId)
          .set(OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID, observationId)
          .set(OBSERVATION_BIOMASS_QUADRAT_SPECIES.POSITION_ID, position)
          .execute()

      eventPublisher.publishEvent(
          BiomassQuadratSpeciesCreatedEvent(
              abundance = abundance,
              biomassSpeciesId = biomassSpeciesId,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = observation.plantingSiteId,
              position = position,
              speciesId = speciesId,
              speciesName = getBiomassSpeciesName(speciesId, scientificName),
          )
      )
    }
  }

  fun deleteBiomassQuadratSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition,
      speciesId: SpeciesId?,
      scientificName: String?,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }

    if (
        speciesId == null && scientificName == null || speciesId != null && scientificName != null
    ) {
      throw IllegalArgumentException("One of species ID or scientific name must be specified")
    }

    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    entityLocker.withLockedObservation(observationId) { observation ->
      val biomassSpecies =
          fetchBiomassSpecies(speciesId, scientificName, observationId, monitoringPlotId)
      val biomassSpeciesId = biomassSpecies[OBSERVATION_BIOMASS_SPECIES.ID]!!
      val exists =
          dslContext.fetchExists(
              OBSERVATION_BIOMASS_QUADRAT_SPECIES,
              OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(observationId)
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID.eq(monitoringPlotId))
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.POSITION_ID.eq(position))
                  .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId)),
          )
      if (!exists) {
        throw BiomassSpeciesNotFoundException(speciesId, scientificName)
      }

      dslContext
          .deleteFrom(OBSERVATION_BIOMASS_QUADRAT_SPECIES)
          .where(OBSERVATION_BIOMASS_QUADRAT_SPECIES.OBSERVATION_ID.eq(observationId))
          .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.MONITORING_PLOT_ID.eq(monitoringPlotId))
          .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.POSITION_ID.eq(position))
          .and(OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
          .execute()

      eventPublisher.publishEvent(
          BiomassQuadratSpeciesDeletedEvent(
              biomassSpeciesId = biomassSpeciesId,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = observation.plantingSiteId,
              position = position,
              speciesId = biomassSpecies[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
              speciesName =
                  getBiomassSpeciesName(
                      biomassSpecies[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                      biomassSpecies[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                  ),
          )
      )

      deleteBiomassSpeciesIfUnreferenced(biomassSpeciesId)
    }
  }

  fun updateRecordedTree(
      observationId: ObservationId,
      recordedTreeId: RecordedTreeId,
      updateFunc: (ExistingRecordedTreeModel) -> ExistingRecordedTreeModel,
  ) {
    requirePermissions { updateObservation(observationId) }

    entityLocker.withLockedObservation(observationId) { observation ->
      val existing = fetchRecordedTree(observationId, recordedTreeId)
      val updated = updateFunc(existing)
      validateRecordedTreeUpdate(existing, updated)

      val changedFrom = existing.toEventValues(updated)
      val changedTo = updated.toEventValues(existing)

      if (changedFrom != changedTo) {
        with(RECORDED_TREES) {
          dslContext
              .update(RECORDED_TREES)
              .set(BOLE_HEIGHT_M, updated.boleHeightM)
              .set(DESCRIPTION, updated.description)
              .set(DIAMETER_AT_BREAST_HEIGHT_CM, updated.diameterAtBreastHeightCm)
              .set(HEIGHT_M, updated.heightM)
              .set(IS_DEAD, updated.isDead)
              .set(POINT_OF_MEASUREMENT_M, updated.pointOfMeasurementM)
              .set(SHRUB_DIAMETER_CM, updated.shrubDiameterCm)
              .set(TREE_CROWN_DIAMETER_CM, updated.treeCrownDiameterCm)
              .where(ID.eq(recordedTreeId))
              .execute()

          val (monitoringPlotId, organizationId) =
              dslContext
                  .select(
                      MONITORING_PLOT_ID.asNonNullable(),
                      monitoringPlots.plantingSites.ORGANIZATION_ID.asNonNullable(),
                  )
                  .from(RECORDED_TREES)
                  .where(ID.eq(recordedTreeId))
                  .fetchSingle()

          eventPublisher.publishEvent(
              RecordedTreeUpdatedEvent(
                  changedFrom = changedFrom,
                  changedTo = changedTo,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = observation.plantingSiteId,
                  recordedTreeId = recordedTreeId,
              )
          )
        }
      }
    }
  }

  fun changeRecordedTreeSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      recordedTreeId: RecordedTreeId,
      speciesId: SpeciesId?,
      scientificName: String?,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }
    require((speciesId == null) xor (scientificName == null)) {
      "One of species ID or scientific name must be specified"
    }

    entityLocker.withLockedObservation(observationId) { observation ->
      val recordedTree =
          dslContext
              .selectFrom(RECORDED_TREES)
              .where(RECORDED_TREES.ID.eq(recordedTreeId))
              .and(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
              .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetchOne() ?: throw RecordedTreeNotFoundException(recordedTreeId)
      val oldBiomassSpeciesId =
          recordedTree.biomassSpeciesId
              ?: throw IllegalStateException("Recorded tree $recordedTreeId has no species")
      val organizationId =
          parentStore.getOrganizationId(observationId)
              ?: throw ObservationNotFoundException(observationId)
      val newBiomassSpeciesId =
          getOrCreateMeasuredBiomassSpecies(
              observationId,
              monitoringPlotId,
              speciesId,
              scientificName,
          )
      if (newBiomassSpeciesId == oldBiomassSpeciesId) {
        return@withLockedObservation
      }

      val oldSpecies =
          dslContext
              .selectFrom(OBSERVATION_BIOMASS_SPECIES)
              .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(oldBiomassSpeciesId))
              .fetchOne()
              ?: throw IllegalStateException("Biomass species $oldBiomassSpeciesId not found")
      val changedFrom =
          RecordedTreeUpdatedEventValues(
              biomassSpeciesId = oldBiomassSpeciesId,
              speciesId = oldSpecies.speciesId,
              speciesName = getBiomassSpeciesName(oldSpecies.speciesId, oldSpecies.scientificName),
          )
      val changedTo =
          RecordedTreeUpdatedEventValues(
              biomassSpeciesId = newBiomassSpeciesId,
              speciesId = speciesId,
              speciesName = getBiomassSpeciesName(speciesId, scientificName),
          )
      val recordedTreeIds =
          dslContext
              .select(RECORDED_TREES.ID)
              .from(RECORDED_TREES)
              .where(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
              .and(RECORDED_TREES.TREE_NUMBER.eq(recordedTree.treeNumber))
              .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .orderBy(RECORDED_TREES.TRUNK_NUMBER)
              .fetch(RECORDED_TREES.ID.asNonNullable())

      dslContext
          .update(RECORDED_TREES)
          .set(RECORDED_TREES.BIOMASS_SPECIES_ID, newBiomassSpeciesId)
          .where(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
          .and(RECORDED_TREES.TREE_NUMBER.eq(recordedTree.treeNumber))
          .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
          .execute()

      recordedTreeIds.forEach { id ->
        eventPublisher.publishEvent(
            RecordedTreeUpdatedEvent(
                changedFrom = changedFrom,
                changedTo = changedTo,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                organizationId = organizationId,
                plantingSiteId = observation.plantingSiteId,
                recordedTreeId = id,
            )
        )
      }

      deleteBiomassSpeciesIfUnreferenced(oldBiomassSpeciesId)
    }
  }

  fun deleteRecordedTree(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      recordedTreeId: RecordedTreeId,
  ) {
    requirePermissions { updateObservationQuantities(observationId) }

    entityLocker.withLockedObservation(observationId) { _ ->
      val recordedTree =
          dslContext
              .selectFrom(RECORDED_TREES)
              .where(RECORDED_TREES.ID.eq(recordedTreeId))
              .and(RECORDED_TREES.OBSERVATION_ID.eq(observationId))
              .and(RECORDED_TREES.MONITORING_PLOT_ID.eq(monitoringPlotId))
              .fetchOne() ?: throw RecordedTreeNotFoundException(recordedTreeId)
      val biomassSpecies =
          dslContext
              .selectFrom(OBSERVATION_BIOMASS_SPECIES)
              .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(recordedTree.biomassSpeciesId))
              .fetchOne()
              ?: throw IllegalStateException(
                  "Biomass species ${recordedTree.biomassSpeciesId} not found"
              )
      val organizationId =
          parentStore.getOrganizationId(observationId)
              ?: throw ObservationNotFoundException(observationId)
      val plantingSiteId =
          parentStore.getPlantingSiteId(observationId)
              ?: throw ObservationNotFoundException(observationId)

      dslContext.deleteFrom(RECORDED_TREES).where(RECORDED_TREES.ID.eq(recordedTreeId)).execute()

      eventPublisher.publishEvent(
          RecordedTreeDeletedEvent(
              biomassSpeciesId = recordedTree.biomassSpeciesId!!,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = plantingSiteId,
              recordedTreeId = recordedTreeId,
              speciesId = biomassSpecies.speciesId,
              speciesName =
                  getBiomassSpeciesName(biomassSpecies.speciesId, biomassSpecies.scientificName),
              treeGrowthForm = recordedTree.treeGrowthFormId!!,
              treeNumber = recordedTree.treeNumber!!,
              trunkNumber = recordedTree.trunkNumber!!,
          )
      )

      deleteBiomassSpeciesIfUnreferenced(recordedTree.biomassSpeciesId!!)
    }
  }

  private fun fetchBiomassSpecies(
      speciesId: SpeciesId?,
      scientificName: String?,
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
  ): ObservationBiomassSpeciesRecord {
    return with(OBSERVATION_BIOMASS_SPECIES) {
      val idOrNameCondition =
          speciesId?.let<SpeciesId, Condition> { SPECIES_ID.eq(it) }
              ?: SCIENTIFIC_NAME.eq(scientificName)

      dslContext
          .selectFrom(OBSERVATION_BIOMASS_SPECIES)
          .where(OBSERVATION_ID.eq(observationId))
          .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
          .and(idOrNameCondition)
          .fetchOne() ?: throw BiomassSpeciesNotFoundException(speciesId, scientificName)
    }
  }

  private fun getBiomassSpeciesName(speciesId: SpeciesId?, scientificName: String?): String =
      speciesId?.let { dslContext.fetchValue(SPECIES.SCIENTIFIC_NAME, SPECIES.ID.eq(it)) }
          ?: scientificName
          ?: throw IllegalStateException(
              "Biomass species must have a species ID or scientific name"
          )

  /**
   * Returns the ID of the biomass species that a new tree or quadrat measurement in a plot will
   * refer to, creating it if needed. The species is removed from the plot's additional species list
   * since it is now referenced in a measurement there.
   */
  private fun getOrCreateMeasuredBiomassSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId?,
      scientificName: String?,
  ): BiomassSpeciesId {
    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    if (speciesId != null && parentStore.getOrganizationId(speciesId) != organizationId) {
      throw SpeciesNotFoundException(speciesId)
    }

    val existing =
        with(OBSERVATION_BIOMASS_SPECIES) {
          val speciesCondition =
              speciesId?.let { SPECIES_ID.eq(it) } ?: SCIENTIFIC_NAME.eq(scientificName)
          dslContext
              .selectFrom(OBSERVATION_BIOMASS_SPECIES)
              .where(OBSERVATION_ID.eq(observationId))
              .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
              .and(speciesCondition)
              .fetchOne()
        }
    if (existing != null) {
      with(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES) {
        dslContext
            .deleteFrom(this)
            .where(OBSERVATION_ID.eq(observationId))
            .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(BIOMASS_SPECIES_ID.eq(existing.id))
            .execute()
      }

      return existing.id!!
    }

    return insertBiomassSpecies(
        observationId,
        monitoringPlotId,
        speciesId,
        scientificName,
        commonName = null,
        isInvasive = false,
        isThreatened = false,
        organizationId = organizationId,
    )
  }

  private fun insertBiomassSpecies(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      speciesId: SpeciesId?,
      scientificName: String?,
      commonName: String?,
      isInvasive: Boolean,
      isThreatened: Boolean,
      organizationId: OrganizationId,
  ): BiomassSpeciesId {
    val record =
        ObservationBiomassSpeciesRecord(
                commonName = commonName,
                isInvasive = isInvasive,
                isThreatened = isThreatened,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
                scientificName = scientificName,
                speciesId = speciesId,
            )
            .attach(dslContext)
    record.insert()

    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    eventPublisher.publishEvent(
        BiomassSpeciesCreatedEvent(
            biomassSpeciesId = record.id!!,
            commonName = commonName,
            isInvasive = isInvasive,
            isThreatened = isThreatened,
            monitoringPlotId = monitoringPlotId,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = scientificName,
            speciesId = speciesId,
            speciesName = getBiomassSpeciesName(speciesId, scientificName),
        )
    )

    return record.id!!
  }

  private fun deleteBiomassSpeciesIfUnreferenced(biomassSpeciesId: BiomassSpeciesId) {
    val hasRecordedTrees =
        dslContext.fetchExists(
            RECORDED_TREES,
            RECORDED_TREES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId),
        )
    val hasQuadratSpecies =
        dslContext.fetchExists(
            OBSERVATION_BIOMASS_QUADRAT_SPECIES,
            OBSERVATION_BIOMASS_QUADRAT_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId),
        )
    val isAdditionalSpecies =
        dslContext.fetchExists(
            OBSERVATION_BIOMASS_ADDITIONAL_SPECIES,
            OBSERVATION_BIOMASS_ADDITIONAL_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId),
        )
    if (hasRecordedTrees || hasQuadratSpecies || isAdditionalSpecies) {
      return
    }

    val record =
        dslContext
            .selectFrom(OBSERVATION_BIOMASS_SPECIES)
            .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
            .fetchOne() ?: return
    deleteBiomassSpeciesRow(record, isImplicit = true)
  }

  private fun deleteBiomassSpeciesRow(
      record: ObservationBiomassSpeciesRecord,
      isImplicit: Boolean,
  ) {
    val biomassSpeciesId = record.id!!
    val observationId = record.observationId!!
    val organizationId =
        parentStore.getOrganizationId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)

    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES)
        .where(OBSERVATION_BIOMASS_ADDITIONAL_SPECIES.BIOMASS_SPECIES_ID.eq(biomassSpeciesId))
        .execute()

    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
        .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(biomassSpeciesId))
        .execute()

    eventPublisher.publishEvent(
        BiomassSpeciesDeletedEvent(
            biomassSpeciesId = biomassSpeciesId,
            commonName = record.commonName,
            isImplicit = isImplicit,
            monitoringPlotId = record.monitoringPlotId!!,
            observationId = observationId,
            organizationId = organizationId,
            plantingSiteId = plantingSiteId,
            scientificName = record.scientificName,
            speciesId = record.speciesId,
            speciesName = getBiomassSpeciesName(record.speciesId, record.scientificName),
        )
    )
  }

  private fun validateRecordedTreeUpdate(
      existing: ExistingRecordedTreeModel,
      updated: ExistingRecordedTreeModel,
  ) {
    when (existing.treeGrowthForm) {
      TreeGrowthForm.Shrub ->
          require(
              updated.boleHeightM == null &&
                  updated.diameterAtBreastHeightCm == null &&
                  updated.heightM == null &&
                  updated.pointOfMeasurementM == null &&
                  updated.treeCrownDiameterCm == null
          ) {
            "Tree measurements may not be edited on shrubs"
          }
      TreeGrowthForm.Tree,
      TreeGrowthForm.Trunk ->
          require(existing.shrubDiameterCm == updated.shrubDiameterCm) {
            "Shrub measurements may not be edited on trees"
          }
    }
  }
}
