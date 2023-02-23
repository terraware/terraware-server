package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.model.FileMetadata

data class ReportPhotoModel(
    val caption: String? = null,
    val fileId: FileId,
    val metadata: FileMetadata,
    val reportId: ReportId,
) {
  constructor(
      photosRow: ReportPhotosRow,
      metadataRow: FilesRow,
  ) : this(photosRow.caption, photosRow.fileId!!, FileMetadata(metadataRow), photosRow.reportId!!)
}
