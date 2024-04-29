package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class SpeciesModel<ID : SpeciesId?>(
    val checkedTime: Instant? = null,
    val commonName: String? = null,
    val conservationCategory: ConservationCategory? = null,
    val deletedTime: Instant? = null,
    val ecosystemTypes: Set<EcosystemType> = emptySet(),
    val familyName: String? = null,
    val growthForms: Set<GrowthForm> = emptySet(),
    val id: ID,
    val organizationId: OrganizationId,
    val plantMaterialSourcingMethods: Set<PlantMaterialSourcingMethod> = emptySet(),
    val rare: Boolean? = null,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior? = null,
    val successionalGroups: Set<SuccessionalGroup> = emptySet(),
    val initialScientificName: String = scientificName,
) {
  companion object {
    fun of(
        record: Record,
        ecosystemTypesMultiset: Field<Set<EcosystemType>>,
        growthFormsMultiset: Field<Set<GrowthForm>>,
        plantMaterialSourcingMethodsMultiset: Field<Set<PlantMaterialSourcingMethod>>,
        successionalGroupsMultiset: Field<Set<SuccessionalGroup>>,
    ): ExistingSpeciesModel =
        ExistingSpeciesModel(
            checkedTime = record[SPECIES.CHECKED_TIME],
            commonName = record[SPECIES.COMMON_NAME],
            conservationCategory = record[SPECIES.CONSERVATION_CATEGORY_ID],
            deletedTime = record[SPECIES.DELETED_TIME],
            ecosystemTypes = record[ecosystemTypesMultiset] ?: emptySet(),
            familyName = record[SPECIES.FAMILY_NAME],
            growthForms = record[growthFormsMultiset] ?: emptySet(),
            id = record[SPECIES.ID]!!,
            initialScientificName = record[SPECIES.INITIAL_SCIENTIFIC_NAME]!!,
            organizationId = record[SPECIES.ORGANIZATION_ID]!!,
            plantMaterialSourcingMethods =
                record[plantMaterialSourcingMethodsMultiset] ?: emptySet(),
            rare = record[SPECIES.RARE],
            scientificName = record[SPECIES.SCIENTIFIC_NAME]!!,
            seedStorageBehavior = record[SPECIES.SEED_STORAGE_BEHAVIOR_ID],
            successionalGroups = record[successionalGroupsMultiset] ?: emptySet(),
        )
  }
}

typealias NewSpeciesModel = SpeciesModel<Nothing?>

typealias ExistingSpeciesModel = SpeciesModel<SpeciesId>
