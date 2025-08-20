package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesKey
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewRecordedTreeModel
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import org.locationtech.jts.geom.Point

data class BiomassSpeciesPayload(
    val commonName: String?,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val scientificName: String?,
    val speciesId: SpeciesId?,
) {
  constructor(
      model: BiomassSpeciesModel
  ) : this(
      isInvasive = model.isInvasive,
      isThreatened = model.isThreatened,
      speciesId = model.speciesId,
      scientificName = model.scientificName,
      commonName = model.commonName,
  )

  fun toModel(): BiomassSpeciesModel {
    return BiomassSpeciesModel(
        commonName = commonName,
        isInvasive = isInvasive,
        isThreatened = isThreatened,
        scientificName = scientificName,
        speciesId = speciesId,
    )
  }
}

data class ExistingBiomassQuadratPayload(
    val description: String?,
    val position: ObservationPlotPosition,
    val species: List<ExistingBiomassQuadratSpeciesPayload>,
) {
  constructor(
      model: BiomassQuadratModel,
      position: ObservationPlotPosition,
      species: Map<BiomassSpeciesKey, BiomassSpeciesModel>,
  ) : this(
      description = model.description,
      position = position,
      species = model.species.map { ExistingBiomassQuadratSpeciesPayload(it, species) },
  )
}

data class ExistingBiomassQuadratSpeciesPayload(
    val abundancePercent: Int,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    val speciesId: SpeciesId?,
    val speciesName: String?,
) {
  constructor(
      model: BiomassQuadratSpeciesModel,
      species: Map<BiomassSpeciesKey, BiomassSpeciesModel>,
  ) : this(
      abundancePercent = model.abundancePercent,
      isInvasive = species[BiomassSpeciesKey(model.speciesId, model.speciesName)]!!.isInvasive,
      isThreatened = species[BiomassSpeciesKey(model.speciesId, model.speciesName)]!!.isThreatened,
      speciesId = model.speciesId,
      speciesName = model.speciesName,
  )
}

data class NewBiomassQuadratPayload(
    val description: String?,
    val position: ObservationPlotPosition,
    val species: List<NewBiomassQuadratSpeciesPayload>,
) {
  fun toModel(): BiomassQuadratModel {
    return BiomassQuadratModel(
        description = description,
        species = species.map { it.toModel() }.toSet(),
    )
  }
}

data class NewBiomassQuadratSpeciesPayload(
    @Schema(minimum = "0", maximum = "100") //
    val abundancePercent: Int,
    val speciesId: SpeciesId?,
    val speciesName: String?,
) {
  fun toModel(): BiomassQuadratSpeciesModel {
    return BiomassQuadratSpeciesModel(
        abundancePercent = abundancePercent,
        speciesId = speciesId,
        speciesName = speciesName,
    )
  }
}

data class ExistingTreePayload(
    val description: String?,
    @Schema(description = "Measured in centimeters.") //
    val diameterAtBreastHeight: BigDecimal?,
    val gpsCoordinates: Point?,
    @Schema(description = "Measured in meters.") //
    val height: BigDecimal?,
    val isDead: Boolean,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    @Schema(description = "Measured in meters.") //
    val pointOfMeasurement: BigDecimal?,
    val shrubDiameter: Int?,
    val speciesId: SpeciesId?,
    val speciesName: String?,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
    val trunkNumber: Int,
) {
  constructor(
      model: ExistingRecordedTreeModel,
      species: Map<BiomassSpeciesKey, BiomassSpeciesModel>,
  ) : this(
      description = model.description,
      diameterAtBreastHeight = model.diameterAtBreastHeightCm,
      gpsCoordinates = model.gpsCoordinates,
      height = model.heightM,
      isDead = model.isDead,
      isInvasive = species[BiomassSpeciesKey(model.speciesId, model.speciesName)]!!.isInvasive,
      isThreatened = species[BiomassSpeciesKey(model.speciesId, model.speciesName)]!!.isThreatened,
      pointOfMeasurement = model.pointOfMeasurementM,
      shrubDiameter = model.shrubDiameterCm,
      speciesId = model.speciesId,
      speciesName = model.speciesName,
      treeGrowthForm = model.treeGrowthForm,
      treeNumber = model.treeNumber,
      trunkNumber = model.trunkNumber,
  )
}

