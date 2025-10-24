package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITY_MEDIA_FILES
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point

data class ActivityMediaModel(
    val activityId: ActivityId,
    val caption: String?,
    val capturedLocalTime: LocalDateTime,
    val createdBy: UserId,
    val createdTime: Instant,
    val fileId: FileId,
    val fileName: String,
    val geolocation: Point?,
    val isCoverPhoto: Boolean,
    val isHiddenOnMap: Boolean,
    val listPosition: Int,
    val type: ActivityMediaType,
) {
  val capturedDate: LocalDate
    get() = capturedLocalTime.toLocalDate()

  companion object {
    fun of(
        record: Record,
        geolocationField: Field<Geometry?> = FILES.GEOLOCATION,
    ): ActivityMediaModel {
      return ActivityMediaModel(
          activityId = record[ACTIVITY_MEDIA_FILES.ACTIVITY_ID]!!,
          caption = record[ACTIVITY_MEDIA_FILES.CAPTION],
          capturedLocalTime = record[FILES.CAPTURED_LOCAL_TIME]!!,
          createdBy = record[FILES.CREATED_BY]!!,
          createdTime = record[FILES.CREATED_TIME]!!,
          fileId = record[ACTIVITY_MEDIA_FILES.FILE_ID]!!,
          fileName = record[FILES.STORAGE_URL]!!.toString().substringAfterLast('/'),
          geolocation = record[geolocationField] as? Point,
          isCoverPhoto = record[ACTIVITY_MEDIA_FILES.IS_COVER_PHOTO]!!,
          isHiddenOnMap = record[ACTIVITY_MEDIA_FILES.IS_HIDDEN_ON_MAP]!!,
          listPosition = record[ACTIVITY_MEDIA_FILES.LIST_POSITION]!!,
          type = record[ACTIVITY_MEDIA_FILES.ACTIVITY_MEDIA_TYPE_ID]!!,
      )
    }
  }
}
