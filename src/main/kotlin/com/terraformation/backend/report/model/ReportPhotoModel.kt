package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.ReportPhotosRow
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata

data class ReportPhotoModel(
    val caption: String? = null,
    val metadata: ExistingFileMetadata,
    val reportId: ReportId,
) {
  constructor(
      photosRow: ReportPhotosRow,
      filesRow: FilesRow,
  ) : this(photosRow.caption, FileMetadata.of(filesRow), photosRow.reportId!!)
}
