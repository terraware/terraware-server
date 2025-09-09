package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.time.Instant
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point

data class ActivityMediaModel(
    val caption: String?,
    val createdBy: UserId,
    val createdTime: Instant,
    val capturedDate: LocalDate,
    val fileId: FileId,
    val geolocation: Point?,
    val isCoverPhoto: Boolean,
    val type: ActivityMediaType,
) {
  companion object {
    fun of(
        record: Record,
        geolocationField: Field<Geometry?> = ACTIVITY_MEDIA_FILES.GEOLOCATION,
    ): ActivityMediaModel {
      return ActivityMediaModel(
          caption = record[ACTIVITY_MEDIA_FILES.CAPTION],
          createdBy = record[FILES.CREATED_BY]!!,
          createdTime = record[FILES.CREATED_TIME]!!,
          capturedDate = record[ACTIVITY_MEDIA_FILES.CAPTURED_DATE]!!,
          fileId = record[ACTIVITY_MEDIA_FILES.FILE_ID]!!,
          geolocation = record[geolocationField] as? Point,
          isCoverPhoto = record[ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO]!!,
          type = record[ACTIVITY_MEDIA_FILES.ACTIVITY_MEDIA_TYPE_ID]!!,
      )
    }
  }
}
