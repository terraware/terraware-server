package com.terraformation.backend.report.model

import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.file.model.ExistingFileMetadata

data class SeedFundReportFileModel(
    val metadata: ExistingFileMetadata,
    val reportId: SeedFundReportId,
)
