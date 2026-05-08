package com.terraformation.backend.splat

import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SplatAnnotationId
import com.terraformation.backend.db.default_schema.tables.references.BIRDNET_RESULTS
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import java.math.BigDecimal
import java.net.URI
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel

data class ObservationSplatModel(
    val assetStatus: AssetStatus,
    val fileId: FileId,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
) {
  companion object {
    fun of(record: Record) =
        ObservationSplatModel(
            assetStatus = record[SPLATS.ASSET_STATUS_ID]!!,
            fileId = record[SPLATS.FILE_ID]!!,
            monitoringPlotId = record[OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID]!!,
            observationId = record[OBSERVATION_MEDIA_FILES.OBSERVATION_ID]!!,
        )
  }
}

data class SplatInfoModel(
    val annotations: List<SplatAnnotationModel<SplatAnnotationId>>,
    val cameraPosition: CoordinateModel?,
    val groundColor: String?,
    val originPosition: CoordinateModel?,
    val sceneBounds: CoordinateModel? = null,
    val skyColor: String?,
)

data class ObservationBirdnetResultModel(
    val assetStatus: AssetStatus,
    val fileId: FileId,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val resultsStorageUrl: URI?,
) {
  companion object {
    fun of(record: Record) =
        ObservationBirdnetResultModel(
            assetStatus = record[BIRDNET_RESULTS.ASSET_STATUS_ID]!!,
            fileId = record[BIRDNET_RESULTS.FILE_ID]!!,
            monitoringPlotId = record[OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID]!!,
            observationId = record[OBSERVATION_MEDIA_FILES.OBSERVATION_ID]!!,
            resultsStorageUrl = record[BIRDNET_RESULTS.RESULTS_STORAGE_URL],
        )
  }
}

data class ModelMetadataModel(
    val groundColor: String? = null,
    val sceneBounds: CoordinateModel? = null,
    val skyColor: String? = null,
)

data class CoordinateModel(
    val x: BigDecimal,
    val y: BigDecimal,
    val z: BigDecimal,
    val m: BigDecimal? = null,
) {
  constructor(
      x: Double,
      y: Double,
      z: Double,
      m: Double? = null,
  ) : this(
      BigDecimal.valueOf(x),
      BigDecimal.valueOf(y),
      BigDecimal.valueOf(z),
      m?.let { BigDecimal.valueOf(it) },
  )

  fun toPoint(): Point =
      cartesianGeometryFactory.createPoint(Coordinate(x.toDouble(), y.toDouble(), z.toDouble()))

  companion object {
    fun of(record: Record, pointField: Field<Geometry?>): CoordinateModel? =
        (record.get(pointField) as Point?)?.let {
          val m = it.coordinate.m.takeUnless { v -> v.isNaN() }
          CoordinateModel(x = it.x, y = it.y, z = it.coordinate.z, m = m)
        }
  }
}

private val cartesianGeometryFactory = GeometryFactory(PrecisionModel(), 0)

@Suppress("UNCHECKED_CAST")
fun CoordinateModel?.toPointField(): Field<Geometry?> =
    if (this == null) {
      DSL.castNull(SQLDataType.GEOMETRY) as Field<Geometry?>
    } else if (m != null) {
      DSL.field(
          "ST_MakePoint({0}, {1}, {2}, {3})",
          SQLDataType.GEOMETRY,
          DSL.value(x),
          DSL.value(y),
          DSL.value(z),
          DSL.value(m),
      ) as Field<Geometry?>
    } else {
      DSL.field(
          "ST_MakePoint({0}, {1}, {2})",
          SQLDataType.GEOMETRY,
          DSL.value(x),
          DSL.value(y),
          DSL.value(z),
      ) as Field<Geometry?>
    }

data class SplatAnnotationModel<AnnotationId : SplatAnnotationId?>(
    val id: AnnotationId,
    val bodyText: String? = null,
    val cameraPosition: CoordinateModel? = null,
    val fileId: FileId,
    val label: String? = null,
    val position: CoordinateModel,
    val title: String,
) {
  companion object {
    fun of(record: Record): ExistingSplatAnnotationModel =
        with(SPLAT_ANNOTATIONS) {
          ExistingSplatAnnotationModel(
              id = record[ID]!!,
              bodyText = record[BODY_TEXT],
              cameraPosition = CoordinateModel.of(record, CAMERA_POSITION),
              fileId = record[FILE_ID]!!,
              label = record[LABEL],
              position = CoordinateModel.of(record, POSITION)!!,
              title = record[TITLE]!!,
          )
        }
  }
}

typealias ExistingSplatAnnotationModel = SplatAnnotationModel<SplatAnnotationId>

typealias NewSplatAnnotationModel = SplatAnnotationModel<Nothing?>

data class SplatGenerationParams(
    val abortAfter: String? = null,
    val restartAt: String? = null,
    val stepArgs: Map<String, List<String>> = emptyMap(),
)
