package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.tables.pojos.FilesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SeedFundReportPhotosRow
import com.terraformation.backend.file.model.ExistingFileMetadata
import com.terraformation.backend.file.model.FileMetadata

data class SeedFundReportPhotoModel(
    val caption: String? = null,
    val metadata: ExistingFileMetadata,
    val reportId: SeedFundReportId,
) {
  constructor(
      photosRow: SeedFundReportPhotosRow,
      filesRow: FilesRow,
  ) : this(photosRow.caption, FileMetadata.of(filesRow), photosRow.reportId!!)
}
