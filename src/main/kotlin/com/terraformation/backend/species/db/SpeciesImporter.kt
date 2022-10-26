package com.terraformation.backend.species.db

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.importer.CsvImporter
import java.io.InputStream
import javax.annotation.ManagedBean
import org.apache.commons.lang3.BooleanUtils
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@ManagedBean
class SpeciesImporter(
    dslContext: DSLContext,
    fileStore: FileStore,
    private val messages: Messages,
    // JobRunr is disabled when generating OpenAPI docs from Gradle
    @Lazy scheduler: JobScheduler,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
    uploadProblemsDao: UploadProblemsDao,
    uploadsDao: UploadsDao,
    uploadService: UploadService,
    uploadStore: UploadStore,
    userStore: UserStore,
) :
    CsvImporter(
        dslContext,
        fileStore,
        scheduler,
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore) {
  override val templatePath: String
    get() = "/csv/species-template.csv"

  fun receiveCsv(
      inputStream: InputStream,
      fileName: String,
      organizationId: OrganizationId,
  ): UploadId {
    requirePermissions { createSpecies(organizationId) }

    return doReceiveCsv(inputStream, fileName, UploadType.SpeciesCSV, organizationId)
  }

  override fun getValidator(uploadsRow: UploadsRow): SpeciesCsvValidator {
    val existingScientificNames =
        dslContext
            .select(SPECIES.SCIENTIFIC_NAME)
            .from(SPECIES)
            .where(SPECIES.ORGANIZATION_ID.eq(uploadsRow.organizationId))
            .and(SPECIES.DELETED_TIME.isNull)
            .fetch(SPECIES.SCIENTIFIC_NAME)
            .filterNotNull()
            .toSet()
    val existingRenames =
        dslContext
            .selectDistinct(SPECIES.INITIAL_SCIENTIFIC_NAME, SPECIES.SCIENTIFIC_NAME)
            .on(SPECIES.INITIAL_SCIENTIFIC_NAME)
            .from(SPECIES)
            .where(SPECIES.ORGANIZATION_ID.eq(uploadsRow.organizationId))
            .and(SPECIES.DELETED_TIME.isNull)
            .and(SPECIES.INITIAL_SCIENTIFIC_NAME.ne(SPECIES.SCIENTIFIC_NAME))
            .orderBy(SPECIES.INITIAL_SCIENTIFIC_NAME, SPECIES.SCIENTIFIC_NAME)
            .fetch { (initial, scientific) ->
              if (initial != null && scientific != null) {
                initial to scientific
              } else {
                null
              }
            }
            .filterNotNull()
            .toMap()

    return SpeciesCsvValidator(uploadsRow.id!!, existingScientificNames, existingRenames, messages)
  }

  override fun doImportCsv(
      uploadsRow: UploadsRow,
      csvReader: CSVReader,
      overwriteExisting: Boolean
  ) {
    val organizationId =
        uploadsRow.organizationId ?: throw IllegalStateException("Organization ID must be non-null")

    requirePermissions { createSpecies(organizationId) }

    var totalImported = 0

    dslContext.transaction { _ ->
      // Consume header line
      csvReader.readNext()

      csvReader
          .map { rawValues -> rawValues.map { it.trim().ifEmpty { null } } }
          .map { values ->
            SpeciesRow(
                scientificName = values[0],
                commonName = values[1],
                familyName = values[2],
                endangered = BooleanUtils.toBooleanObject(values[3]),
                rare = BooleanUtils.toBooleanObject(values[4]),
                growthFormId = values[5]?.let { GrowthForm.forDisplayName(it) },
                seedStorageBehaviorId = values[6]?.let { SeedStorageBehavior.forDisplayName(it) },
                organizationId = organizationId,
            )
          }
          .forEach { row ->
            speciesStore.importRow(row, overwriteExisting)
            totalImported++
          }

      // Check for misspelled species names or other problems by scanning all the unchecked
      // species in the organization. In theory, this could check species that we didn't just
      // import. But in practice, we try to run checks synchronously in the same transactions
      // that make changes to species data, so the only unchecked species that should be visible
      // here are the ones that have been newly inserted in the current transaction.
      speciesChecker.checkAllUncheckedSpecies(organizationId)
    }

    log.info("Processed $totalImported species from upload ${uploadsRow.id}")
  }
}
