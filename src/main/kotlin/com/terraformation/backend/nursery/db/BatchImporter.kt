package com.terraformation.backend.nursery.db

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.importer.CsvImporter
import com.terraformation.backend.importer.CsvValidator
import com.terraformation.backend.species.db.SpeciesStore
import java.io.InputStream
import java.time.LocalDate
import javax.annotation.ManagedBean
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@ManagedBean
class BatchImporter(
    private val batchStore: BatchStore,
    dslContext: DSLContext,
    fileStore: FileStore,
    private val messages: Messages,
    private val parentStore: ParentStore,
    @Lazy scheduler: JobScheduler,
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
    get() = "/csv//batches-template.csv"

  fun receiveCsv(inputStream: InputStream, fileName: String, facilityId: FacilityId): UploadId {
    requirePermissions { createBatch(facilityId) }

    if (parentStore.getFacilityType(facilityId) != FacilityType.Nursery) {
      throw FacilityTypeMismatchException(facilityId, FacilityType.Nursery)
    }

    val organizationId = parentStore.getOrganizationId(facilityId)
    return doReceiveCsv(
        inputStream, fileName, UploadType.SeedlingBatchCSV, organizationId, facilityId)
  }

  override fun getValidator(uploadsRow: UploadsRow): CsvValidator {
    return BatchCsvValidator(uploadsRow.id!!, messages)
  }

  override fun doImportCsv(
      uploadsRow: UploadsRow,
      csvReader: CSVReader,
      overwriteExisting: Boolean
  ) {
    val organizationId = uploadsRow.organizationId!!
    val facilityId = uploadsRow.facilityId!!

    requirePermissions {
      createBatch(facilityId)
      createSpecies(organizationId)
    }

    // Consume header row
    csvReader.readNext()

    dslContext.transaction { _ -> csvReader.forEach { importRow(it, organizationId, facilityId) } }
  }

  private fun importRow(
      rawValues: Array<String?>,
      organizationId: OrganizationId,
      facilityId: FacilityId
  ) {
    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    val scientificName = values[0]!!
    val commonName = values[1]
    val germinatingQuantity = values[2]?.toInt() ?: 0
    val seedlingQuantity = values[3]?.toInt() ?: 0
    val storedDate = LocalDate.parse(values[4])!!

    val speciesId =
        speciesStore.importRow(
            SpeciesRow(
                commonName = commonName,
                organizationId = organizationId,
                scientificName = scientificName),
            overwriteExisting = false)

    batchStore.create(
        BatchesRow(
            addedDate = storedDate,
            facilityId = facilityId,
            germinatingQuantity = germinatingQuantity,
            notReadyQuantity = seedlingQuantity,
            organizationId = organizationId,
            readyQuantity = 0,
            speciesId = speciesId,
        ))
  }
}
