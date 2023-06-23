package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesEcosystemTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
import com.terraformation.backend.db.default_schema.tables.references.UPLOAD_PROBLEMS
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.UploadService
import com.terraformation.backend.file.UploadStore
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.jobrunr.jobs.JobId
import org.jobrunr.scheduling.JobScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class SpeciesImporterTest : DatabaseTest(), RunsAsUser {
  override val tablesToResetSequences = listOf(SPECIES, SPECIES_PROBLEMS, UPLOADS, UPLOAD_PROBLEMS)
  override val user = mockUser()

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val messages = Messages()
  private val scheduler: JobScheduler = mockk()
  private val speciesChecker: SpeciesChecker = mockk()
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(clock, dslContext, speciesDao, speciesEcosystemTypesDao, speciesProblemsDao)
  }
  private val uploadService: UploadService = mockk()
  private val uploadStore: UploadStore by lazy {
    UploadStore(dslContext, uploadProblemsDao, uploadsDao)
  }
  private val userStore: UserStore = mockk()
  private val importer: SpeciesImporter by lazy {
    SpeciesImporter(
        dslContext,
        fileStore,
        messages,
        scheduler,
        speciesChecker,
        speciesStore,
        uploadProblemsDao,
        uploadsDao,
        uploadService,
        uploadStore,
        userStore)
  }

  private val header =
      "Scientific Name,Common Name,Family,Endangered,Rare,Growth Form,Seed Storage Behavior,Ecosystem Types"

  private val storageUrl = URI.create("file:///test")
  private val uploadId = UploadId(10)
  private lateinit var userId: UserId

  @BeforeEach
  fun setUp() {
    userId = user.userId
    insertUser()
    insertOrganization()

    every { speciesChecker.checkAllUncheckedSpecies(organizationId) } just Runs
    every { user.canCreateSpecies(organizationId) } returns true
    every { user.canDeleteUpload(uploadId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canReadUpload(uploadId) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canUpdateUpload(uploadId) } returns true
    every { userStore.fetchOneById(userId) } returns user
  }

  @Test
  fun `receiveCsv schedules validate job`() {
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    every { uploadService.receive(any(), any(), any(), any(), any()) } returns uploadId

    importer.receiveCsv(ByteArrayInputStream(ByteArray(1)), "test", organizationId)

    verify { scheduler.enqueue<SpeciesImporter>(any()) }
  }

  @MethodSource("supportedLocales")
  @ParameterizedTest
  fun `getCsvTemplate returns a template that is accepted by validateCsv`(locale: Locale) {
    locale.use {
      val template = importer.getCsvTemplate()
      every { fileStore.read(storageUrl) } returns sizedInputStream(template)
      every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())

      insertUpload(
          uploadId,
          organizationId = organizationId,
          storageUrl = storageUrl,
          type = UploadType.SpeciesCSV)

      importer.validateCsv(uploadId)

      assertEquals(emptyList<UploadProblemsRow>(), uploadProblemsDao.findAll())
    }
  }

  @Test
  fun `getCsvTemplate returns localized templates`() {
    val templatesByLocale: Map<Locale, String> =
        supportedLocales().associateWith { locale ->
          locale.use { importer.getCsvTemplate().decodeToString() }
        }
    val localesByTemplate: Map<String, List<Locale>> =
        templatesByLocale.entries.groupBy({ (_, template) -> template }) { (locale, _) -> locale }
    val localesWithSameTemplates: List<List<Locale>> =
        localesByTemplate.values.filter { it.size > 1 }

    assertEquals(
        emptyList<List<Locale>>(), localesWithSameTemplates, "Locales with same CSV template")
  }

  @Test
  fun `cancelProcessing throws exception if upload is not awaiting user action`() {
    insertUpload(uploadId, status = UploadStatus.Processing)

    assertThrows<UploadNotAwaitingActionException> { importer.cancelProcessing(uploadId) }
  }

  @Test
  fun `cancelProcessing deletes upload if it is awaiting user action`() {
    every { uploadService.delete(uploadId) } just Runs
    insertUpload(uploadId, status = UploadStatus.AwaitingUserAction)

    importer.cancelProcessing(uploadId)

    verify { uploadService.delete(uploadId) }
  }

  @Test
  fun `resolveWarnings throws exception if upload is not awaiting user action`() {
    insertUpload(uploadId, status = UploadStatus.Processing)

    assertThrows<UploadNotAwaitingActionException> { importer.resolveWarnings(uploadId, true) }
  }

  @Test
  fun `resolveWarnings schedules import job`() {
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    insertUpload(uploadId, status = UploadStatus.AwaitingUserAction)

    importer.resolveWarnings(uploadId, true)

    verify { scheduler.enqueue<SpeciesImporter>(any()) }
  }

  @Test
  fun `validateCsv detects existing scientific names`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nExisting name,,,,,,,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingValidation,
        storageUrl = storageUrl)
    insertSpecies(1, "Existing name")

    importer.validateCsv(uploadId)

    val expectedProblems =
        listOf(
            UploadProblemsRow(
                UploadProblemId(1),
                uploadId,
                UploadProblemType.DuplicateValue,
                false,
                2,
                "Scientific Name",
                messages.speciesCsvScientificNameExists(),
                "Existing name"))

    val actualProblems = uploadProblemsDao.findAll()
    assertEquals(expectedProblems, actualProblems, "Upload problems")
    assertStatus(UploadStatus.AwaitingUserAction)
  }

  @Test
  fun `validateCsv detects duplicate species even if they were renamed`() {
    every { fileStore.read(storageUrl) } answers
        {
          sizedInputStream("$header\nInitial name,,,,,,,")
        }
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(2, "Corrected name", initialScientificName = "Initial name")

    importer.validateCsv(uploadId)

    val expectedProblems =
        listOf(
            UploadProblemsRow(
                UploadProblemId(1),
                uploadId,
                UploadProblemType.DuplicateValue,
                false,
                2,
                "Scientific Name",
                messages.speciesCsvScientificNameExists(),
                "Corrected name (Initial name)"))

    val actualProblems = uploadProblemsDao.findAll()
    assertEquals(expectedProblems, actualProblems)
  }

  @Test
  fun `validateCsv does not treat deleted species as name collisions`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nExisting name,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingValidation,
        storageUrl = storageUrl)
    insertSpecies(1, "Existing name", deletedTime = Instant.EPOCH)

    importer.validateCsv(uploadId)

    assertEquals(emptyList<UploadProblemsRow>(), uploadProblemsDao.findAll(), "Upload problems")
    assertStatus(UploadStatus.AwaitingProcessing)
  }

  @Test
  fun `validateCsv sets upload status to Invalid if there are validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("bogus")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingValidation,
        storageUrl = storageUrl)

    importer.validateCsv(uploadId)

    val expectedProblems =
        listOf(
            UploadProblemsRow(
                UploadProblemId(1),
                uploadId,
                UploadProblemType.MalformedValue,
                true,
                1,
                null,
                messages.csvBadHeader()))

    val actualProblems = uploadProblemsDao.findAll()
    assertEquals(expectedProblems, actualProblems, "Upload problems")
    assertStatus(UploadStatus.Invalid)
  }

  @Test
  fun `validateCsv schedules import if there are no validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nNew name,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingValidation,
        storageUrl = storageUrl)

    importer.validateCsv(uploadId)

    assertEquals(emptyList<UploadProblemsRow>(), uploadProblemsDao.findAll(), "Upload problems")
    assertStatus(UploadStatus.AwaitingProcessing)
    verify { scheduler.enqueue<SpeciesImporter>(any()) }
  }

  @Test
  fun `importCsv creates new species with normalized scientific name`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nNew—name a–b,Common,Family,true,false,Shrub,Recalcitrant,\"Tundra \r\n Mangroves \r\n\"") // note the dash types in the scientific name
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(2, "Existing name")

    importer.importCsv(uploadId, true)

    val expectedSpecies =
        setOf(
            SpeciesRow(
                id = SpeciesId(1),
                organizationId = organizationId,
                scientificName = "New-name a-b", // dashes replaced by hyphens
                initialScientificName = "New-name a-b",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH),
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH),
        )

    val actualSpecies = speciesDao.findAll().toSet()
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes =
        setOf(
            SpeciesEcosystemTypesRow(SpeciesId(1), EcosystemType.Tundra),
            SpeciesEcosystemTypesRow(SpeciesId(1), EcosystemType.Mangroves),
        )

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv throws exception if upload is not awaiting processing`() {
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingValidation,
        storageUrl = storageUrl)

    assertThrows<IllegalStateException> { importer.importCsv(uploadId, true) }
  }

  @Test
  fun `importCsv updates existing species if overwrite flag is set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "Existing name,Common,Family,true,false,Shrub,Recalcitrant,Tundra\n" +
                "Initial name,New common,NewFamily,false,true,Shrub,Recalcitrant,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(2, "Existing name", ecosystemTypes = setOf(EcosystemType.Mangroves))
    insertSpecies(3, "New name", initialScientificName = "Initial name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    val expectedSpecies =
        setOf(
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now),
            SpeciesRow(
                id = SpeciesId(3),
                organizationId = organizationId,
                scientificName = "New name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                rare = true,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now))

    val actualSpecies = speciesDao.findAll().toSet()
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes = setOf(SpeciesEcosystemTypesRow(SpeciesId(2), EcosystemType.Tundra))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv prefers current name over initial name when updating existing species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nDuplicate name,New common,NewFamily,false,true,Shrub,Recalcitrant,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(2, "Duplicate name", initialScientificName = "Initial name")
    insertSpecies(3, "Nonduplicate name", initialScientificName = "Duplicate name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    val expected =
        setOf(
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "Duplicate name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                rare = true,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now),
            SpeciesRow(
                id = SpeciesId(3),
                organizationId = organizationId,
                scientificName = "Nonduplicate name",
                initialScientificName = "Duplicate name",
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH))

    val actual = speciesDao.findAll().toSet()
    assertEquals(expected, actual)
    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv leaves existing species alone if overwrite flag is not set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "Existing name,Common,Family,true,false,Shrub,Recalcitrant,Tundra\n" +
                "Initial name,New common,NewFamily,false,true,Shrub,Recalcitrant,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(10, "Existing name", ecosystemTypes = setOf(EcosystemType.Mangroves))
    insertSpecies(11, "New name", initialScientificName = "Initial name")

    clock.instant = Instant.EPOCH + Duration.ofDays(1)

    val expectedSpecies = speciesDao.findAll().toSet()

    importer.importCsv(uploadId, false)

    val actual = speciesDao.findAll().toSet()
    assertEquals(expectedSpecies, actual)

    val expectedEcosystemTypes =
        setOf(SpeciesEcosystemTypesRow(SpeciesId(10), EcosystemType.Mangroves))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv updates existing deleted species even if overwrite flag is not set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nExisting name,Common,Family,true,false,Shrub,Recalcitrant,Tundra\n")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(
        2,
        "Existing name",
        deletedTime = Instant.EPOCH,
        ecosystemTypes = setOf(EcosystemType.Mangroves))

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    val expectedSpecies =
        listOf(
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now))

    val actualSpecies = speciesDao.findAll()
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes = setOf(SpeciesEcosystemTypesRow(SpeciesId(2), EcosystemType.Tundra))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv does not apply renames from deleted species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nInitial name,New common,NewFamily,false,true,Shrub,Recalcitrant,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    insertSpecies(
        2, "Renamed name", deletedTime = Instant.EPOCH, initialScientificName = "Initial name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    val expected =
        listOf(
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "Renamed name",
                initialScientificName = "Initial name",
                createdBy = userId,
                createdTime = Instant.EPOCH,
                deletedBy = userId,
                deletedTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH),
            SpeciesRow(
                id = SpeciesId(1),
                organizationId = organizationId,
                scientificName = "Initial name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                rare = true,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = now,
                modifiedBy = userId,
                modifiedTime = now),
        )

    val actual = speciesDao.findAll()
    assertEquals(expected.toSet(), actual.toSet())
    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv rolls back changes and sets upload to failed if an error occurs`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nNew name,Common,Family,true,false,Shrub,Recalcitrant,")
    insertUpload(
        uploadId,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)
    // Species ID will collide with the autogenerated primary key
    insertSpecies(1, "Existing name")

    val expected = speciesDao.findAll()

    importer.importCsv(uploadId, true)

    val actual = speciesDao.findAll()
    assertEquals(expected, actual)
    assertStatus(UploadStatus.ProcessingFailed)
  }

  @Test
  fun `importCsv accepts localized values for enumerated fields`() {
    val gibberishTrue = "true".toGibberish()
    val gibberishFalse = "NO".toGibberish()
    val gibberishShrub = "Shrub".toGibberish()
    val gibberishRecalcitrant = "Recalcitrant".toGibberish()
    val gibberishMangroves = "Mangroves".toGibberish()

    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "New name,,,$gibberishTrue,$gibberishFalse,$gibberishShrub,$gibberishRecalcitrant,$gibberishMangroves")
    insertUpload(
        uploadId,
        locale = Locales.GIBBERISH,
        organizationId = organizationId,
        status = UploadStatus.AwaitingProcessing,
        storageUrl = storageUrl)

    val expected =
        listOf(
            SpeciesRow(
                id = SpeciesId(1),
                organizationId = organizationId,
                scientificName = "New name",
                initialScientificName = "New name",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                growthFormId = GrowthForm.Shrub,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH))

    importer.importCsv(uploadId, true)

    val actual = speciesDao.findAll()
    assertEquals(expected, actual)
  }

  private fun assertStatus(expectedStatus: UploadStatus, id: UploadId = uploadId) {
    val actualStatus =
        dslContext
            .select(UPLOADS.STATUS_ID)
            .from(UPLOADS)
            .where(UPLOADS.ID.eq(id))
            .fetchOne(UPLOADS.STATUS_ID)
    assertEquals(expectedStatus, actualStatus, "Upload status")
  }

  private fun sizedInputStream(content: ByteArray) =
      SizedInputStream(content.inputStream(), content.size.toLong())
  private fun sizedInputStream(content: String) = sizedInputStream(content.toByteArray())

  companion object {
    @JvmStatic fun supportedLocales() = listOf(Locale.US, Locales.GIBBERISH)
  }
}
