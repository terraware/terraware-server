package com.terraformation.backend.search.table

import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class LayersTable(private val tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = LAYERS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          features.asMultiValueSublist("features", LAYERS.ID.eq(FEATURES.LAYER_ID)),
          sites.asSingleValueSublist("site", LAYERS.SITE_ID.eq(SITES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          timestampField("createdTime", "Layer created time", LAYERS.CREATED_TIME),
          idWrapperField("id", "Layer ID", LAYERS.ID) { LayerId(it) },
          booleanField("hidden", "Layer is hidden", LAYERS.HIDDEN),
          booleanField("proposed", "Layer is proposed", LAYERS.PROPOSED),
          textField("tileSetName", "Layer tile set name", LAYERS.TILE_SET_NAME),
          enumField("type", "Layer type", LAYERS.LAYER_TYPE_ID),
      )

  override val inheritsPermissionsFrom: SearchTable
    get() = tables.sites

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SITES).on(LAYERS.SITE_ID.eq(SITES.ID))
  }
}
