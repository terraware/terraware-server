package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ActivityMediaType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.funder.tables.references.PUBLISHED_ACTIVITY_MEDIA_FILES
import java.time.LocalDate
import org.jooq.Field
import org.jooq.Record
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point

data class PublishedActivityMediaModel(
    val activityId: ActivityId,
    val caption: String?,
    val capturedDate: LocalDate,
    val fileId: FileId,
    val geolocation: Point?,
    val isCoverPhoto: Boolean,
    val isHiddenOnMap: Boolean,
    val listPosition: Int,
    val type: ActivityMediaType,
) {
  companion object {
    fun of(
        record: Record,
        geolocationField: Field<Geometry?> = PUBLISHED_ACTIVITY_MEDIA_FILES.GEOLOCATION,
    ): PublishedActivityMediaModel {
      return with(PUBLISHED_ACTIVITY_MEDIA_FILES) {
        PublishedActivityMediaModel(
            activityId = record[ACTIVITY_ID]!!,
            caption = record[CAPTION],
            capturedDate = record[CAPTURED_DATE]!!,
            fileId = record[FILE_ID]!!,
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
