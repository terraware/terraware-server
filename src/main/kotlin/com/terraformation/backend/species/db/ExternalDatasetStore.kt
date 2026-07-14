package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.tables.references.EXTERNAL_DATASET_IMPORTS
import jakarta.inject.Named
import java.time.LocalDate
import java.time.ZoneOffset
import org.jooq.DSLContext

@Named
class ExternalDatasetStore(private val dslContext: DSLContext) {
  /**
   * Returns the date of the most recent import of the GBIF dataset. Some datasets don't include
   * publication dates in their metadata, so fall back to the date of the most recent import in the
   * UTC time zone.
   */
  fun getDatasetDate(datasetType: ExternalDatasetType): LocalDate {
    val importRecord =
        dslContext.fetchOne(
            EXTERNAL_DATASET_IMPORTS,
            EXTERNAL_DATASET_IMPORTS.EXTERNAL_DATASET_TYPE_ID.eq(datasetType),
        ) ?: throw IllegalStateException("No import record found for $datasetType dataset")

    return importRecord.lastPublicationDate
        ?: importRecord.importedTime!!.atZone(ZoneOffset.UTC).toLocalDate()
  }
}