@JsonSubTypes(
    JsonSubTypes.Type(name = "shrub", value = NewShrubPayload::class),
    JsonSubTypes.Type(name = "tree", value = NewTreeWithTrunksPayload::class),
)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "growthForm",
)
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(value = "shrub", schema = NewShrubPayload::class),
            DiscriminatorMapping(value = "tree", schema = NewTreeWithTrunksPayload::class),
        ]
)
sealed interface NewTreePayload {
  @get:Schema(description = "GPS coordinates where plant was observed.") //
  val gpsCoordinates: Point?
  val speciesId: SpeciesId?
  val speciesName: String?

  fun toTreeModels(treeNumber: Int): List<NewRecordedTreeModel>
}

@JsonTypeName("shrub")
data class NewShrubPayload(
    val description: String?,
    override val gpsCoordinates: Point? = null,
    val isDead: Boolean,
    override val speciesId: SpeciesId?,
    override val speciesName: String?,
    @Schema(description = "Measured in centimeters.") val shrubDiameter: Int,
) : NewTreePayload {
  override fun toTreeModels(treeNumber: Int): List<NewRecordedTreeModel> {
    return listOf(
        NewRecordedTreeModel(
            id = null,
            description = description,
            diameterAtBreastHeightCm = null,
            gpsCoordinates = gpsCoordinates,
            heightM = null,
            isDead = isDead,
            pointOfMeasurementM = null,
            shrubDiameterCm = shrubDiameter,
            speciesId = speciesId,
            speciesName = speciesName,
            treeGrowthForm = TreeGrowthForm.Shrub,
            treeNumber = treeNumber,
            trunkNumber = 1,
        )
    )
  }
}

data class NewTrunkPayload(
    @Schema(description = "Measured in centimeters.") //
    val diameterAtBreastHeight: BigDecimal,
    @Schema(description = "Measured in meters.") //
    val height: BigDecimal?,
    @Schema(description = "Measured in meters.") //
    val pointOfMeasurement: BigDecimal,
    val description: String?,
    val isDead: Boolean,
) {
  fun toTreeModel(
      gpsCoordinates: Point?,
      growthForm: TreeGrowthForm,
      speciesId: SpeciesId?,
      speciesName: String?,
      treeNumber: Int,
      trunkNumber: Int,
  ): NewRecordedTreeModel {
    return NewRecordedTreeModel(
        id = null,
        description = description,
        diameterAtBreastHeightCm = diameterAtBreastHeight,
        gpsCoordinates = gpsCoordinates,
        heightM = height,
        isDead = isDead,
        pointOfMeasurementM = pointOfMeasurement,
        shrubDiameterCm = null,
        speciesId = speciesId,
        speciesName = speciesName,
        treeGrowthForm = growthForm,
        treeNumber = treeNumber,
        trunkNumber = trunkNumber,
    )
  }
}

@JsonTypeName("tree")
data class NewTreeWithTrunksPayload(
    override val gpsCoordinates: Point? = null,
    override val speciesId: SpeciesId?,
    override val speciesName: String?,
    val trunks: List<NewTrunkPayload>,
) : NewTreePayload {
  override fun toTreeModels(treeNumber: Int): List<NewRecordedTreeModel> {
    if (trunks.isEmpty()) {
      throw IllegalArgumentException("Tree $treeNumber has no trunks.")
    }

    val growthForm =
        if (trunks.size > 1) {
          TreeGrowthForm.Trunk
        } else {
          TreeGrowthForm.Tree
        }

    return trunks.mapIndexed { index, trunk ->
      trunk.toTreeModel(gpsCoordinates, growthForm, speciesId, speciesName, treeNumber, index + 1)
    }
  }
}

