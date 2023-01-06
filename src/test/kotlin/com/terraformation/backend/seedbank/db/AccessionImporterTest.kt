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
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.UPLOAD_PROBLEMS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionQuantityHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_QUANTITY_HISTORY
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Messages
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
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.jobrunr.scheduling.JobScheduler
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences: List<Table<out Record>> =
      listOf(ACCESSION_QUANTITY_HISTORY, ACCESSIONS, SPECIES, UPLOADS, UPLOAD_PROBLEMS)

  private val accessionStore: AccessionStore by lazy {
    AccessionStore(
        dslContext,
        BagStore(dslContext),
        GeolocationStore(dslContext, clock),
        ViabilityTestStore(dslContext),
        parentStore,
        WithdrawalStore(dslContext, clock, messages, parentStore),
        clock,
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
        TestEventPublisher(),
        facilitiesDao,
        organizationsDao,
        storageLocationsDao)
  }
  private val fileStore: FileStore = mockk()
  private val importer: AccessionImporter by lazy {
    AccessionImporter(
        accessionStore,
        countriesDao,
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
    SpeciesStore(clock, dslContext, speciesDao, speciesProblemsDao)
  }
  private val uploadService: UploadService = mockk()
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }
  private val userStore: UserStore = mockk()

  private val uploadId = UploadId(1)

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

    insertSiteData()
  }

  @Test
  fun `end-to-end happy path causes accessions and species to be created`() {
    val csvContent =
        javaClass.getResourceAsStream("/seedbank/accession/HappyPath.csv")!!.use { inputStream ->
          inputStream.readAllBytes()
        }

    val slot: CapturingSlot<IocJobLambda<AccessionImporter>> = slot()
    every { scheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())
    every { uploadService.receive(any(), any(), any(), any(), any(), any(), any()) } answers
        {
          insertAccessionUpload(csvContent, UploadStatus.AwaitingValidation)
          uploadId
        }

    importer.receiveCsv(csvContent.inputStream(), "HappyPath.csv", facilityId)

    // Validate (no problems found since this is the happy path) -- this will cause slot.captured
    // to be updated to point to importCsv()
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.AwaitingProcessing,
        uploadsDao.fetchOneById(uploadId)?.statusId,
        "Status after validation")

    // Import
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.Completed, uploadsDao.fetchOneById(uploadId)?.statusId, "Status after import")

    assertEquals(
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
                initialScientificName = "New species var. new")),
        speciesDao.findAll(),
        "Imported species")

    assertJsonEquals(
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
                number = "70-1-001",
                remainingGrams = BigDecimal(101000),
                remainingQuantity = BigDecimal(101),
                remainingUnitsId = SeedQuantityUnits.Kilograms,
                speciesId = SpeciesId(1),
                stateId = AccessionState.InStorage,
            ),
        ),
        accessionsDao.findAll().sortedBy { it.id!!.value },
        "Imported accessions")

    assertEquals(
        listOf(AccessionCollectorsRow(AccessionId(1), 0, "Collector,Name")),
        accessionCollectorsDao.findAll(),
        "Imported collectors")
  }

  @Nested
  inner class ReceiveCsv {
    @Test
    fun `schedules validate job`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      every { uploadService.receive(any(), any(), any(), any(), any(), any()) } returns uploadId

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
    fun `accepts rows where all columns are blank`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(",,,,  ,,,,,    ,,,\"  \",,,,\n", UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `accepts rows with country codes`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,uS,,,,,\n", UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `accepts rows with country names`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,canada,,,,,\n", UploadStatus.AwaitingProcessing)
    }

    @Test
    fun `rejects rows with wrong number of columns`() {
      testValidation(
          ",Species name,,,,",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 2,
              message = messages.csvWrongFieldCount(17, 6)))
    }

    @Test
    fun `rejects rows with malformed scientific names`() {
      testValidation(
          ",,,,,,2022-03-04,,,,,,,,,,\n" +
              ",Name,,,,,2022-03-04,,,,,,,,,,\n" +
              ",A very long name with too many words,,,,,2022-03-04,,,,,,,,,,\n" +
              ",Bad name?,,,,,2022-03-04,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 2,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameMissing()),
          UploadProblemsRow(
              id = UploadProblemId(2),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameTooShort(),
              value = "Name"),
          UploadProblemsRow(
              id = UploadProblemId(3),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameTooLong(),
              value = "A very long name with too many words"),
          UploadProblemsRow(
              id = UploadProblemId(4),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 5,
              field = "Species (Scientific Name)",
              message = messages.csvScientificNameInvalidChar("?"),
              value = "Bad name?"),
      )
    }

    @Test
    fun `rejects rows with malformed collection dates`() {
      testValidation(
          ",Scientific name,,,,,,,,,,,,,,,\n" +
              ",Scientific name,,,,,January 6,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022-99-99,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022/03/04,,,,,,,,,,\n" +
              ",Scientific name,,,,,2022-3-4,,,,,,,,,,\n" +
              ",Scientific name,,,,,20220304,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 2,
              field = "Collection Date",
              message = messages.csvRequiredFieldMissing()),
          UploadProblemsRow(
              id = UploadProblemId(2),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 3,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "January 6"),
          UploadProblemsRow(
              id = UploadProblemId(3),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 4,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022-99-99"),
          UploadProblemsRow(
              id = UploadProblemId(4),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 5,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022/03/04"),
          UploadProblemsRow(
              id = UploadProblemId(5),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 6,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "2022-3-4"),
          UploadProblemsRow(
              id = UploadProblemId(6),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 7,
              field = "Collection Date",
              message = messages.csvDateMalformed(),
              value = "20220304"),
      )
    }

    @Test
    fun `rejects rows with malformed statuses`() {
      testValidation(
          ",Scientific name,,,,Bogus,2022-03-04,,,,,,,,,,\n" +
              // Withdrawn is a v1-only state
              ",Scientific name,,,,Withdrawn,2022-03-04,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Status",
              message = messages.accessionCsvStatusInvalid(),
              value = "Bogus"),
          UploadProblemsRow(
              id = UploadProblemId(2),
              uploadId = uploadId,
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 3,
              field = "Status",
              message = messages.accessionCsvStatusInvalid(),
              value = "Withdrawn"),
      )
    }

    @Test
    fun `rejects rows with malformed collection sources`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,,,,Unknown,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Collection Source",
              message = messages.accessionCsvCollectionSourceInvalid(),
              value = "Unknown"))
    }

    @Test
    fun `rejects rows with bogus countries`() {
      testValidation(
          ",Scientific name,,,,,2022-03-04,,,,,Unknown,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.UnrecognizedValue,
              isError = true,
              position = 2,
              field = "Country",
              message = messages.accessionCsvCountryInvalid(),
              value = "Unknown"))
    }

    @Test
    fun `detects existing accession numbers`() {
      insertAccession(number = "123")

      testValidation(
          "123,Scientific name,,,,,2022-03-04,,,,,,,,,,\n",
          UploadStatus.AwaitingUserAction,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.DuplicateValue,
              isError = false,
              position = 2,
              field = "Accession Number",
              message = messages.accessionCsvNumberExists(),
              value = "123"))
    }

    @Test
    fun `rejects duplicate accession numbers in CSV file`() {
      testValidation(
          "123,Scientific name,,,,,2022-03-04,,,,,,,,,,\n" +
              "123,Other name,,,,,2022-03-05,,,,,,,,,,\n",
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.DuplicateValue,
              isError = true,
              position = 3,
              field = "Accession Number",
              message = messages.accessionCsvNumberDuplicate(2),
              value = "123"))
    }

    @Test
    fun `rejects empty file`() {
      testValidation(
          byteArrayOf(),
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.MissingRequiredValue,
              isError = true,
              position = 1,
              message = messages.csvBadHeader()))
    }

    @Test
    fun `rejects header row with wrong number of columns`() {
      testValidation(
          "a,b,c,d\n".toByteArray(),
          UploadStatus.Invalid,
          UploadProblemsRow(
              id = UploadProblemId(1),
              uploadId = uploadId,
              typeId = UploadProblemType.MalformedValue,
              isError = true,
              position = 1,
              field = null,
              message = messages.csvBadHeader()))
    }

    private fun testValidation(
        body: String,
        status: UploadStatus,
        vararg problems: UploadProblemsRow
    ) {
      insertAccessionUpload(body, UploadStatus.AwaitingValidation)
      importer.validateCsv(uploadId)
      assertValidationResult(status, *problems)
    }

    private fun testValidation(
        body: ByteArray,
        status: UploadStatus,
        vararg problems: UploadProblemsRow
    ) {
      insertAccessionUpload(body, UploadStatus.AwaitingValidation)
      importer.validateCsv(uploadId)
      assertValidationResult(status, *problems)
    }

    private fun assertValidationResult(status: UploadStatus, vararg problems: UploadProblemsRow) {
      assertEquals(
          problems.toList(),
          uploadProblemsDao.findAll().sortedBy { it.id?.value },
          "Upload problems")
      assertStatus(status)
    }
  }

  @Nested
  inner class CancelProcessing {
    @Test
    fun `throws exception if upload is not awaiting user action`() {
      insertAccessionUpload(status = UploadStatus.Processing)

      assertThrows<UploadNotAwaitingActionException> { importer.cancelProcessing(uploadId) }
    }

    @Test
    fun `deletes upload if it is awaiting user action`() {
      every { uploadService.delete(uploadId) } just Runs
      insertAccessionUpload(status = UploadStatus.AwaitingUserAction)

      importer.cancelProcessing(uploadId)

      verify { uploadService.delete(uploadId) }
    }
  }

  @Nested
  inner class ResolveWarnings {
    @Test
    fun `throws exception if upload is not awaiting user action`() {
      insertAccessionUpload(status = UploadStatus.Processing)

      assertThrows<UploadNotAwaitingActionException> { importer.resolveWarnings(uploadId, true) }
    }

    @Test
    fun `schedules import job`() {
      every { scheduler.enqueue<AccessionImporter>(any()) } returns JobId(UUID.randomUUID())
      insertAccessionUpload(status = UploadStatus.AwaitingUserAction)

      importer.resolveWarnings(uploadId, true)

      verify { scheduler.enqueue<AccessionImporter>(any()) }
    }
  }

  @Nested
  inner class ImportCsv {
    @Test
    fun `uses Seeds as default quantity units if not specified`() {
      insertAccessionUpload(
          ",Species name,,10,,,2022-03-04,,,,,,,,,,\n", UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll()
      assertEquals(1, accessions.size, "Should have inserted 1 accession")
      assertEquals(BigDecimal.TEN, accessions[0].remainingQuantity, "Quantity")
      assertEquals(SeedQuantityUnits.Seeds, accessions[0].remainingUnitsId, "Units")
    }

    @Test
    fun `overwrites existing accession data if requested`() {
      insertAccessionUpload(
          "123,Species name,New common name,10,Seeds,Processing,2022-03-04,New Site," +
              "New Landowner,New City,New State,GB,New Notes,New Collector,Other,2,New ID\n",
          UploadStatus.AwaitingProcessing)

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
              ))
      accessionCollectorsDao.insert(AccessionCollectorsRow(accessionId, 0, "Old Collector"))

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
              )),
          accessionsDao.findAll(),
          "Accessions")

      assertEquals(
          listOf(AccessionCollectorsRow(accessionId, 0, "New Collector")),
          accessionCollectorsDao.findAll(),
          "Collectors")

      assertEquals(existingSpecies, speciesDao.findAll(), "Species should not have been updated")

      assertEquals(
          listOf(
              AccessionStateHistoryRow(
                  accessionId = accessionId,
                  updatedTime = Instant.EPOCH,
                  oldStateId = AccessionState.InStorage,
                  newStateId = AccessionState.Processing,
                  reason = "Accession has been edited",
                  updatedBy = user.userId)),
          dslContext
              .selectFrom(ACCESSION_STATE_HISTORY)
              .fetchInto(AccessionStateHistoryRow::class.java),
          "State change should have created history entry")

      assertEquals(
          listOf(
              AccessionQuantityHistoryRow(
                  accessionId = accessionId,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  historyTypeId = AccessionQuantityHistoryType.Observed,
                  id = AccessionQuantityHistoryId(1),
                  remainingQuantity = BigDecimal.TEN,
                  remainingUnitsId = SeedQuantityUnits.Seeds)),
          accessionQuantityHistoryDao.findAll(),
          "Remaining quantity change should have created history entry")
    }

    @Test
    fun `uses new name if a species has been renamed`() {
      val speciesId = insertSpecies(scientificName = "New name", initialScientificName = "Old name")
      insertAccessionUpload(",Old name,,,,,2022-03-04,,,,,,,,,,\n", UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, false)

      assertEquals(
          speciesId,
          accessionsDao.fetchOneById(AccessionId(1))?.speciesId,
          "Should have used existing species")
    }

    @Test
    fun `ignores template example rows`() {
      insertAccessionUpload(importer.getCsvTemplate(), UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, true)

      assertEquals(emptyList<AccessionsRow>(), accessionsDao.findAll())
    }

    @Test
    fun `ignores empty rows`() {
      insertAccessionUpload(
          ",\" \",, ,,, ,,,,,,,,,,\n" +
              ",Species name,,10,,,2022-03-04,,,,,,,,,,\n" +
              ",,,,,,,,,,,,,,,,\n",
          UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll()
      assertEquals(1, accessions.size, "Should have inserted 1 accession")
      assertEquals(BigDecimal.TEN, accessions[0].remainingQuantity, "Quantity")
    }

    @Test
    fun `accepts country codes and names`() {
      insertAccessionUpload(
          ",Species name,,,,,2022-03-04,,,,,uganda,,,,,\n" +
              ",Species name,,,,,2022-03-04,,,,,gB,,,,,\n",
          UploadStatus.AwaitingProcessing)

      importer.importCsv(uploadId, false)

      val accessions = accessionsDao.findAll()
      assertEquals(2, accessions.size, "Should have inserted 2 accessions")
      assertEquals(
          "UG", accessions[0].collectionSiteCountryCode, "Country code looked up from name")
      assertEquals("GB", accessions[1].collectionSiteCountryCode, "Country code specified in file")
    }
  }

  private fun insertAccessionUpload(
      body: String = "",
      status: UploadStatus = UploadStatus.AwaitingUserAction
  ) {
    val header =
        javaClass.getResourceAsStream("/seedbank/accession/HeaderRows.csv")!!.use { inputStream ->
          inputStream.readAllBytes().decodeToString().trim()
        }

    insertAccessionUpload("$header\n$body".toByteArray(), status)
  }

  private fun insertAccessionUpload(
      body: ByteArray,
      status: UploadStatus = UploadStatus.AwaitingUserAction
  ) {
    every { fileStore.read(any()) } answers
        {
          SizedInputStream(body.inputStream(), body.size.toLong())
        }

    insertUpload(
        id = uploadId,
        facilityId = facilityId,
        organizationId = organizationId,
        status = status,
        type = UploadType.AccessionCSV,
    )
  }

  private fun assertStatus(expected: UploadStatus) {
    assertEquals(expected, uploadsDao.fetchOneById(uploadId)?.statusId, "Upload status")
  }
}
