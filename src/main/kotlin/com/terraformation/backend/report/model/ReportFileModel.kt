package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.file.model.FileMetadata

data class ReportFileModel(
    val fileId: FileId,
    val metadata: FileMetadata,
    val reportId: ReportId,
)
