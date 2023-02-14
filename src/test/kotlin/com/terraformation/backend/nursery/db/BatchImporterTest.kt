package com.terraformation.backend.nursery.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.UPLOAD_PROBLEMS
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.use
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
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BatchImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(BATCHES, SPECIES, UPLOAD_PROBLEMS)

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val scheduler: JobScheduler = mockk()
  private val uploadService: UploadService = mockk()
  private val userStore: UserStore = mockk()

  private val batchStore: BatchStore by lazy {
    BatchStore(
        batchesDao,
        batchQuantityHistoryDao,
        batchWithdrawalsDao,
        clock,
        dslContext,
        TestEventPublisher(),
        IdentifierGenerator(clock, dslContext),
        parentStore,
        nurseryWithdrawalsDao)
  }
  private val messages = Messages()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(clock, dslContext, speciesDao, speciesEcosystemTypesDao, speciesProblemsDao)
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
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore)
  }

  private val uploadId = UploadId(1)

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

    insertUser()
    insertOrganization()
    insertFacility(type = FacilityType.Nursery)
  }

  @Test
  fun `happy path with valid file causes batches and species to be created`() {
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
                batchNumber = "70-2-001",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 1,
                id = BatchId(1),
                latestObservedGerminatingQuantity = 1,
                latestObservedNotReadyQuantity = 2,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
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
                batchNumber = "70-2-002",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 0,
                id = BatchId(2),
                latestObservedGerminatingQuantity = 0,
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
                batchNumber = "70-2-003",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 0,
                id = BatchId(3),
                latestObservedGerminatingQuantity = 0,
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
                batchNumber = "70-2-001",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                facilityId = facilityId,
                germinatingQuantity = 123456,
                id = BatchId(1),
                latestObservedGerminatingQuantity = 123456,
                latestObservedNotReadyQuantity = 2,
                latestObservedReadyQuantity = 0,
                latestObservedTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                notReadyQuantity = 2,
                organizationId = organizationId,
                readyQuantity = 0,
                speciesId = SpeciesId(1),
                version = 1,
            ),
        ),
    )
  }

  @Test
  fun `rejects files with validation errors`() {
    insertBatchUpload(headerAnd("ShortName,,1,1,2022-01-01\n"), UploadStatus.AwaitingValidation)

    importer.validateCsv(uploadId)

    assertEquals(
        listOf(
            UploadProblemsRow(
                id = UploadProblemId(1),
                isError = true,
                field = "Species (Scientific Name)",
                message = messages.csvScientificNameTooShort(),
                position = 2,
                typeId = UploadProblemType.MalformedValue,
                uploadId = uploadId,
                value = "ShortName",
            )),
        uploadProblemsDao.findAll(),
        "Upload problems")

    assertStatus(UploadStatus.Invalid)
  }

  @Test
  fun `uses new name if a species has been renamed`() {
    val speciesId = insertSpecies(scientificName = "New name", initialScientificName = "Old name")
    insertBatchUpload(headerAnd("Old name,,,,2022-01-01"))

    importer.importCsv(uploadId, false)

    assertEquals(speciesId, batchesDao.fetchOneById(BatchId(1))?.speciesId)
  }

  private fun runHappyPath(
      filename: String,
      locale: Locale,
      expectedSpecies: List<SpeciesRow>,
      expectedBatches: List<BatchesRow>
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
          uploadId
        }

    locale.use { importer.receiveCsv(csvContent.inputStream(), filename, facilityId) }

    // Validate (no problems found since this is the happy path) -- this will cause slot.captured
    // to be updated to point to importCsv()
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.AwaitingProcessing,
        uploadsDao.fetchOneById(uploadId)?.statusId,
        "Status after validation",
    )

    // Import
    slot.captured.accept(importer)

    assertEquals(
        UploadStatus.Completed,
        uploadsDao.fetchOneById(uploadId)?.statusId,
        "Status after import",
    )

    assertEquals(
        expectedSpecies,
        speciesDao.findAll().sortedBy { it.id!!.value },
        "Imported species",
    )

    assertJsonEquals(
        expectedBatches,
        batchesDao.findAll().sortedBy { it.id!!.value },
        "Imported batches",
    )

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
  ) {
    every { fileStore.read(any()) } answers
        {
          SizedInputStream(body.inputStream(), body.size.toLong())
        }

    insertUpload(
        id = uploadId,
        facilityId = facilityId,
        locale = locale,
        organizationId = organizationId,
        status = status,
        type = UploadType.SeedlingBatchCSV,
    )
  }

  private fun assertStatus(expected: UploadStatus) {
    assertEquals(expected, uploadsDao.fetchOneById(uploadId)?.statusId, "Upload status")
  }
}