data class ExistingBiomassMeasurementPayload(
    val additionalSpecies: List<BiomassSpeciesPayload>,
    val description: String?,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: Int,
    val ph: BigDecimal?,
    val quadrats: List<ExistingBiomassQuadratPayload>,
    @Schema(description = "Measured in ppt") //
    val salinity: BigDecimal?,
    val smallTreeCountLow: Int,
    val smallTreeCountHigh: Int,
    val soilAssessment: String,
    @Schema(description = "Low or high tide.") //
    val tide: MangroveTide?,
    @Schema(description = "Time when ide is observed.") //
    val tideTime: Instant?,
    val treeSpeciesCount: Int,
    val trees: List<ExistingTreePayload>,
    @Schema(description = "Measured in centimeters.") //
    val waterDepth: Int?,
) {
  companion object {
    fun of(model: ExistingBiomassDetailsModel): ExistingBiomassMeasurementPayload {
      val species = model.species.associateBy { BiomassSpeciesKey(it.speciesId, it.scientificName) }

      val treeSpecies =
          species.filterKeys { key ->
            model.trees.any { BiomassSpeciesKey(it.speciesId, it.speciesName) == key }
          }

      val quadratSpecies =
          species.filterKeys { key ->
            model.quadrats
                .flatMap { it.value.species }
                .any { BiomassSpeciesKey(it.speciesId, it.speciesName) == key }
          }

      // Find species that are not part of a tree or a quadrat
      val additionalSpecies =
          species.filterKeys { treeSpecies[it] == null && quadratSpecies[it] == null }

      return ExistingBiomassMeasurementPayload(
          additionalSpecies = additionalSpecies.map { BiomassSpeciesPayload(it.value) },
          description = model.description,
          forestType = model.forestType,
          herbaceousCoverPercent = model.herbaceousCoverPercent,
          ph = model.ph,
          quadrats =
              model.quadrats.map { (position, quadrat) ->
                ExistingBiomassQuadratPayload(quadrat, position, species)
              },
          salinity = model.salinityPpt,
          smallTreeCountLow = model.smallTreeCountRange.first,
          smallTreeCountHigh = model.smallTreeCountRange.second,
          soilAssessment = model.soilAssessment,
          tide = model.tide,
          tideTime = model.tideTime,
          treeSpeciesCount = treeSpecies.size,
          trees = model.trees.map { ExistingTreePayload(it, species) },
          waterDepth = model.waterDepthCm,
      )
    }
  }
}

data class NewBiomassMeasurementPayload(
    val description: String?,
    val forestType: BiomassForestType,
    @Schema(minimum = "0", maximum = "100") //
    val herbaceousCoverPercent: Int,
    @Schema(description = "Required for Mangrove forest.", minimum = "0", maximum = "14")
    val ph: BigDecimal?,
    val quadrats: List<NewBiomassQuadratPayload>,
    @Schema(description = "Measured in ppt. Required for Mangrove forest.", minimum = "0")
    val salinity: BigDecimal?,
    @Schema(minimum = "0") //
    val smallTreeCountLow: Int,
    @Schema(minimum = "smallTreeCountLow") //
    val smallTreeCountHigh: Int,
    val soilAssessment: String,
    @Schema(description = "Low or high tide. Required for Mangrove forest.")
    val tide: MangroveTide?,
    @Schema(description = "Time when ide is observed. Required for Mangrove forest.")
    val tideTime: Instant?,
    val trees: List<NewTreePayload>,
    @Schema(
        description =
            "List of herbaceous and tree species. Includes all recorded quadrat and additional " +
                "herbaceous species and recorded tree species. Species not assigned to a quadrat or " +
                "recorded trees will be saved as an additional herbaceous species."
    )
    val species: List<BiomassSpeciesPayload>,
    @Schema(description = "Measured in centimeters. Required for Mangrove forest.")
    val waterDepth: Int?,
) {
  fun toModel(): NewBiomassDetailsModel {
    return NewBiomassDetailsModel(
        description = description,
        forestType = forestType,
        herbaceousCoverPercent = herbaceousCoverPercent,
        observationId = null,
        ph = ph,
        quadrats = quadrats.associateBy { it.position }.mapValues { it.value.toModel() },
        salinityPpt = salinity,
        smallTreeCountRange = smallTreeCountLow to smallTreeCountHigh,
        soilAssessment = soilAssessment,
        species = species.map { it.toModel() }.toSet(),
        plotId = null,
        tide = tide,
        tideTime = tideTime,
        trees = trees.flatMapIndexed { index, tree -> tree.toTreeModels(index + 1) },
        waterDepthCm = waterDepth,
    )
  }
}
