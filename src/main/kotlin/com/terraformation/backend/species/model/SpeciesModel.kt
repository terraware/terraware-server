package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import java.time.Instant
import org.jooq.Field
import org.jooq.Record

data class SpeciesModel<ID : SpeciesId?>(
    val checkedTime: Instant? = null,
    val commonName: String? = null,
    val deletedTime: Instant? = null,
    val ecosystemTypes: Set<EcosystemType> = emptySet(),
    val endangered: Boolean? = null,
    val familyName: String? = null,
    val growthForm: GrowthForm? = null,
    val id: ID,
    val organizationId: OrganizationId,
    val rare: Boolean? = null,
    val scientificName: String,
    val seedStorageBehavior: SeedStorageBehavior? = null,
    val initialScientificName: String = scientificName,
) {
  companion object {
    fun of(
        record: Record,
        ecosystemTypesMultiset: Field<Set<EcosystemType>>
    ): ExistingSpeciesModel =
        ExistingSpeciesModel(
            checkedTime = record[SPECIES.CHECKED_TIME],
            commonName = record[SPECIES.COMMON_NAME],
            deletedTime = record[SPECIES.DELETED_TIME],
            ecosystemTypes = record[ecosystemTypesMultiset] ?: emptySet(),
            endangered = record[SPECIES.ENDANGERED],
            familyName = record[SPECIES.FAMILY_NAME],
            growthForm = record[SPECIES.GROWTH_FORM_ID],
            id = record[SPECIES.ID]!!,
            initialScientificName = record[SPECIES.INITIAL_SCIENTIFIC_NAME]!!,
            organizationId = record[SPECIES.ORGANIZATION_ID]!!,
            rare = record[SPECIES.RARE],
            scientificName = record[SPECIES.SCIENTIFIC_NAME]!!,
            seedStorageBehavior = record[SPECIES.SEED_STORAGE_BEHAVIOR_ID],
        )
  }
}

typealias NewSpeciesModel = SpeciesModel<Nothing?>

typealias ExistingSpeciesModel = SpeciesModel<SpeciesId>
