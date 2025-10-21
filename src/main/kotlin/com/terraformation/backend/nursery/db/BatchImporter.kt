package com.terraformation.backend.nursery.db

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.SubLocationsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.i18n.toBigDecimal
import com.terraformation.backend.importer.CsvImporter
import com.terraformation.backend.importer.CsvValidator
import com.terraformation.backend.nursery.model.NewBatchModel
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.NewSpeciesModel
import jakarta.inject.Named
import java.io.InputStream
import java.time.LocalDate
import org.jobrunr.jobs.JobId
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@Named
class BatchImporter(
    private val batchStore: BatchStore,
    dslContext: DSLContext,
    fileStore: FileStore,
    private val messages: Messages,
    private val parentStore: ParentStore,
    @Lazy private val scheduler: JobScheduler,
    private val speciesStore: SpeciesStore,
    private val subLocationsDao: SubLocationsDao,
    uploadProblemsDao: UploadProblemsDao,
    uploadsDao: UploadsDao,
    uploadService: UploadService,
    uploadStore: UploadStore,
    userStore: UserStore,
) :
    CsvImporter(
        dslContext,
        fileStore,
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore,
    ) {
  override val templatePath: String
    get() = "/csv/batches-template.csv"

  fun receiveCsv(inputStream: InputStream, fileName: String, facilityId: FacilityId): UploadId {
    requirePermissions { createBatch(facilityId) }

    if (parentStore.getFacilityType(facilityId) != FacilityType.Nursery) {
      throw FacilityTypeMismatchException(facilityId, FacilityType.Nursery)
    }

    val organizationId = parentStore.getOrganizationId(facilityId)
    return doReceiveCsv(
        inputStream,
        fileName,
        UploadType.SeedlingBatchCSV,
        organizationId,
        facilityId,
    )
  }

  override fun getValidator(uploadsRow: UploadsRow): CsvValidator {
    val facilityId = uploadsRow.facilityId!!
    val subLocationNames = subLocationsDao.fetchByFacilityId(facilityId).map { it.name!! }.toSet()

    return BatchCsvValidator(uploadsRow.id!!, messages, subLocationNames)
  }

  override fun doImportCsv(
      uploadsRow: UploadsRow,
      csvReader: CSVReader,
      overwriteExisting: Boolean,
  ) {
    val organizationId = uploadsRow.organizationId!!
    val facilityId = uploadsRow.facilityId!!

    requirePermissions {
      createBatch(facilityId)
      createSpecies(organizationId)
    }

    val subLocationIds =
        subLocationsDao.fetchByFacilityId(facilityId).associate { it.name!! to it.id!! }

    // Consume header row
    csvReader.readNext()

    dslContext.transaction { _ ->
      csvReader.forEach { importRow(it, organizationId, facilityId, subLocationIds) }
    }
  }

  private fun importRow(
      rawValues: Array<String?>,
      organizationId: OrganizationId,
      facilityId: FacilityId,
      subLocationIds: Map<String, SubLocationId>,
  ) {
    val locale = currentLocale()
    val values = rawValues.map { it?.trim()?.ifEmpty { null } }

    val scientificName = values[0]!!
    val commonName = values[1]
    val germinatingQuantity = values[2]?.toBigDecimal(locale)?.toInt() ?: 0
    val seedlingQuantity = values[3]?.toBigDecimal(locale)?.toInt() ?: 0
    val readyQuantity = values[4]?.toBigDecimal(locale)?.toInt() ?: 0
    val storedDate = LocalDate.parse(values[5])
    val subLocationNames = values[6]?.lines()?.map { it.trim() } ?: emptyList()

    val speciesId =
        speciesStore.importSpecies(
            NewSpeciesModel(
                commonName = commonName,
                organizationId = organizationId,
                scientificName = scientificName,
            ),
            overwriteExisting = false,
        )

    batchStore.create(
        NewBatchModel(
            addedDate = storedDate,
            activeGrowthQuantity = seedlingQuantity,
            facilityId = facilityId,
            germinatingQuantity = germinatingQuantity,
            hardeningOffQuantity = 0,
            readyQuantity = readyQuantity,
            speciesId = speciesId,
            subLocationIds = subLocationNames.mapNotNull { subLocationIds[it] }.toSet(),
        )
    )
  }

  override fun enqueueValidateCsv(uploadId: UploadId): JobId =
      scheduler.enqueue<BatchImporter> { validateCsv(uploadId) }

  override fun enqueueImportCsv(uploadId: UploadId, overwriteExisting: Boolean): JobId =
      scheduler.enqueue<BatchImporter> { importCsv(uploadId, overwriteExisting) }
}
