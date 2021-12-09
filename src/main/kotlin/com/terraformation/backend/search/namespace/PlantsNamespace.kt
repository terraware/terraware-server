package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class PlantsNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          features.asSingleValueSublist("feature", PLANTS.FEATURE_ID.eq(FEATURES.ID)),
          species.asSingleValueSublist("species", PLANTS.SPECIES_ID.eq(SPECIES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.plants) {
        listOf(
            dateField("datePlanted", "Date planted", PLANTS.DATE_PLANTED, nullable = true),
            idWrapperField("featureId", "Plant feature ID", PLANTS.FEATURE_ID) { FeatureId(it) },
            textField("label", "Plant label", PLANTS.LABEL),
            booleanField("naturalRegen", "Natural regeneration", PLANTS.NATURAL_REGEN),
        )
      }
}
