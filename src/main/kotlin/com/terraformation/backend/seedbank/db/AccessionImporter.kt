package com.terraformation.backend.seedbank.db

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.species.db.SpeciesStore
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import javax.annotation.ManagedBean
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@ManagedBean
class AccessionImporter(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val countriesDao: CountriesDao,
    private val dslContext: DSLContext,
    private val fileStore: FileStore,
    private val messages: Messages,
    private val parentStore: ParentStore,
    @Lazy private val scheduler: JobScheduler,
    private val speciesStore: SpeciesStore,
    private val uploadProblemsDao: UploadProblemsDao,
    private val uploadsDao: UploadsDao,
    private val uploadService: UploadService,
    private val uploadStore: UploadStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  /**
   * The country code for each valid lower-case value of the collection site country column in the
   * CSV. We allow the CSV to use country codes or country names.
   */
  private val countryCodesByLowerCsvValue: Map<String, String> by lazy {
    val countries = countriesDao.findAll()
    countries.associate { it.name!!.lowercase() to it.code!! } +
        countries.associate { it.code!!.lowercase() to it.code!! }
  }

  fun getCsvTemplate(): ByteArray {
    return javaClass.getResourceAsStream("/csv/accessions-template.csv")?.use { it.readAllBytes() }
        ?: throw IllegalStateException("BUG! Can't load accessions CSV template.")
  }

  fun receiveCsv(inputStream: InputStream, fileName: String, facilityId: FacilityId): UploadId {
    requirePermissions { createAccession(facilityId) }

    val organizationId = parentStore.getOrganizationId(facilityId)
    val uploadId =
        uploadService.receive(
            inputStream, fileName, "text/csv", UploadType.AccessionCSV, organizationId, facilityId)

    val jobId = scheduler.enqueue<AccessionImporter> { validateCsv(uploadId) }

    log.info("Enqueued job $jobId to process uploaded CSV $uploadId")

    return uploadId
  }

  @Throws(UploadNotAwaitingActionException::class)
  fun cancelProcessing(uploadId: UploadId) {
    uploadStore.requireAwaitingAction(uploadId)
    uploadService.delete(uploadId)
  }

  @Throws(UploadNotAwaitingActionException::class)
  fun resolveWarnings(uploadId: UploadId, overwriteExisting: Boolean) {
    uploadStore.requireAwaitingAction(uploadId)

    dslContext.transaction { _ ->
      scheduler.enqueue<AccessionImporter> { importCsv(uploadId, overwriteExisting) }
      uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun validateCsv(uploadId: UploadId) {
    log.debug("Validating uploaded accession list $uploadId")

    withUpload(uploadId) { uploadsRow ->
      val validator =
          AccessionCsvValidator(
              uploadId,
              messages,
              countryCodesByLowerCsvValue,
              findExistingAccessionNumbers = { numbers ->
                dslContext
                    .select(ACCESSIONS.NUMBER)
                    .from(ACCESSIONS)
                    .where(ACCESSIONS.FACILITY_ID.eq(uploadsRow.facilityId))
                    .and(ACCESSIONS.NUMBER.`in`(numbers))
                    .fetch(ACCESSIONS.NUMBER)
                    .filterNotNull()
              })

      fileStore.read(uploadsRow.storageUrl!!).use { inputStream -> validator.validate(inputStream) }

      dslContext.transaction { _ ->
        // If there are errors, don't bother recording any warnings since the user will be unable
        // to resolve them anyway.
        if (validator.errors.isNotEmpty()) {
          log.info("Uploaded accession list $uploadId has validation errors")

          uploadProblemsDao.insert(validator.errors)
          uploadStore.updateStatus(uploadId, UploadStatus.Invalid)
        } else if (validator.warnings.isNotEmpty()) {
          log.info("Uploaded accession list $uploadId has warnings; awaiting user action")

          uploadProblemsDao.insert(validator.warnings)
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingUserAction)
        } else {
          log.info("Uploaded accession list $uploadId has no problems; importing it")

          scheduler.enqueue<AccessionImporter> { importCsv(uploadId, true) }
          uploadStore.updateStatus(uploadId, UploadStatus.AwaitingProcessing)
        }
      }
    }
  }

  @Suppress("MemberVisibilityCanBePrivate") // Called by JobRunr
  fun importCsv(uploadId: UploadId, overwriteExisting: Boolean) {
    withUpload(uploadId) { uploadsRow ->
      val storageUrl = uploadsRow.storageUrl!!
      val organizationId = uploadsRow.organizationId!!
      val facilityId = uploadsRow.facilityId!!

      requirePermissions {
        createAccession(facilityId)
        createSpecies(organizationId)
        readUpload(uploadId)
      }

      log.info(
          "Importing accession list $uploadId for facility $facilityId overwrite $overwriteExisting")

      fileStore.read(storageUrl).use { inputStream ->
        dslContext.transaction { _ ->
          val csvReader = CSVReader(InputStreamReader(inputStream))

          // Consume header row
          csvReader.readNext()

          csvReader.forEach { importRow(it, organizationId, facilityId, overwriteExisting) }
        }

        uploadStore.updateStatus(uploadId, UploadStatus.Completed)
      }
    }
  }

  private fun importRow(
      rawValues: Array<out String>,
      organizationId: OrganizationId,
      facilityId: FacilityId,
      overwriteExisting: Boolean
  ) {
    val values = rawValues.map { it.trim().ifEmpty { null } }

    // Our example template file has a zillion rows where only the status and collection source
    // columns have values; we don't want to try to create accessions for them if the user downloads
    // the template, edits some of the rows, and leaves the other example rows in place.
    val columnsWithValues = values.count { it != null }
    if (columnsWithValues == 0 ||
        (values[5] != null && values[14] != null && columnsWithValues == 2)) {
      return
    }

    // Scientific name and collection date are required; everything else is optional.

    val accessionNumber = values[0]
    val scientificName = values[1]
    val commonName = values[2]
    val quantity = values[3]?.let { BigDecimal(it) }
    val units = values[4]?.let { SeedQuantityUnits.forDisplayName(it) } ?: SeedQuantityUnits.Seeds
    val status = values[5]?.let { AccessionState.forDisplayName(it) } ?: AccessionState.InStorage
    val collectionDate = LocalDate.parse(values[6])
    val collectionSiteName = values[7]
    val collectionLandowner = values[8]
    val collectionCity = values[9]
    val collectionCountrySubdivision = values[10]
    val collectionCountryCode = values[11]?.let { countryCodesByLowerCsvValue[it.lowercase()] }
    val collectionSiteDescription = values[12]
    val collectorName = values[13]
    val collectionSource = values[14]?.toCollectionSource()
    val numberOfPlants = values[15]?.toInt()
    val plantId = values[16]

    val existing = accessionNumber?.let { accessionStore.fetchOneByNumber(facilityId, it) }
    if (existing != null && !overwriteExisting) {
      return
    }

    val speciesId =
        speciesStore.importRow(
            SpeciesRow(
                commonName = commonName,
                organizationId = organizationId,
                scientificName = scientificName),
            overwriteExisting = false)

    if (existing != null) {
      accessionStore.update(
          existing
              .toV2Compatible(clock)
              .copy(
                  collectionSiteCity = collectionCity,
                  collectionSiteCountryCode = collectionCountryCode,
                  collectionSiteCountrySubdivision = collectionCountrySubdivision,
                  collectionSiteLandowner = collectionLandowner,
                  collectedDate = collectionDate,
                  collectionSiteName = collectionSiteName,
                  collectionSiteNotes = collectionSiteDescription,
                  collectors = collectorName?.let { listOf(it) } ?: emptyList(),
                  collectionSource = collectionSource,
                  founderId = plantId,
                  numberOfTrees = numberOfPlants,
                  remaining = SeedQuantityModel.of(quantity, units),
                  speciesId = speciesId,
                  state = status,
              ))
    } else {
      accessionStore.create(
          AccessionModel(
              accessionNumber = accessionNumber,
              collectionSiteCity = collectionCity,
              collectionSiteCountryCode = collectionCountryCode,
              collectionSiteCountrySubdivision = collectionCountrySubdivision,
              collectionSiteLandowner = collectionLandowner,
              collectedDate = collectionDate,
              collectionSiteName = collectionSiteName,
              collectionSiteNotes = collectionSiteDescription,
              collectors = collectorName?.let { listOf(it) } ?: emptyList(),
              collectionSource = collectionSource,
              facilityId = facilityId,
              founderId = plantId,
              isManualState = true,
              numberOfTrees = numberOfPlants,
              remaining = SeedQuantityModel.of(quantity, units),
              source = DataSource.FileImport,
              speciesId = speciesId,
              state = status,
              total = SeedQuantityModel.of(quantity, units),
          ))
    }
  }

  /** Runs a function as the user who owns a particular upload. */
  private fun withUpload(uploadId: UploadId, func: (UploadsRow) -> Unit) {
    val uploadsRow =
        uploadsDao.fetchOneById(uploadId)
            ?: run {
              log.error("Upload $uploadId not found; cannot process it")
              return
            }

    val uploadUser = userStore.fetchOneById(uploadsRow.createdBy!!)
    uploadUser.run { func(uploadsRow) }
  }
}
