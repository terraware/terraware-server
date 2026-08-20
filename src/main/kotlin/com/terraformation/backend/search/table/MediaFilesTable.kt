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
import com.terraformation.backend.search.field.column
import org.jooq.Condition
import org.jooq.Field
import org.jooq.OrderField
import org.jooq.Table
import org.jooq.impl.DSL

class MediaFilesTable(tables: SearchTables) : SearchTable() {
  // Columns projected by both branches of the UNION ALL.
  private val orgBranch =
      DSL.select(
              ORGANIZATION_MEDIA_FILES.FILE_ID,
              ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID,
              ORGANIZATION_MEDIA_FILES.CAPTION,
              DSL.castNull(OBSERVATION_MEDIA_FILES.OBSERVATION_ID).`as`("observation_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID).`as`("monitoring_plot_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.POSITION_ID).`as`("position_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.TYPE_ID).`as`("type_id"),
              DSL.castNull(OBSERVATION_MEDIA_FILES.IS_ORIGINAL).`as`("is_original"),
          )
          .from(ORGANIZATION_MEDIA_FILES)

  private val observationBranch =
      DSL.select(
              OBSERVATION_MEDIA_FILES.FILE_ID,
              MONITORING_PLOTS.ORGANIZATION_ID,
              OBSERVATION_MEDIA_FILES.CAPTION,
              OBSERVATION_MEDIA_FILES.OBSERVATION_ID,
              OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID,
              OBSERVATION_MEDIA_FILES.POSITION_ID,
              OBSERVATION_MEDIA_FILES.TYPE_ID,
              OBSERVATION_MEDIA_FILES.IS_ORIGINAL,
          )
          .from(OBSERVATION_MEDIA_FILES)
          .join(MONITORING_PLOTS)
          .on(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))

  private val unioned = orgBranch.unionAll(observationBranch).asTable("media_files_union")

  private val unionedFileId = unioned.column(ORGANIZATION_MEDIA_FILES.FILE_ID)

  /**
   * The two kinds of media file plus the other tables that hold their data. This is structured as a
   * subquery so we don't have to thread unique table aliases down into the UNION query; it is
   * isolated from the main query so can use the jOOQ fields directly.
   */
  override val fromTable =
      DSL.select(
              unionedFileId,
              unioned.column(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID),
              unioned.column(ORGANIZATION_MEDIA_FILES.CAPTION),
              unioned.column(OBSERVATION_MEDIA_FILES.OBSERVATION_ID),
              unioned.column(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID),
              unioned.column(OBSERVATION_MEDIA_FILES.POSITION_ID),
              unioned.column(OBSERVATION_MEDIA_FILES.TYPE_ID),
              unioned.column(OBSERVATION_MEDIA_FILES.IS_ORIGINAL),
              FILES.CONTENT_TYPE,
              FILES.CREATED_TIME,
              FILES.GEOLOCATION,
              SPLATS.ASSET_STATUS_ID,
              SPLATS.NEEDS_ATTENTION,
          )
          .from(unioned)
          .join(FILES)
          .on(unionedFileId.eq(FILES.ID))
          .leftJoin(SPLATS)
          .on(unionedFileId.eq(SPLATS.FILE_ID))
          .asTable("media_files")

  private val fileIdColumn = fromTable.column(ORGANIZATION_MEDIA_FILES.FILE_ID)
  val organizationIdColumn = fromTable.column(ORGANIZATION_MEDIA_FILES.ORGANIZATION_ID)
  private val captionColumn = fromTable.column(ORGANIZATION_MEDIA_FILES.CAPTION)
  private val observationIdColumn = fromTable.column(OBSERVATION_MEDIA_FILES.OBSERVATION_ID)
  private val monitoringPlotIdColumn = fromTable.column(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID)
  private val positionColumn = fromTable.column(OBSERVATION_MEDIA_FILES.POSITION_ID)
  private val typeColumn = fromTable.column(OBSERVATION_MEDIA_FILES.TYPE_ID)
  private val isOriginalColumn = fromTable.column(OBSERVATION_MEDIA_FILES.IS_ORIGINAL)
  private val contentTypeColumn = fromTable.column(FILES.CONTENT_TYPE)
  private val createdTimeColumn = fromTable.column(FILES.CREATED_TIME)
  private val geolocationColumn = fromTable.column(FILES.GEOLOCATION)
  private val splatStatusColumn = fromTable.column(SPLATS.ASSET_STATUS_ID)
  private val needsAttentionColumn = fromTable.column(SPLATS.NEEDS_ATTENTION)

  override val primaryKey: Field<out Any?>
    get() = fileIdColumn

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          monitoringPlots.asSingleValueSublist(
              "monitoringPlot",
              monitoringPlotIdColumn,
              MONITORING_PLOTS.ID,
          ),
          observations.asSingleValueSublist("observation", observationIdColumn, OBSERVATIONS.ID),
          organizations.asSingleValueSublist(
              "organization",
              organizationIdColumn,
              ORGANIZATIONS.ID,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          nonLocalizableEnumField("birdnetStatus") { table ->
            DSL.field(
                DSL.select(BIRDNET_RESULTS.ASSET_STATUS_ID)
                    .from(BIRDNET_RESULTS)
                    .where(BIRDNET_RESULTS.FILE_ID.eq(table.column(fileIdColumn)))
            )
          },
          textField("caption", captionColumn),
          textField("contentType", contentTypeColumn),
          timestampField("createdTime", createdTimeColumn),
          idWrapperField("fileId", fileIdColumn) { FileId(it) },
          geometryField("gpsCoordinates", geolocationColumn),
          booleanField("isOriginal", isOriginalColumn),
          coordinateField("latitude", geolocationColumn, POINT, LATITUDE),
          coordinateField("longitude", geolocationColumn, POINT, LONGITUDE),
          booleanField("needsAttention", needsAttentionColumn),
          nonLocalizableEnumField("position", positionColumn),
          nonLocalizableEnumField("splatStatus", splatStatusColumn),
          nonLocalizableEnumField("type", typeColumn),
      )

  override val defaultOrderFields: List<OrderField<*>> =
      listOf(createdTimeColumn.desc(), fileIdColumn)

  override fun conditionForVisibility(table: Table<*>): Condition {
    return table.column(organizationIdColumn).`in`(currentUser().organizationRoles.keys)
  }
}
