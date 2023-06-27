package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.db.nursery.tables.references.INVENTORIES
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
          speciesEcosystemTypes.asMultiValueSublist(
              "ecosystemTypes", SPECIES.ID.eq(SPECIES_ECOSYSTEM_TYPES.SPECIES_ID)),
          organizations.asSingleValueSublist(
              "organization", SPECIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          speciesProblems.asMultiValueSublist(
              "problems", SPECIES.ID.eq(SPECIES_PROBLEMS.SPECIES_ID)),
          inventories.asSingleValueSublist(
              "inventory",
              SPECIES.ORGANIZATION_ID.eq(INVENTORIES.ORGANIZATION_ID)
                  .and(SPECIES.ID.eq(INVENTORIES.SPECIES_ID)),
              isRequired = false),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("checkedTime", SPECIES.CHECKED_TIME),
          textField("commonName", SPECIES.COMMON_NAME),
          enumField("conservationCategory", SPECIES.CONSERVATION_CATEGORY_ID, localize = false),
          booleanField("endangered", SPECIES.ENDANGERED),
          textField("familyName", SPECIES.FAMILY_NAME, nullable = false),
          enumField("growthForm", SPECIES.GROWTH_FORM_ID),
          idWrapperField("id", SPECIES.ID) { SpeciesId(it) },
          booleanField("rare", SPECIES.RARE),
          textField("scientificName", SPECIES.SCIENTIFIC_NAME, nullable = false),
          enumField("seedStorageBehavior", SPECIES.SEED_STORAGE_BEHAVIOR_ID),
      )

  override fun conditionForVisibility(): Condition {
    return SPECIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
        .and(SPECIES.DELETED_TIME.isNull)
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    return SPECIES.ORGANIZATION_ID.eq(organizationId)
  }
}
