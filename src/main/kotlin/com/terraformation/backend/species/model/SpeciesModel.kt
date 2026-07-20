package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.WoodDensityLevel
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import java.math.BigDecimal
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class ExistingSpeciesProjectModel(
    val calculatedNativity: SpeciesNativity? = null,
    val calculatedNativitySource: SpeciesDataSourceModel? = null,
    val overriddenJustification: String? = null,
    val overriddenNativity: SpeciesNativity? = null,
    val projectId: ProjectId?,
) {
  companion object {
    fun of(record: Record) =
        with(PROJECT_SPECIES) {
          ExistingSpeciesProjectModel(
              calculatedNativity = record[CALCULATED_NATIVITY_ID],
              calculatedNativitySource =
                  SpeciesDataSourceModel.of(
                      record[CALCULATED_NATIVITY_DATASET_DATE],
                      record[CALCULATED_NATIVITY_DATASET_TYPE_ID],
                  ),
              overriddenJustification = record[OVERRIDDEN_JUSTIFICATION],
              overriddenNativity = record[OVERRIDDEN_NATIVITY_ID],
              projectId = record[PROJECT_ID],
          )
        }
  }
}

data class ExistingSpeciesModel(
    val averageWoodDensity: BigDecimal? = null,
    val checkedTime: Instant? = null,
    val commonName: String? = null,
    val commonNameSource: SpeciesDataSourceModel? = null,
    val conservationCategory: ConservationCategory? = null,
    val createdTime: Instant,
    val dbhSource: String? = null,
    val dbhValue: BigDecimal? = null,
    val deletedTime: Instant? = null,
    val ecologicalRoleKnown: String? = null,
    val ecosystemTypes: Set<EcosystemType> = emptySet(),
    val familyName: String? = null,
    val familyNameSource: SpeciesDataSourceModel? = null,
    val growthForms: Set<GrowthForm> = emptySet(),
    val heightAtMaturitySource: String? = null,
    val heightAtMaturityValue: BigDecimal? = null,
    val id: SpeciesId,
    val localUsesKnown: String? = null,
    val modifiedTime: Instant,
    val nativeEcosystem: String? = null,
    val organizationId: OrganizationId,
    val otherFacts: String? = null,
    val plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod> = emptySet(),
    val projects: List<ExistingSpeciesProjectModel> = emptyList(),
    val rare: Boolean? = null,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior? = null,
    val successionalGroups: Set<SuccessionalGroup> = emptySet(),
    val woodDensityLevel: WoodDensityLevel? = null,
    val initialScientificName: String = scientificName,
) {
  companion object {
    fun of(
        record: Record,
        ecosystemTypesMultiset: Field<Set<EcosystemType>>? = null,
        growthFormsMultiset: Field<Set<GrowthForm>>? = null,
        plantMaterialSourcingMethodsMultiset: Field<Set<PlantMaterialSourcingMethod>>? = null,
        projectSpeciesMultiset: Field<List<ExistingSpeciesProjectModel>>? = null,
        successionalGroupsMultiset: Field<Set<SuccessionalGroup>>? = null,
    ): ExistingSpeciesModel =
        ExistingSpeciesModel(
            averageWoodDensity = record[SPECIES.AVERAGE_WOOD_DENSITY],
            checkedTime = record[SPECIES.CHECKED_TIME],
            commonName = record[SPECIES.COMMON_NAME],
            commonNameSource =
                SpeciesDataSourceModel.of(
                    record[SPECIES.COMMON_NAME_DATASET_DATE],
                    record[SPECIES.COMMON_NAME_DATASET_TYPE_ID],
                ),
            conservationCategory = record[SPECIES.CONSERVATION_CATEGORY_ID],
            createdTime = record[SPECIES.CREATED_TIME]!!,
            dbhSource = record[SPECIES.DBH_SOURCE],
            dbhValue = record[SPECIES.DBH_VALUE],
            deletedTime = record[SPECIES.DELETED_TIME],
            ecologicalRoleKnown = record[SPECIES.ECOLOGICAL_ROLE_KNOWN],
            ecosystemTypes = ecosystemTypesMultiset?.let { record[it] } ?: emptySet(),
            familyName = record[SPECIES.FAMILY_NAME],
            familyNameSource =
                SpeciesDataSourceModel.of(
                    record[SPECIES.FAMILY_NAME_DATASET_DATE],
                    record[SPECIES.FAMILY_NAME_DATASET_TYPE_ID],
                ),
            growthForms = growthFormsMultiset?.let { record[it] } ?: emptySet(),
            heightAtMaturitySource = record[SPECIES.HEIGHT_AT_MATURITY_SOURCE],
            heightAtMaturityValue = record[SPECIES.HEIGHT_AT_MATURITY_VALUE],
            id = record[SPECIES.ID]!!,
            initialScientificName = record[SPECIES.INITIAL_SCIENTIFIC_NAME]!!,
            localUsesKnown = record[SPECIES.LOCAL_USES_KNOWN],
            modifiedTime = record[SPECIES.MODIFIED_TIME]!!,
            nativeEcosystem = record[SPECIES.NATIVE_ECOSYSTEM],
            organizationId = record[SPECIES.ORGANIZATION_ID]!!,
            otherFacts = record[SPECIES.OTHER_FACTS],
            plantMaterialSourcingMethods =
                plantMaterialSourcingMethodsMultiset?.let { record[it] } ?: emptySet(),
            projects = projectSpeciesMultiset?.let { record[it] } ?: emptyList(),
            rare = record[SPECIES.RARE],
            scientificName = record[SPECIES.SCIENTIFIC_NAME]!!,
            seedStorageBehavior = record[SPECIES.SEED_STORAGE_BEHAVIOR_ID],
            successionalGroups = successionalGroupsMultiset?.let { record[it] } ?: emptySet(),
            woodDensityLevel = record[SPECIES.WOOD_DENSITY_LEVEL_ID],
        )
  }
}

data class NewSpeciesModel(
    val averageWoodDensity: BigDecimal? = null,
    val checkedTime: Instant? = null,
    val commonName: String? = null,
    val commonNameSource: SpeciesDataSourceModel? = null,
    val conservationCategory: ConservationCategory? = null,
    val dbhSource: String? = null,
    val dbhValue: BigDecimal? = null,
    val deletedTime: Instant? = null,
    val ecologicalRoleKnown: String? = null,
    val ecosystemTypes: Set<EcosystemType> = emptySet(),
    val familyName: String? = null,
    val familyNameSource: SpeciesDataSourceModel? = null,
    val growthForms: Set<GrowthForm> = emptySet(),
    val heightAtMaturitySource: String? = null,
    val heightAtMaturityValue: BigDecimal? = null,
    val localUsesKnown: String? = null,
    val nativeEcosystem: String? = null,
    val organizationId: OrganizationId,
    val otherFacts: String? = null,
    val plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod> = emptySet(),
    val projectIds: Set<ProjectId> = emptySet(),
    val rare: Boolean? = null,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior? = null,
    val successionalGroups: Set<SuccessionalGroup> = emptySet(),
    val woodDensityLevel: WoodDensityLevel? = null,
)
