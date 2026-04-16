package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_MEDIA_FILES
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.CoordinateField.Companion.LATITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.LONGITUDE
import com.terraformation.backend.search.field.CoordinateField.Companion.POINT
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class MediaFilesTable(tables: SearchTables) : SearchTable() {
  private val filesAlias = FILES.`as`("media_files_files")

  // Columns projected by both branches of the UNION ALL.
  private val orgBranch =
      DSL.select(
              ORGANIZATION_MEDIA_FILES.FILE_ID.`as`("file_id"),
              ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.`as`("organization_id"),
              ORGANIZATION_MEDIA_FILES.CAPTION.`as`("caption"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.OBSERVATION_ID.dataType).`as`("observation_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.dataType)
                  .`as`("monitoring_plot_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.POSITION_ID.dataType).`as`("position_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.TYPE_ID.dataType).`as`("type_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.IS_ORIGINAL.dataType).`as`("is_original"),
          )
          .from(ORGANIZATION_MEDIA_FILES)

  private val observationBranch =
      DSL.select(
              OBSERVATION_MEDIA_FILES.FILE_ID.`as`("file_id"),
              MONITORING_PLOTS.ORGANIZATION_ID.`as`("organization_id"),
              OBSERVATION_MEDIA_FILES.CAPTION.`as`("caption"),
              OBSERVATION_MEDIA_FILES.OBSERVATION_ID.`as`("observation_id"),
              OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.`as`("monitoring_plot_id"),
              OBSERVATION_MEDIA_FILES.POSITION_ID.`as`("position_id"),
              OBSERVATION_MEDIA_FILES.TYPE_ID.`as`("type_id"),
              OBSERVATION_MEDIA_FILES.IS_ORIGINAL.`as`("is_original"),
          )
          .from(OBSERVATION_MEDIA_FILES)
          .join(MONITORING_PLOTS)
          .on(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))

  private val unified = orgBranch.unionAll(observationBranch).asTable("media_files")

  private val fileIdColumn = unified.field("file_id", ORGANIZATION_MEDIA_FILES.FILE_ID.dataType)!!
  val organizationIdColumn =
      unified.field("organization_id", ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID.dataType)!!
  private val captionColumn = unified.field("caption", ORGANIZATION_MEDIA_FILES.CAPTION.dataType)!!
  private val observationIdColumn =
      unified.field("observation_id", OBSERVATION_MEDIA_FILES.OBSERVATION_ID.dataType)!!
  private val monitoringPlotIdColumn =
      unified.field("monitoring_plot_id", OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.dataType)!!
  private val positionColumn =
      unified.field("position_id", OBSERVATION_MEDIA_FILES.POSITION_ID.dataType)!!
  private val typeColumn = unified.field("type_id", OBSERVATION_MEDIA_FILES.TYPE_ID.dataType)!!
  private val isOriginalColumn =
      unified.field("is_original", OBSERVATION_MEDIA_FILES.IS_ORIGINAL.dataType)!!

  // Must reference the derived `unified` table, not ORGANIZATION_MEDIA_FILES.FILE_ID, because
  // NestedQueryBuilder.filterResults uses primaryKey in SQL against fromTable. The cast is
  // needed because Table.field(name) on a derived table returns Field, not TableField.
  @Suppress("UNCHECKED_CAST")
  override val primaryKey: TableField<out Record, out Any?> =
      unified.field("file_id")!! as TableField<out Record, out Any?>

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              monitoringPlotIdColumn.eq(MONITORING_PLOTS.ID),
              isRequired = false,
          ),
          observations.asSingleValueSublist(
              "observation",
              observationIdColumn.eq(OBSERVATIONS.ID),
              isRequired = false,
          ),
          organizations.asSingleValueSublist(
              "organization",
              organizationIdColumn.eq(ORGANIZATIONS.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          nonLocalizableEnumField(
              "birdnetStatus",
              DSL.field(
                  DSL.select(BIRDNET_RESULTS.ASSET_STATUS_ID)
                      .from(BIRDNET_RESULTS)
                      .where(BIRDNET_RESULTS.FILE_ID.eq(fileIdColumn))
              ),
          ),
          textField("caption", captionColumn),
          textField("contentType", filesAlias.CONTENT_TYPE),
          timestampField("createdTime", filesAlias.CREATED_TIME),
          idWrapperField("fileId", fileIdColumn) { FileId(it) },
          geometryField("gpsCoordinates", filesAlias.GEOLOCATION),
          booleanField("isOriginal", isOriginalColumn),
          coordinateField("latitude", filesAlias.GEOLOCATION, POINT, LATITUDE),
          coordinateField("longitude", filesAlias.GEOLOCATION, POINT, LONGITUDE),
          booleanField("needsAttention", SPLATS.NEEDS_ATTENTION),
          nonLocalizableEnumField("position", positionColumn),
          nonLocalizableEnumField("splatStatus", SPLATS.ASSET_STATUS_ID),
          nonLocalizableEnumField("type", typeColumn),
      )

  override val defaultOrderFields: List<OrderField<*>> =
      listOf(filesAlias.CREATED_TIME.desc(), fileIdColumn)

  override val fromTable
    get() =
        unified
            .join(filesAlias)
            .on(fileIdColumn.eq(filesAlias.ID))
            .leftJoin(SPLATS)
            .on(fileIdColumn.eq(SPLATS.FILE_ID))

  override fun conditionForVisibility(): Condition {
    return organizationIdColumn.`in`(currentUser().organizationRoles.keys)
  }
}
