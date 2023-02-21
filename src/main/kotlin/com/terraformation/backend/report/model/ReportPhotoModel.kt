package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.PhotoId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow

data class ReportPhotoModel(
    val caption: String? = null,
    val photoId: PhotoId,
    val reportId: ReportId,
) {
  constructor(row: ReportPhotosRow) : this(row.caption, row.photoId!!, row.reportId!!)
}
