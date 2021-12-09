package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class FeaturesNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          layers.asSingleValueSublist("layer", FEATURES.LAYER_ID.eq(LAYERS.ID)),
          plants.asSingleValueSublist("plant", FEATURES.ID.eq(PLANTS.FEATURE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.features) {
        listOf(
            textField("attributes", "Feature attributes", FEATURES.ATTRIB),
            timestampField("createdTime", "Feature created time", FEATURES.CREATED_TIME),
            timestampField("enteredTime", "Feature entered time", FEATURES.ENTERED_TIME),
            geometryField("geom", "Feature geometry", FEATURES.GEOM, nullable = false),
            doubleField(
                "gpsHorizAccuracy", "GPS horizontal accuracy (m)", FEATURES.GPS_HORIZ_ACCURACY),
            doubleField("gpsVertAccuracy", "GPS vertical accuracy (m)", FEATURES.GPS_VERT_ACCURACY),
            idWrapperField("id", "Feature ID", FEATURES.ID) { FeatureId(it) },
            textField("notes", "Feature notes", FEATURES.NOTES),
        )
      }
}
