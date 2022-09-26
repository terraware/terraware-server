package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class SpeciesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization", SPECIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
          speciesProblems.asMultiValueSublist(
              "problems", SPECIES.ID.eq(SPECIES_PROBLEMS.SPECIES_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("checkedTime", "Species checked time", SPECIES.CHECKED_TIME),
          textField("commonName", "Species common name", SPECIES.COMMON_NAME),
          booleanField("endangered", "Species is endangered", SPECIES.ENDANGERED),
          textField("familyName", "Species family name", SPECIES.FAMILY_NAME, nullable = false),
          enumField("growthForm", "Species growth form", SPECIES.GROWTH_FORM_ID),
          idWrapperField("id", "Species ID", SPECIES.ID) { SpeciesId(it) },
          booleanField("rare", "Species is rare", SPECIES.RARE),
          textField(
              "scientificName",
              "Species scientific name",
              SPECIES.SCIENTIFIC_NAME,
              nullable = false),
          enumField(
              "seedStorageBehavior",
              "Species seed storage behavior",
              SPECIES.SEED_STORAGE_BEHAVIOR_ID),
      )

  override fun conditionForVisibility(): Condition {
    return SPECIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
        .and(SPECIES.DELETED_TIME.isNull)
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    return when (scope) {
      is OrganizationIdScope -> SPECIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          SPECIES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
