package com.terraformation.backend.splat

import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import org.jooq.Record
import org.locationtech.jts.geom.Point

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

data class SplatAnnotationModel(
    val fileId: FileId,
    val title: String,
    val text: String?,
    val label: String?,
    val position: Point,
    val cameraPosition: Point?,
) {
  companion object {
    fun of(record: Record) =
        SplatAnnotationModel(
            fileId = record[SPLAT_ANNOTATIONS.FILE_ID]!!,
            title = record[SPLAT_ANNOTATIONS.TITLE]!!,
            text = record[SPLAT_ANNOTATIONS.TEXT],
            label = record[SPLAT_ANNOTATIONS.LABEL],
            position = record[SPLAT_ANNOTATIONS.POSITION]!! as Point,
            cameraPosition = record[SPLAT_ANNOTATIONS.CAMERA_POSITION] as Point?,
        )
  }
}
