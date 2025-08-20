package com.terraformation.backend.seedbank.db

import com.opencsv.CSVReader
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.daos.UploadProblemsDao
import com.terraformation.backend.db.default_schema.tables.daos.UploadsDao
import com.terraformation.backend.db.default_schema.tables.pojos.UploadsRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.i18n.toBigDecimal
import com.terraformation.backend.importer.CsvImporter
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.model.NewSpeciesModel
import jakarta.inject.Named
import java.io.InputStream
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import org.jobrunr.jobs.JobId
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.annotation.Lazy

@Named
class AccessionImporter(
    private val accessionStore: AccessionStore,
    dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    fileStore: FileStore,
    private val messages: Messages,
    private val parentStore: ParentStore,
    @Lazy private val scheduler: JobScheduler,
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
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore,
    ) {
  override val templatePath: String
    get() = "/csv/accessions-template.csv"

  private val countryCodesByLowerCsvValue = ConcurrentHashMap<Locale, Map<String, String>>()

  fun receiveCsv(inputStream: InputStream, fileName: String, facilityId: FacilityId): UploadId {
    requirePermissions { createAccession(facilityId) }

    return doReceiveCsv(
        inputStream,
        fileName,
        UploadType.AccessionCSV,
        parentStore.getOrganizationId(facilityId),
        facilityId,
    )
  }

  override fun getValidator(uploadsRow: UploadsRow): AccessionCsvValidator {
    val validator =
        AccessionCsvValidator(
            uploadsRow.id!!,
            messages,
            this::getCountryCode,
            findExistingAccessionNumbers = { numbers ->
              dslContext
                  .select(ACCESSIONS.NUMBER)
                  .from(ACCESSIONS)
                  .where(ACCESSIONS.FACILITY_ID.eq(uploadsRow.facilityId))
                  .and(ACCESSIONS.NUMBER.`in`(numbers))
                  .fetch(ACCESSIONS.NUMBER.asNonNullable())
            },
        )
    return validator
  }

  override fun doImportCsv(
      uploadsRow: UploadsRow,
      csvReader: CSVReader,
      overwriteExisting: Boolean,
  ) {
    val organizationId = uploadsRow.organizationId!!
    val facilityId = uploadsRow.facilityId!!
    val clocks = mutableMapOf<FacilityId, Clock>()

    requirePermissions {
      createAccession(facilityId)
      createSpecies(organizationId)
    }

    // Consume header row
    csvReader.readNext()

    dslContext.transaction { _ ->
      csvReader.forEach { rawValues ->
        val clock = clocks.getOrPut(facilityId) { facilityStore.getClock(facilityId) }
        importRow(rawValues, organizationId, facilityId, clock, overwriteExisting)
      }
    }
  }

  private fun importRow(
      rawValues: Array<out String>,
      organizationId: OrganizationId,
      facilityId: FacilityId,
      clock: Clock,
      overwriteExisting: Boolean,
  ) {
    val values = rawValues.map { it.trim().ifEmpty { null } }

    // Our example template file has a zillion rows where only the status and collection source
    // columns have values; we don't want to try to create accessions for them if the user downloads
    // the template, edits some of the rows, and leaves the other example rows in place.
    val columnsWithValues = values.count { it != null }
    if (
        columnsWithValues == 0 ||
            (values[5] != null && values[14] != null && columnsWithValues == 2)
    ) {
      return
    }

    val locale = currentLocale()

    // Scientific name and collection date are required; everything else is optional.

    val accessionNumber = values[0]
    val scientificName = values[1]!!
    val commonName = values[2]
    val quantity = values[3]?.toBigDecimal(locale)
    val units =
        values[4]?.let { SeedQuantityUnits.forDisplayName(it, locale) } ?: SeedQuantityUnits.Seeds
    val status =
        values[5]?.let { AccessionState.forDisplayName(it, locale) } ?: AccessionState.InStorage
    val collectionDate = LocalDate.parse(values[6])
    val collectionSiteName = values[7]
    val collectionLandowner = values[8]
    val collectionCity = values[9]
    val collectionCountrySubdivision = values[10]
    val collectionCountryCode = values[11]?.let { getCountryCode(it) }
    val collectionSiteDescription = values[12]
    val collectorName = values[13]
    val collectionSource = values[14]?.toCollectionSource(locale)
    val numberOfPlants =
        values[15]?.let { NumberFormat.getIntegerInstance(locale).parse(it).toInt() }
    val plantId = values[16]
    val latitude = values[17]?.toBigDecimal(locale)
    val longitude = values[18]?.toBigDecimal(locale)

    val geolocations =
        if (latitude != null && longitude != null) {
          setOf(Geolocation(latitude, longitude))
        } else {
          emptySet()
        }

    val existing = accessionNumber?.let { accessionStore.fetchOneByNumber(facilityId, it) }
    if (existing != null && !overwriteExisting) {
      return
    }

    val speciesId =
        speciesStore.importSpecies(
            NewSpeciesModel(
                commonName = commonName,
                organizationId = organizationId,
                scientificName = scientificName,
            ),
            overwriteExisting = false,
        )

    if (existing != null) {
      accessionStore.update(
          existing.copy(
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
              geolocations = geolocations,
              numberOfTrees = numberOfPlants,
              remaining = SeedQuantityModel.of(quantity, units),
              speciesId = speciesId,
              state = status,
          )
      )
    } else {
      accessionStore.create(
          AccessionModel(
              accessionNumber = accessionNumber,
              clock = clock,
              collectedDate = collectionDate,
              collectionSiteCity = collectionCity,
              collectionSiteCountryCode = collectionCountryCode,
              collectionSiteCountrySubdivision = collectionCountrySubdivision,
              collectionSiteLandowner = collectionLandowner,
              collectionSiteName = collectionSiteName,
              collectionSiteNotes = collectionSiteDescription,
              collectionSource = collectionSource,
              collectors = collectorName?.let { listOf(it) } ?: emptyList(),
              facilityId = facilityId,
              founderId = plantId,
              geolocations = geolocations,
              numberOfTrees = numberOfPlants,
              remaining = SeedQuantityModel.of(quantity, units),
              source = DataSource.FileImport,
              speciesId = speciesId,
              state = status,
          )
      )
    }
  }

  override fun enqueueValidateCsv(uploadId: UploadId): JobId =
      scheduler.enqueue<AccessionImporter> { validateCsv(uploadId) }

  override fun enqueueImportCsv(uploadId: UploadId, overwriteExisting: Boolean): JobId =
      scheduler.enqueue<AccessionImporter> { importCsv(uploadId, overwriteExisting) }

  /**
   * Returns the country code for a CSV country value, which can either be a country code or a
   * country name in the current locale, case-insensitive.
   */
  private fun getCountryCode(value: String): String? {
    val locale = currentLocale()
    val lowerValue = value.lowercase(locale)
    val mapping =
        countryCodesByLowerCsvValue.getOrPut(locale) {
          val bundle = ResourceBundle.getBundle("i18n.Countries", locale)
          val countryCodes = bundle.keySet()

          countryCodes.associateBy { it.lowercase(locale) } +
              countryCodes.associateBy { bundle.getString(it).lowercase(locale) }
        }

    return mapping[lowerValue]
  }
}
