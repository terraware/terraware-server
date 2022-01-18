package com.terraformation.backend.search.table

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantsTable(private val tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTS.FEATURE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          features.asSingleValueSublist("feature", PLANTS.FEATURE_ID.eq(FEATURES.ID)),
          species.asSingleValueSublist(
              "species", PLANTS.SPECIES_ID.eq(SPECIES.ID), isRequired = false),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("datePlanted", "Date planted", PLANTS.DATE_PLANTED, nullable = true),
          idWrapperField("featureId", "Plant feature ID", PLANTS.FEATURE_ID) { FeatureId(it) },
          textField("label", "Plant label", PLANTS.LABEL),
          booleanField("naturalRegen", "Natural regeneration", PLANTS.NATURAL_REGEN),
      )

  override val inheritsPermissionsFrom: SearchTable
    get() = tables.features

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(FEATURES).on(PLANTS.FEATURE_ID.eq(FEATURES.ID))
  }
}
