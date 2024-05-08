package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_GROWTH_FORMS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PLANT_MATERIAL_SOURCING_METHODS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_SUCCESSIONAL_GROUPS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
import com.terraformation.backend.db.nursery.tables.references.SPECIES_PROJECTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class SpeciesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          inventories.asSingleValueSublist(
              "inventory",
              SPECIES.ORGANIZATION_ID.eq(INVENTORIES.ORGANIZATION_ID)
                  .and(SPECIES.ID.eq(INVENTORIES.SPECIES_ID)),
              isRequired = false),
          nurserySpeciesProjects.asMultiValueSublist(
              "nurseryProjects", SPECIES.ID.eq(SPECIES_PROJECTS.SPECIES_ID)),
          organizations.asSingleValueSublist(
              "organization", SPECIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          participantProjectSpecies.asMultiValueSublist(
              "participantProjectSpecies", SPECIES.ID.eq(PARTICIPANT_PROJECT_SPECIES.SPECIES_ID)),
          speciesEcosystemTypes.asMultiValueSublist(
              "ecosystemTypes", SPECIES.ID.eq(SPECIES_ECOSYSTEM_TYPES.SPECIES_ID)),
          speciesGrowthForms.asMultiValueSublist(
              "growthForms", SPECIES.ID.eq(SPECIES_GROWTH_FORMS.SPECIES_ID)),
          speciesPlantMaterialSourcingMethods.asMultiValueSublist(
              "plantMaterialSourcingMethods",
              SPECIES.ID.eq(SPECIES_PLANT_MATERIAL_SOURCING_METHODS.SPECIES_ID)),
          speciesProblems.asMultiValueSublist(
              "problems", SPECIES.ID.eq(SPECIES_PROBLEMS.SPECIES_ID)),
          speciesSuccessionalGroups.asMultiValueSublist(
              "successionalGroups", SPECIES.ID.eq(SPECIES_SUCCESSIONAL_GROUPS.SPECIES_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("checkedTime", SPECIES.CHECKED_TIME),
          textField("commonName", SPECIES.COMMON_NAME),
          enumField("conservationCategory", SPECIES.CONSERVATION_CATEGORY_ID, localize = false),
          textField("familyName", SPECIES.FAMILY_NAME, nullable = false),
          idWrapperField("id", SPECIES.ID) { SpeciesId(it) },
          booleanField("rare", SPECIES.RARE),
          textField("scientificName", SPECIES.SCIENTIFIC_NAME, nullable = false),
          enumField("seedStorageBehavior", SPECIES.SEED_STORAGE_BEHAVIOR_ID),
      )

  override fun conditionForVisibility(): Condition {
    return SPECIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
        .and(SPECIES.DELETED_TIME.isNull)
  }
}
