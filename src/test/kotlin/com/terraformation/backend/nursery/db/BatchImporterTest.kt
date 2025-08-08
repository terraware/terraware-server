package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.records.UploadProblemsRecord
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchSubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mapTo1IndexedIds
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.jobs.lambdas.IocJobLambda
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BatchImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val scheduler: JobScheduler = mockk()
  private val uploadService: UploadService = mockk()
  private val userStore: UserStore = mockk()

  private val batchStore: BatchStore by lazy {
    BatchStore(
        batchDetailsHistoryDao,
        batchDetailsHistorySubLocationsDao,
        batchesDao,
        batchQuantityHistoryDao,
        batchWithdrawalsDao,
        clock,
        dslContext,
        TestEventPublisher(),
        facilitiesDao,
        IdentifierGenerator(clock, dslContext),
        parentStore,
        projectsDao,
        subLocationsDao,
        nurseryWithdrawalsDao)
  }
  private val messages = Messages()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao)
  }
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }

  private val importer: BatchImporter by lazy {
    BatchImporter(
        batchStore,
        dslContext,
        fileStore,
        messages,
        parentStore,
        scheduler,
        speciesStore,
        subLocationsDao,
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore)
  }

  private lateinit var facilityId: FacilityId
  private lateinit var organizationId: OrganizationId
  private lateinit var subLocationId: SubLocationId

  @BeforeEach
  fun setUp() {
    val userId = user.userId

    every { user.canCreateBatch(any()) } returns true
    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadFacility(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canReadUpload(any()) } returns true
    every { user.canUpdateUpload(any()) } returns true
    every { userStore.fetchOneById(userId) } returns user

    organizationId = insertOrganization()
    facilityId = insertFacility(type = FacilityType.Nursery)
    subLocationId = insertSubLocation(name = "Location 1")
  }

  @Test
  fun `happy path with valid file causes batches and species to be created`() {
    val subLocationId2 = insertSubLocation(name = "Location 2")

    runHappyPath(
        "HappyPath.csv",
        Locale.ENGLISH,
        listOf(
            SpeciesRow(
                commonName = "Common name",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                id = SpeciesId(1),
                initialScientificName = "Scientific name",
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Scientific name",
            ),
            SpeciesRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                id = SpeciesId(2),
                initialScientificName = "Second name",
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Second name",
            ),
        ),
        listOf(
            BatchesRow(
                addedDate = LocalDate.of(2022, 1, 1),
                batchNumber = "70-2-1-001",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 1,
                hardeningOffQuantity = 0,
                id = BatchId(1),
                latestObservedGerminatingQuantity = 1,
                latestObservedHardeningOffQuantity = 0,
                latestObservedNotReadyQuantity = 2,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
                lossRate = 0,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                notReadyQuantity = 2,
                organizationId = organizationId,
                readyQuantity = 0,
                speciesId = SpeciesId(1),
                version = 1,
            ),
            BatchesRow(
                addedDate = LocalDate.of(2022, 1, 2),
                batchNumber = "70-2-1-002",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 0,
                hardeningOffQuantity = 0,
                id = BatchId(2),
                latestObservedGerminatingQuantity = 0,
                latestObservedHardeningOffQuantity = 0,
                latestObservedNotReadyQuantity = 0,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                notReadyQuantity = 0,
                organizationId = organizationId,
                readyQuantity = 0,
                speciesId = SpeciesId(2),
                version = 1,
            ),
            BatchesRow(
                addedDate = LocalDate.of(2022, 1, 3),
                batchNumber = "70-2-1-003",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 0,
                hardeningOffQuantity = 0,
                id = BatchId(3),
                latestObservedGerminatingQuantity = 0,
                latestObservedHardeningOffQuantity = 0,
                latestObservedNotReadyQuantity = 0,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                notReadyQuantity = 0,
                organizationId = organizationId,
                readyQuantity = 0,
                speciesId = SpeciesId(1),
                version = 1,
            ),
        ),
        listOf(
            BatchSubLocationsRow(
                batchId = BatchId(2),
                facilityId = facilityId,
                subLocationId = subLocationId,
            ),
            BatchSubLocationsRow(
                batchId = BatchId(2),
                facilityId = facilityId,
                subLocationId = subLocationId2,
            ),
        ),
    )
  }

  @Test
  fun `accepts file with valid localized values`() {
    runHappyPath(
        "Gibberish.csv",
        Locales.GIBBERISH,
        listOf(
            SpeciesRow(
                commonName = "Common name",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                id = SpeciesId(1),
                initialScientificName = "Scientific name",
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Scientific name",
            ),
        ),
        listOf(
            BatchesRow(
                addedDate = LocalDate.of(2022, 1, 1),
                batchNumber = "70-2-1-001",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 123456,
                hardeningOffQuantity = 0,
                id = BatchId(1),
                latestObservedGerminatingQuantity = 123456,
                latestObservedHardeningOffQuantity = 0,
                latestObservedNotReadyQuantity = 2,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
                lossRate = 0,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                notReadyQuantity = 2,
                organizationId = organizationId,
                readyQuantity = 0,
                speciesId = SpeciesId(1),
                version = 1,
            ),
        ),
        listOf(
            BatchSubLocationsRow(
                batchId = BatchId(1),
                facilityId = facilityId,
                subLocationId = subLocationId,
            ),
        ))
  }

  @Test
  fun `rejects files with validation errors`() {
    val uploadId =
        insertBatchUpload(
            headerAnd("ShortName,,1,1,2022-01-01,\n"), UploadStatus.AwaitingValidation)

    importer.validateCsv(uploadId)

    assertTableEquals(
        UploadProblemsRecord(
            isError = true,
            field = "Species (Scientific Name)",
            message = messages.csvScientificNameTooShort(),
            position = 2,
            typeId = UploadProblemType.MalformedValue,
            uploadId = uploadId,
            value = "ShortName"))

    assertStatus(UploadStatus.Invalid)
  }

  @Test
  fun `uses new name if a species has been renamed`() {
    val speciesId = insertSpecies(scientificName = "New name", initialScientificName = "Old name")
    val uploadId = insertBatchUpload(headerAnd("Old name,,,,2022-01-01,"))

    importer.importCsv(uploadId, false)

    assertEquals(speciesId, batchesDao.findAll().first().speciesId)
  }

  /**
   * Runs a scenario with a successful import and verifies that the expected rows have been inserted
   * into the database.
   *
   * The IDs in the expected entity lists are assumed to start with 1.
   */
  private fun runHappyPath(
      filename: String,
      locale: Locale,
      expectedSpecies: List<SpeciesRow>,
      expectedBatches: List<BatchesRow>,
      expectedSubLocations: List<BatchSubLocationsRow> = emptyList(),
  ) {
    val csvContent =
        javaClass.getResourceAsStream("/nursery/$filename")!!.use { inputStream ->
          inputStream.readAllBytes()
        }
    val slot: CapturingSlot<IocJobLambda<BatchImporter>> = slot()
    every { scheduler.enqueue(capture(slot)) } returns JobId(UUID.randomUUID())
    every { uploadService.receive(any(), any(), any(), any(), any(), any(), any()) } answers
        {
          insertBatchUpload(csvContent, UploadStatus.AwaitingValidation, locale)
        }

    locale.use { importer.receiveCsv(csvContent.inputStream(), filename, facilityId) }

    // Validate (no problems found since this is the happy path) -- this will cause slot.captured
    // to be updated to point to importCsv()
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.AwaitingProcessing,
        uploadsDao.fetchOneById(inserted.uploadId)?.statusId,
        "Status after validation")

    // Import
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.Completed,
        uploadsDao.fetchOneById(inserted.uploadId)?.statusId,
        "Status after import")

    val actualSpecies = speciesDao.findAll().sortedBy { it.id }
    val actualBatches = batchesDao.findAll().sortedBy { it.id }
    val mappedSpeciesIds = mapTo1IndexedIds(actualSpecies, ::SpeciesId, SpeciesRow::id)
    val mappedBatchIds = mapTo1IndexedIds(actualBatches, ::BatchId, BatchesRow::id)

    assertEquals(
        expectedSpecies.map { it.copy(id = null) },
        actualSpecies.map { it.copy(id = null) },
        "Imported species")

    assertJsonEquals(
        expectedBatches.map { it.copy(id = null, speciesId = mappedSpeciesIds[it.speciesId]) },
        actualBatches.map { it.copy(id = null) },
        "Imported batches")

    assertJsonEquals(
        expectedSubLocations.map { it.copy(batchId = mappedBatchIds[it.batchId]) },
        batchSubLocationsDao
            .findAll()
            .sortedWith(
                compareBy<BatchSubLocationsRow> { it.batchId!!.value }
                    .thenBy { it.subLocationId!!.value }),
        "Imported batch sub-locations")

    assertStatus(UploadStatus.Completed)
  }

  private fun headerAnd(vararg rows: String): ByteArray {
    val header =
        javaClass.getResourceAsStream("/csv/batches-template.csv")!!.use { inputStream ->
          inputStream.readAllBytes().decodeToString().trim()
        }
    val joinedRows = rows.joinToString("\r\n").trim()

    return "$header\r\n$joinedRows\r\n".toByteArray()
  }

  private fun insertBatchUpload(
      body: ByteArray,
      status: UploadStatus = UploadStatus.AwaitingProcessing,
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
        type = UploadType.SeedlingBatchCSV,
    )
  }

  private fun assertStatus(expected: UploadStatus) {
    assertEquals(expected, uploadsDao.fetchOneById(inserted.uploadId)?.statusId, "Upload status")
  }
}
