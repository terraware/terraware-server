package com.terraformation.backend.splat

import com.terraformation.backend.db.default_schema.AssetStatus
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.SPLATS
import com.terraformation.backend.db.default_schema.tables.references.SPLAT_ANNOTATIONS
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import java.math.BigDecimal
import org.jooq.Record

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

data class CoordinateModel(val x: BigDecimal, val y: BigDecimal, val z: BigDecimal) {
  constructor(
      x: Double,
      y: Double,
      z: Double,
  ) : this(BigDecimal.valueOf(x), BigDecimal.valueOf(y), BigDecimal.valueOf(z))
}

data class SplatAnnotationModel(
    val cameraPosition: CoordinateModel? = null,
    val fileId: FileId,
    val label: String? = null,
    val position: CoordinateModel,
    val text: String? = null,
    val title: String,
) {
  companion object {
    fun of(record: Record) =
        with(SPLAT_ANNOTATIONS) {
          SplatAnnotationModel(
              fileId = record[FILE_ID]!!,
              title = record[TITLE]!!,
              text = record[TEXT],
              label = record[LABEL],
              position =
                  CoordinateModel(
                      record[POSITION_X]!!,
                      record[POSITION_Y]!!,
                      record[POSITION_Z]!!,
                  ),
              cameraPosition =
                  record[CAMERA_POSITION_X]?.let {
                    CoordinateModel(
                        it,
                        record[CAMERA_POSITION_Y]!!,
                        record[CAMERA_POSITION_Z]!!,
                    )
                  },
          )
        }
  }
}
