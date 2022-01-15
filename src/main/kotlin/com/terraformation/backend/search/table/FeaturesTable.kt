package com.terraformation.backend.search.table

import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FuzzySearchOperators
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class FeaturesTable(private val tables: SearchTables, fuzzySearchOperators: FuzzySearchOperators) :
    SearchTable(fuzzySearchOperators) {
  override val primaryKey: TableField<out Record, out Any?>
    get() = FEATURES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          layers.asSingleValueSublist("layer", FEATURES.LAYER_ID.eq(LAYERS.ID)),
          plants.asSingleValueSublist("plant", FEATURES.ID.eq(PLANTS.FEATURE_ID)),
      )
    }
  }

  override val fields: List<SearchField> =
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

  override val inheritsPermissionsFrom: SearchTable
    get() = tables.layers

  override fun <T : Record> joinForPermissions(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(LAYERS).on(FEATURES.LAYER_ID.eq(LAYERS.ID))
  }
}
