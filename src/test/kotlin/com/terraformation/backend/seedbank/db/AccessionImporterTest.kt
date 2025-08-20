package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.GeolocationId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionQuantityHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.pojos.GeolocationsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mapTo1IndexedIds
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val publisher = TestEventPublisher()
  private val accessionStore: AccessionStore by lazy {
    AccessionStore(
        dslContext,
        BagStore(dslContext),
        facilitiesDao,
        GeolocationStore(dslContext, clock),
        ViabilityTestStore(dslContext),
        parentStore,
        WithdrawalStore(dslContext, clock, messages, parentStore),
        clock,
        publisher,
        messages,
        IdentifierGenerator(clock, dslContext),
    )
  }
  private val clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
  private val facilityStore: FacilityStore by lazy {
    FacilityStore(
        clock,
        mockk(),
        dslContext,
        publisher,
        facilitiesDao,
        messages,
        organizationsDao,
        subLocationsDao,
    )
  }
  private val fileStore: FileStore = mockk()
  private val importer: AccessionImporter by lazy {
    AccessionImporter(
        accessionStore,
        dslContext,
        facilityStore,
        fileStore,
        messages,
        parentStore,
        scheduler,
        speciesStore,
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore,
    )
  }
  private val messages: Messages = Messages()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val scheduler: JobScheduler = mockk()
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao,
    )
  }
  private val uploadService: UploadService = mockk()
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }
  private val userStore: UserStore = mockk()

  private lateinit var facilityId: FacilityId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    val userId = user.userId

    every { user.canCreateAccession(any()) } returns true
    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canReadUpload(any()) } returns true
    every { user.canUpdateAccession(any()) } returns true
    every { user.canUpdateUpload(any()) } returns true
    every { userStore.fetchOneById(userId) } returns user

    organizationId = insertOrganization()
    facilityId = insertFacility()
  }

  @Nested
  inner class HappyPath {
    @Test
    fun `end-to-end happy path causes accessions and species to be created`() {
      runHappyPath(
          "HappyPath.csv",
          Locale.ENGLISH,
          listOf(
              SpeciesRow(
                  id = SpeciesId(1),
                  organizationId = organizationId,
                  scientificName = "New species var. new",
                  commonName = "New common name",
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  initialScientificName = "New species var. new",
              )
          ),
          listOf(
              AccessionsRow(
                  collectedDate = LocalDate.of(2022, 3, 4),
                  collectionSiteCity = "City name",
                  collectionSiteCountryCode = "US",
                  collectionSiteCountrySubdivision = "Hawaii",
                  collectionSiteLandowner = "New landowner",
                  collectionSiteName = "New site name",
                  collectionSiteNotes = "Site description\nwith multiple\nlines",
                  collectionSourceId = CollectionSource.Reintroduced,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.FileImport,
                  estSeedCount = 100,
                  facilityId = facilityId,
                  founderId = "PlantID",
                  id = AccessionId(1),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "12345",
                  remainingQuantity = BigDecimal(100),
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  speciesId = SpeciesId(1),
                  stateId = AccessionState.Drying,
                  treesCollectedFrom = 5,
              ),
              AccessionsRow(
                  collectedDate = LocalDate.of(2022, 3, 5),
                  collectionSiteCountryCode = "UG",
                  collectionSourceId = CollectionSource.Wild,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.FileImport,
                  estWeightGrams = BigDecimal(101000),
                  estWeightQuantity = BigDecimal(101),
                  estWeightUnitsId = SeedQuantityUnits.Kilograms,
                  facilityId = facilityId,
                  id = AccessionId(2),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "70-1-1-001",
                  remainingGrams = BigDecimal(101000),
                  remainingQuantity = BigDecimal(101),
                  remainingUnitsId = SeedQuantityUnits.Kilograms,
                  speciesId = SpeciesId(1),
                  stateId = AccessionState.InStorage,
              ),
          ),
          listOf(AccessionCollectorsRow(AccessionId(1), 0, "Collector,Name")),
          listOf(
              GeolocationsRow(
                  accessionId = AccessionId(2),
                  createdTime = Instant.EPOCH,
                  id = GeolocationId(1),
                  latitude = BigDecimal("12.345678"),
                  longitude = BigDecimal("-87.654321"),
              )
          ),
      )
    }

    @Test
    fun `can set status to Used Up if quantity is zero`() {

      runHappyPath(
          "UsedUp.csv",
          Locale.ENGLISH,
          listOf(
              SpeciesRow(
                  id = SpeciesId(1),
                  organizationId = organizationId,
                  scientificName = "New species",
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  initialScientificName = "New species",
              )
          ),
          listOf(
              AccessionsRow(
                  collectedDate = LocalDate.of(2023, 6, 1),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.FileImport,
                  estSeedCount = 0,
                  facilityId = facilityId,
                  id = AccessionId(1),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "1",
                  remainingQuantity = BigDecimal.ZERO,
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  speciesId = SpeciesId(1),
                  stateId = AccessionState.UsedUp,
              ),
              AccessionsRow(
                  collectedDate = LocalDate.of(2023, 6, 1),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.FileImport,
                  estWeightGrams = BigDecimal.ZERO,
                  estWeightQuantity = BigDecimal.ZERO,
                  estWeightUnitsId = SeedQuantityUnits.Grams,
                  facilityId = facilityId,
                  id = AccessionId(2),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "2",
                  remainingGrams = BigDecimal.ZERO,
                  remainingQuantity = BigDecimal.ZERO,
                  remainingUnitsId = SeedQuantityUnits.Grams,
                  speciesId = SpeciesId(1),
                  stateId = AccessionState.UsedUp,
              ),
          ),
          emptyList(),
          emptyList(),
      )
    }

    @Test
    fun `valid localized file causes accessions and species to be created`() {
      runHappyPath(
          "Gibberish.csv",
          Locales.GIBBERISH,
          listOf(
              SpeciesRow(
                  id = SpeciesId(1),
                  organizationId = organizationId,
                  scientificName = "New species var. new",
                  commonName = "New common name",
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  initialScientificName = "New species var. new",
              )
          ),
          listOf(
              AccessionsRow(
                  collectedDate = LocalDate.of(2022, 3, 5),
                  collectionSiteCountryCode = "UG",
                  collectionSourceId = CollectionSource.Wild,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.FileImport,
                  estWeightGrams = BigDecimal(123456780),
                  estWeightQuantity = BigDecimal("123456.78"),
                  estWeightUnitsId = SeedQuantityUnits.Kilograms,
                  facilityId = facilityId,
                  id = AccessionId(1),
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "12345",
                  remainingGrams = BigDecimal(123456780),
                  remainingQuantity = BigDecimal("123456.78"),
                  remainingUnitsId = SeedQuantityUnits.Kilograms,
                  speciesId = SpeciesId(1),
                  stateId = AccessionState.InStorage,
              ),
          ),
          emptyList(),
          listOf(
              GeolocationsRow(
                  accessionId = AccessionId(1),
                  createdTime = Instant.EPOCH,
                  id = GeolocationId(1),
                  latitude = BigDecimal("12.345678"),
                  longitude = BigDecimal("-87.654321"),
              )
          ),
      )
    }

    /**
     * Runs a scenario with a successful import and verifies that the expected rows have been
     * inserted into the database.
     *
     * The IDs in the expected entity lists are assumed to start with 1.
     */
    private fun runHappyPath(
        filename: String,
        locale: Locale,
        expectedSpecies: List<SpeciesRow>,
        expectedAccessions: List<AccessionsRow>,
        expectedCollectors: List<AccessionCollectorsRow> = emptyList(),
        expectedGeolocations: List<GeolocationsRow> = emptyList(),
    ) {
      val csvContent =
          javaClass.getResourceAsStream("/seedbank/accession/$filename")!!.use { inputStream ->
            inputStream.readAllBytes()
          }

      val slot: CapturingSlot<IocJobLambda<AccessionImporter>> = slot()
      every { scheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())
      every { uploadService.receive(any(), any(), any(), any(), any(), any(), any()) } answers
          {
            insertAccessionUpload(csvContent, UploadStatus.AwaitingValidation, locale)
          }

      locale.use { importer.receiveCsv(csvContent.inputStream(), filename, facilityId) }

      // Validate (no problems found since this is the happy path) -- this will cause slot.captured
      // to be updated to point to importCsv()
      slot.captured.accept(importer)

      assertJsonEquals(
          emptyList<UploadProblemsRow>(),
          uploadProblemsDao.findAll(),
          "Problems after validation",
      )
      assertEquals(
          UploadStatus.AwaitingProcessing,
          uploadsDao.fetchOneById(inserted.uploadId)?.statusId,
          "Status after validation",
      )

      // Import
      slot.captured.accept(importer)

      assertEquals(
          UploadStatus.Completed,
          uploadsDao.fetchOneById(inserted.uploadId)?.statusId,
          "Status after import",
      )

      val actualSpecies = speciesDao.findAll().sortedBy { it.id }
      val actualAccessions = accessionsDao.findAll().sortedBy { it.id }
      val mappedSpeciesIds = mapTo1IndexedIds(actualSpecies, ::SpeciesId, SpeciesRow::id)
      val mappedAccessionIds = mapTo1IndexedIds(actualAccessions, ::AccessionId, AccessionsRow::id)

      assertEquals(
          expectedSpecies.map { it.copy(id = null) },
          actualSpecies.map { it.copy(id = null) },
          "Imported species",
      )

      assertJsonEquals(
          expectedAccessions.map { it.copy(id = null, speciesId = mappedSpeciesIds[it.speciesId]) },
          actualAccessions.map { it.copy(id = null) },
          "Imported accessions",
      )

      assertEquals(
          expectedCollectors.map { it.copy(accessionId = mappedAccessionIds[it.accessionId]) },
          accessionCollectorsDao.findAll().sortedBy { it.accessionCollectorId.toString() },
          "Imported collectors",
      )

      assertEquals(
          expectedGeolocations.map {
            it.copy(id = null, accessionId = mappedAccessionIds[it.accessionId])
          },
          geolocationsDao
              .findAll()
              .sortedBy { it.id }
              .map {
                it.copy(
                    id = null,
                    latitude = it.latitude?.stripTrailingZeros(),
                    longitude = it.longitude?.stripTrailingZeros(),
                )
              },
          "Imported geolocations",
      )
    }
  }

  @Nested
  inner class ReceiveCsv {
    @Test
    fun `schedules validate job`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      every { uploadService.receive(any(), any(), any(), any(), any(), any()) } returns UploadId(1)

      importer.receiveCsv(ByteArrayInputStream(ByteArray(1)), "test", facilityId)

      verify { scheduler.enqueue<AccessionImporter>(any()) }
    }
  }

  @Nested
  inner class ValidateCsv {
    @Test
    fun `accepts template file`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(importer.getCsvTemplate(), UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `accepts localized template file`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      val template = Locales.GIBBERISH.use { importer.getCsvTemplate() }
      testValidation(template, UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `accepts localized values`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())

      val grams = "Grams".toGibberish()
      val drying = "Drying".toGibberish()
      val california = "California".toGibberish()
      val us = "United States".toGibberish()
      val wild = "Wild".toGibberish()

      testValidation(
          ",Species name,Common name,1 234,$grams,$drying,2023-01-01,Site,Landowner,City," +
              "$california,$us,Description,Collector,$wild,1,ID,\"-13,45\",\"18,5578\"",
          UploadStatus.AwaitingProcessing,
          Locales.GIBBERISH,
      )
    }

    @Test
    fun `accepts rows where all columns are blank`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(",,,,  ,,,,,    ,,,\"  \",,,,,,\n", UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `accepts rows with country codes`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,uS,,,,,,,\n",
          UploadStatus.AwaitingProcessing,
      )
    }

    @Test
    fun `accepts rows with country names`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,canada,,,,,,,\n",
          UploadStatus.AwaitingProcessing,
      )
    }

    @Test
    fun `rejects rows with wrong number of columns`() {
      testValidation(
          ",Species name,,,,",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 2,
              message = messages.csvWrongFieldCount(19, 6),
          ),
      )
    }

    @Test
    fun `rejects rows with malformed scientific names`() {
      testValidation(
          ",,,,,,2022-03-04,,,,,,,,,,,,\n" +
              ",Name,,,,,2022-03-04,,,,,,,,,,,,\n" +
              ",A very long name with too many words,,,,,2022-03-04,,,,,,,,,,,,\n" +
              ",Bad name?,,,,,2022-03-04,,,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 2,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameMissing(),
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameTooShort(),
              value = "Name",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameTooLong(),
              value = "A very long name with too many words",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 5,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameInvalidChar("?"),
              value = "Bad name?",
          ),
      )
    }

    @Test
    fun `rejects rows with malformed collection dates`() {
      testValidation(
          ",Scientific name,,,,,,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,January 6,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022-99-99,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022/03/04,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022-3-4,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,20220304,,,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 2,
              field = "Collection Date",
              message = messages.csvRequiredFieldMissing(),
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "January 6",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022-99-99",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 5,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022/03/04",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 6,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022-3-4",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 7,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "20220304",
          ),
      )
    }

    @Test
    fun `rejects rows with malformed statuses`() {
      testValidation(
          ",Scientific name,,,,Bogus,2022-03-04,,,,,,,,,,,,\n" +
              // Withdrawn is a v1-only state
              ",Scientific name,,,,Withdrawn,2022-03-04,,,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Status",
              message = messages.accessionCsvStatusInvalid(),
              value = "Bogus",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 3,
              field = "Status",
              message = messages.accessionCsvStatusInvalid(),
              value = "Withdrawn",
          ),
      )
    }

    @Test
    fun `rejects rows with Used Up status and nonzero quantities`() {
      testValidation(
          ",Scientific name,,1,Seeds,Used Up,2023-01-01,,,,,,,,,,,,",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 2,
              field = "QTY",
              message = messages.accessionCsvNonZeroUsedUpQuantity(),
              value = "1",
          ),
      )
    }

    @Test
    fun `rejects rows with malformed collection sources`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,,,,Unknown,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Collection Source",
              message = messages.accessionCsvCollectionSourceInvalid(),
              value = "Unknown",
          ),
      )
    }

    @Test
    fun `rejects rows with malformed latitudes`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,,,,,,,xyzzy,1\n" +
              ",Scientific name,,,,,2022-03-04,,,,,,,,,,,-91,1\n" +
              ",Scientific name,,,,,2022-03-04,,,,,,,,,,,91,1\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 2,
              field = "Latitude",
              message = messages.accessionCsvLatitudeInvalid(),
              value = "xyzzy",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Latitude",
              message = messages.accessionCsvLatitudeInvalid(),
              value = "-91",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Latitude",
              message = messages.accessionCsvLatitudeInvalid(),
              value = "91",
          ),
      )
    }

    @Test
    fun `rejects rows with malformed longitudes`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,,,,,,,1,xyzzy\n" +
              ",Scientific name,,,,,2022-03-04,,,,,,,,,,,1,-181\n" +
              ",Scientific name,,,,,2022-03-04,,,,,,,,,,,1,181\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 2,
              field = "Longitude",
              message = messages.accessionCsvLongitudeInvalid(),
              value = "xyzzy",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Longitude",
              message = messages.accessionCsvLongitudeInvalid(),
              value = "-181",
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Longitude",
              message = messages.accessionCsvLongitudeInvalid(),
              value = "181",
          ),
      )
    }

    @Test
    fun `rejects rows with only latitude or only longitude`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,,,,,,,,-1\n" +
              ",Scientific name,,,,,2022-03-04,,,,,,,,,,,1,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 2,
              field = "Latitude",
              message = messages.accessionCsvLatitudeLongitude(),
              value = null,
          ),
          UploadProblemsRow(
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 3,
              field = "Longitude",
              message = messages.accessionCsvLatitudeLongitude(),
              value = null,
          ),
      )
    }

    @Test
    fun `rejects rows with bogus countries`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,Unknown,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Country",
              message = messages.accessionCsvCountryInvalid(),
              value = "Unknown",
          ),
      )
    }

    @Test
    fun `detects existing accession numbers`() {
      insertAccession(number = "123")

      testValidation(
          "123,Scientific name,,,,,2022-03-04,,,,,,,,,,,,\n",
          UploadStatus.AwaitingUserAction,
          UploadProblemsRow(
              typeId = UploadProblemType.DuplicateValue,
              isError = false,
              position = 2,
              field = "Accession Number",
              message = messages.accessionCsvNumberExists(),
              value = "123",
          ),
      )
    }

    @Test
    fun `rejects duplicate accession numbers in CSV file`() {
      testValidation(
          "123,Scientific name,,,,,2022-03-04,,,,,,,,,,,,\n" +
              "123,Other name,,,,,2022-03-05,,,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.DuplicateValue,
              isError = true,
              position = 3,
              field = "Accession Number",
              message = messages.accessionCsvNumberDuplicate(2),
              value = "123",
          ),
      )
    }

    @Test
    fun `rejects empty file`() {
      testValidation(
          byteArrayOf(),
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 1,
              message = messages.csvBadHeader(),
          ),
      )
    }

    @Test
    fun `rejects header row with wrong number of columns`() {
      testValidation(
          "a,b,c,d\n".toByteArray(),
          UploadStatus.Invalid,
          UploadProblemsRow(
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 1,
              field = null,
              message = messages.csvBadHeader(),
          ),
      )
    }

    private fun testValidation(
        body: String,
        status: UploadStatus,
        vararg problems: UploadProblemsRow,
    ) {
      testValidation(body, status, Locale.ENGLISH, *problems)
    }

    private fun testValidation(
        body: String,
        status: UploadStatus,
        locale: Locale = Locale.ENGLISH,
        vararg problems: UploadProblemsRow,
    ) {
      val uploadId = insertAccessionUpload(body, UploadStatus.AwaitingValidation, locale)
      importer.validateCsv(uploadId)
      assertValidationResult(status, *problems)
    }

    private fun testValidation(
        body: ByteArray,
        status: UploadStatus,
        vararg problems: UploadProblemsRow,
    ) {
      val uploadId = insertAccessionUpload(body, UploadStatus.AwaitingValidation)
      importer.validateCsv(uploadId)
      assertValidationResult(status, *problems)
    }

    private fun assertValidationResult(status: UploadStatus, vararg problems: UploadProblemsRow) {
      assertEquals(
          problems.toList().map { it.copy(uploadId = inserted.uploadId) },
          uploadProblemsDao
              .findAll()
              .sortedBy { it.id }
              .map { it.copy(id = null, uploadId = inserted.uploadId) },
          "Upload problems",
      )
      assertStatus(status)
    }
  }

  @Nested
  inner class CancelProcessing {
    @Test
    fun `throws exception if upload is not awaiting user action`() {
      val uploadId = insertAccessionUpload(status = UploadStatus.Processing)

      assertThrows<UploadNotAwaitingActionException> { importer.cancelProcessing(uploadId) }
    }

    @Test
    fun `deletes upload if it is awaiting user action`() {
      val uploadId = insertAccessionUpload(status = UploadStatus.AwaitingUserAction)
      every { uploadService.delete(uploadId) } just Runs

      importer.cancelProcessing(uploadId)

      verify { uploadService.delete(uploadId) }
    }
  }

  @Nested
  inner class ResolveWarnings {
    @Test
    fun `throws exception if upload is not awaiting user action`() {
      val uploadId = insertAccessionUpload(status = UploadStatus.Processing)

      assertThrows<UploadNotAwaitingActionException> { importer.resolveWarnings(uploadId, true) }
    }

    @Test
    fun `schedules import job`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      val uploadId = insertAccessionUpload(status = UploadStatus.AwaitingUserAction)

      importer.resolveWarnings(uploadId, true)

      verify { scheduler.enqueue<AccessionImporter>(any()) }
    }
  }

  @Nested
  inner class ImportCsv {
    @Test
    fun `uses Seeds as default quantity units if not specified`() {
      val uploadId =
          insertAccessionUpload(
              ",Species name,,10,,,2022-03-04,,,,,,,,,,,,\n",
              UploadStatus.AwaitingProcessing,
          )

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll()
      assertEquals(1, accessions.size, "Should have inserted 1 accession")
      assertEquals(BigDecimal.TEN, accessions[0].remainingQuantity, "Quantity")
      assertEquals(SeedQuantityUnits.Seeds, accessions[0].remainingUnitsId, "Units")
    }

    @Test
    fun `overwrites existing accession data if requested`() {
      val uploadId =
          insertAccessionUpload(
              "123,Species name,New common name,10,Seeds,Processing,2022-03-04,New Site," +
                  "New Landowner,New City,New State,GB,New Notes,New Collector,Other,2,New ID,5,4\n",
              UploadStatus.AwaitingProcessing,
          )

      val speciesId = insertSpecies(scientificName = "Species name", commonName = "Old common name")
      val accessionId =
          insertAccession(
              AccessionsRow(
                  collectedDate = LocalDate.EPOCH,
                  collectionSiteCity = "Old City",
                  collectionSiteCountryCode = "US",
                  collectionSiteCountrySubdivision = "Old State",
                  collectionSiteLandowner = "Old Landowner",
                  collectionSiteName = "Old Site",
                  collectionSiteNotes = "Old Notes",
                  collectionSourceId = CollectionSource.Wild,
                  facilityId = facilityId,
                  founderId = "Old ID",
                  number = "123",
                  remainingGrams = BigDecimal.ONE,
                  remainingQuantity = BigDecimal.ONE,
                  remainingUnitsId = SeedQuantityUnits.Grams,
                  speciesId = speciesId,
                  stateId = AccessionState.InStorage,
                  treesCollectedFrom = 1,
              )
          )
      accessionCollectorsDao.insert(AccessionCollectorsRow(accessionId, 0, "Old Collector"))
      geolocationsDao.insert(
          GeolocationsRow(
              accessionId = accessionId,
              createdTime = Instant.EPOCH,
              latitude = BigDecimal(50),
              longitude = BigDecimal(51),
          )
      )

      val existingSpecies = speciesDao.findAll()

      importer.importCsv(uploadId, true)

      assertEquals(
          listOf(
              AccessionsRow(
                  collectedDate = LocalDate.of(2022, 3, 4),
                  collectionSiteCity = "New City",
                  collectionSiteCountryCode = "GB",
                  collectionSiteCountrySubdivision = "New State",
                  collectionSiteLandowner = "New Landowner",
                  collectionSiteName = "New Site",
                  collectionSiteNotes = "New Notes",
                  collectionSourceId = CollectionSource.Other,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  dataSourceId = DataSource.Web,
                  estSeedCount = 10,
                  facilityId = facilityId,
                  founderId = "New ID",
                  id = accessionId,
                  latestObservedQuantity = BigDecimal.TEN,
                  latestObservedTime = Instant.EPOCH,
                  latestObservedUnitsId = SeedQuantityUnits.Seeds,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  number = "123",
                  remainingQuantity = BigDecimal.TEN,
                  remainingUnitsId = SeedQuantityUnits.Seeds,
                  speciesId = speciesId,
                  stateId = AccessionState.Processing,
                  treesCollectedFrom = 2,
              )
          ),
          accessionsDao.findAll(),
          "Accessions",
      )

      assertEquals(
          listOf(AccessionCollectorsRow(accessionId, 0, "New Collector")),
          accessionCollectorsDao.findAll(),
          "Collectors",
      )

      assertEquals(
          listOf(
              GeolocationsRow(
                  accessionId = accessionId,
                  createdTime = Instant.EPOCH,
                  latitude = BigDecimal(5),
                  longitude = BigDecimal(4),
              )
          ),
          geolocationsDao.findAll().map {
            it.copy(
                id = null,
                latitude = it.latitude?.stripTrailingZeros(),
                longitude = it.longitude?.stripTrailingZeros(),
            )
          },
          "Geolocations",
      )

      assertEquals(existingSpecies, speciesDao.findAll(), "Species should not have been updated")

      assertEquals(
          listOf(
              AccessionStateHistoryRow(
                  accessionId = accessionId,
                  updatedTime = Instant.EPOCH,
                  oldStateId = AccessionState.InStorage,
                  newStateId = AccessionState.Processing,
                  reason = "Accession has been edited",
                  updatedBy = user.userId,
              )
          ),
          dslContext
              .selectFrom(ACCESSION_STATE_HISTORY)
              .fetchInto(AccessionStateHistoryRow::class.java),
          "State change should have created history entry",
      )

      assertEquals(
          listOf(
              AccessionQuantityHistoryRow(
                  accessionId = accessionId,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  historyTypeId = AccessionQuantityHistoryType.Observed,
                  remainingQuantity = BigDecimal.TEN,
                  remainingUnitsId = SeedQuantityUnits.Seeds,
              )
          ),
          accessionQuantityHistoryDao.findAll().map { it.copy(id = null) },
          "Remaining quantity change should have created history entry",
      )
    }

    @Test
    fun `uses new name if a species has been renamed`() {
      val speciesId = insertSpecies(scientificName = "New name", initialScientificName = "Old name")
      val uploadId =
          insertAccessionUpload(
              ",Old name,,,,,2022-03-04,,,,,,,,,,,,\n",
              UploadStatus.AwaitingProcessing,
          )

      importer.importCsv(uploadId, false)

      assertEquals(
          speciesId,
          accessionsDao.findAll().firstOrNull()?.speciesId,
          "Should have used existing species",
      )
    }

    @Test
    fun `ignores template example rows`() {
      val uploadId =
          insertAccessionUpload(importer.getCsvTemplate(), UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, true)

      assertTableEmpty(ACCESSIONS)
    }

    @Test
    fun `ignores empty rows`() {
      val uploadId =
          insertAccessionUpload(
              ",\" \",, ,,, ,,,,,,,,,,,,\n" +
                  ",Species name,,10,,,2022-03-04,,,,,,,,,,,,\n" +
                  ",,,,,,,,,,,,,,,,,,\n",
              UploadStatus.AwaitingProcessing,
          )

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll()
      assertEquals(1, accessions.size, "Should have inserted 1 accession")
      assertEquals(BigDecimal.TEN, accessions[0].remainingQuantity, "Quantity")
    }

    @Test
    fun `accepts country codes and names`() {
      val uploadId =
          insertAccessionUpload(
              ",Species name,,,,,2022-03-04,,,,,uganda,,,,,,,\n" +
                  ",Species name,,,,,2022-03-04,,,,,gB,,,,,,,\n",
              UploadStatus.AwaitingProcessing,
          )

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll().sortedBy { it.id }
      assertEquals(2, accessions.size, "Should have inserted 2 accessions")
      assertEquals(
          "UG",
          accessions[0].collectionSiteCountryCode,
          "Country code looked up from name",
      )
      assertEquals("GB", accessions[1].collectionSiteCountryCode, "Country code specified in file")
    }

    @Test
    fun `accepts localized values`() {
      val grams = "Grams".toGibberish()
      val drying = "Drying".toGibberish()
      val california = "California".toGibberish()
      val us = "United States".toGibberish()
      val wild = "Wild".toGibberish()

      val uploadId =
          insertAccessionUpload(
              ",Species name,Common name,1 234,$grams,$drying,2023-01-01,Site,Landowner,City," +
                  "$california,$us,Description,Collector,$wild,1,ID,\"-13,45\",\"18,5578\"",
              UploadStatus.AwaitingProcessing,
              Locales.GIBBERISH,
          )

      importer.importCsv(uploadId, false)

      val accession = accessionsDao.findAll().single()
      assertEquals(BigDecimal(1234), accession.remainingQuantity, "Quantity")
      assertEquals(SeedQuantityUnits.Grams, accession.remainingUnitsId, "Quantity units")
      assertEquals(AccessionState.Drying, accession.stateId, "State")
      assertEquals("US", accession.collectionSiteCountryCode, "Country code")
      assertEquals(california, accession.collectionSiteCountrySubdivision, "Country subdivision")
      assertEquals(CollectionSource.Wild, accession.collectionSourceId, "Collection source")

      val geolocation = geolocationsDao.findAll().single()
      assertEquals(BigDecimal("-13.45"), geolocation.latitude?.stripTrailingZeros(), "Latitude")
      assertEquals(BigDecimal("18.5578"), geolocation.longitude?.stripTrailingZeros(), "Longitude")
    }
  }

  @Nested
  inner class GetTemplate {
    @Test
    fun `returns template with most specific matching locale`() {
      assertTemplateContains(
          Locale.forLanguageTag("gx-US-x-lvariant-test"),
          "Template,with,full,locale,including,variant",
      )
      assertTemplateContains(Locale.forLanguageTag("gx-US"), "Template,with,language,and,country")
      assertTemplateContains(Locale.forLanguageTag("gx"), "gibberish for")
    }

    private fun assertTemplateContains(locale: Locale, searchString: String) {
      val template = locale.use { importer.getCsvTemplate() }
      val templateString = template.decodeToString()

      if (searchString !in templateString) {
        // assertEquals is just to get a failure message that IntelliJ can interpret
        assertEquals(
            searchString,
            templateString,
            "Didn't find expected string in template for $locale",
        )
      }
    }
  }

  private fun insertAccessionUpload(
      body: String = "",
      status: UploadStatus = UploadStatus.AwaitingUserAction,
      locale: Locale = Locale.ENGLISH,
  ): UploadId {
    val header =
        javaClass.getResourceAsStream("/seedbank/accession/HeaderRows.csv")!!.use { inputStream ->
          inputStream.readAllBytes().decodeToString().trim()
        }

    return insertAccessionUpload("$header\n$body".toByteArray(), status, locale)
  }

  private fun insertAccessionUpload(
      body: ByteArray,
      status: UploadStatus = UploadStatus.AwaitingUserAction,
      locale: Locale = Locale.ENGLISH,
  ): UploadId {
    every { fileStore.read(any()) } answers
        {
          SizedInputStream(body.inputStream(), body.size.toLong())
        }

    return insertUpload(
        facilityId = facilityId,
        locale = locale,
        organizationId = organizationId,
        status = status,
        type = UploadType.AccessionCSV,
    )
  }

  private fun assertStatus(expected: UploadStatus) {
    assertEquals(expected, uploadsDao.fetchOneById(inserted.uploadId)?.statusId, "Upload status")
  }
}
