package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.attach
import com.terraformation.backend.db.default_schema.SpeciesId
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.event.BiomassDetailsCreatedEvent
import com.terraformation.backend.tracking.event.BiomassQuadratCreatedEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class BiomassStore(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val observationLocker: ObservationLocker,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()

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

    // For quadrat species, we need to add the abundance percentages of the numbered species and the
    // "Other" one if both exist in the quadrat. If only the "Other" one exists, then we can just
    // switch its biomass species ID in place.
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
              ABUNDANCE_PERCENT,
              ABUNDANCE_PERCENT.plus(
                  DSL.field(
                      DSL.select(quadratSpeciesTable2.ABUNDANCE_PERCENT)
                          .from(quadratSpeciesTable2)
                          .where(quadratSpeciesTable2.BIOMASS_SPECIES_ID.eq(otherBiomassSpeciesId))
                          .and(quadratSpeciesTable2.POSITION_ID.eq(POSITION_ID))
                  )
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

    // ON DELETE CASCADE will remove the data for the "Other" species from all the biomass tables.
    dslContext
        .deleteFrom(OBSERVATION_BIOMASS_SPECIES)
        .where(OBSERVATION_BIOMASS_SPECIES.ID.eq(otherBiomassSpeciesId))
        .execute()
  }
}
