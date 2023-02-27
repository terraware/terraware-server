package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.file.model.ExistingFileMetadata

data class ReportFileModel(
    val metadata: ExistingFileMetadata,
    val reportId: ReportId,
)
