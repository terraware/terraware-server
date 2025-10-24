package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_MEDIA_FILES
import java.time.LocalDate
import java.time.LocalDateTime
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point

data class PublishedActivityMediaModel(
    val activityId: ActivityId,
    val caption: String?,
    val capturedLocalTime: LocalDateTime,
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
    ): PublishedActivityMediaModel {
      return with(PUBLISHED_ACTIVITY_MEDIA_FILES) {
        PublishedActivityMediaModel(
            activityId = record[ACTIVITY_ID]!!,
            caption = record[CAPTION],
            capturedLocalTime = record[FILES.CAPTURED_LOCAL_TIME]!!,
            fileId = record[FILE_ID]!!,
            fileName = record[FILES.STORAGE_URL]!!.toString().substringAfterLast('/'),
            geolocation = record[geolocationField] as? Point,
            isCoverPhoto = record[IS_COVER_PHOTO]!!,
            isHiddenOnMap = record[IS_HIDDEN_ON_MAP]!!,
            listPosition = record[LIST_POSITION]!!,
            type = record[ACTIVITY_MEDIA_TYPE_ID]!!,
        )
      }
    }
  }
}
