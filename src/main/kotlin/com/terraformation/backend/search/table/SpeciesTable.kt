package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.TableField

class SpeciesTable(tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          organizations.asSingleValueSublist(
              "organization", SPECIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          idWrapperField("id", "Species ID", SPECIES.ID) { SpeciesId(it) },
          textField("commonName", "Species common name", SPECIES.COMMON_NAME),
          booleanField("endangered", "Species endangered", SPECIES.ENDANGERED),
          textField("familyName", "Species family name", SPECIES.FAMILY_NAME, nullable = false),
          booleanField("rare", "Species rare", SPECIES.RARE),
          textField(
              "scientificName",
              "Species scientific name",
              SPECIES.SCIENTIFIC_NAME,
              nullable = false),
      )

  override fun conditionForPermissions(): Condition {
    return SPECIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }
}
