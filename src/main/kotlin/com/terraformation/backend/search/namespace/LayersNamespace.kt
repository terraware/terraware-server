package com.terraformation.backend.search.namespace

import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchFieldNamespace
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField

class LayersNamespace(namespaces: SearchFieldNamespaces) : SearchFieldNamespace() {
  override val sublists: List<SublistField> by lazy {
    with(namespaces) {
      listOf(
          features.asMultiValueSublist("features", LAYERS.ID.eq(FEATURES.LAYER_ID)),
          sites.asSingleValueSublist("site", LAYERS.SITE_ID.eq(SITES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      with(namespaces.searchTables.layers) {
        listOf(
            timestampField("createdTime", "Layer created time", LAYERS.CREATED_TIME),
            idWrapperField("id", "Layer ID", LAYERS.ID) { LayerId(it) },
            booleanField("hidden", "Layer is hidden", LAYERS.HIDDEN),
            booleanField("proposed", "Layer is proposed", LAYERS.PROPOSED),
            textField("tileSetName", "Layer tile set name", LAYERS.TILE_SET_NAME),
            enumField("type", "Layer type", LAYERS.LAYER_TYPE_ID),
        )
      }
}
