package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.UploadNotAwaitingActionException
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesEcosystemTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesGrowthFormsRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.references.UPLOADS
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
  override val user = mockUser()

  private val clock = TestClock()
  private val fileStore: FileStore = mockk()
  private val messages = Messages()
  private val scheduler: JobScheduler = mockk()
  private val speciesChecker: SpeciesChecker = mockk()
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao)
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
      "Scientific Name,Common Name,Family,IUCN Category,Rare,Growth Form,Seed Storage Behavior,Ecosystem Types"

  private val storageUrl = URI.create("file:///test")

  private lateinit var organizationId: OrganizationId
  private lateinit var userId: UserId

  @BeforeEach
  fun setUp() {
    userId = user.userId
    organizationId = insertOrganization()

    every { speciesChecker.checkAllUncheckedSpecies(any()) } just Runs
    every { user.canCreateSpecies(any()) } returns true
    every { user.canDeleteUpload(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canReadUpload(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canUpdateUpload(any()) } returns true
    every { userStore.fetchOneById(userId) } returns user
  }

  @Test
  fun `receiveCsv schedules validate job`() {
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    every { uploadService.receive(any(), any(), any(), any(), any()) } returns UploadId(1)

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

      val uploadId =
          insertUpload(
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
    val uploadId = insertUpload(status = UploadStatus.Processing)

    assertThrows<UploadNotAwaitingActionException> { importer.cancelProcessing(uploadId) }
  }

  @Test
  fun `cancelProcessing deletes upload if it is awaiting user action`() {
    val uploadId = insertUpload(status = UploadStatus.AwaitingUserAction)
    every { uploadService.delete(uploadId) } just Runs

    importer.cancelProcessing(uploadId)

    verify { uploadService.delete(uploadId) }
  }

  @Test
  fun `resolveWarnings throws exception if upload is not awaiting user action`() {
    val uploadId = insertUpload(status = UploadStatus.Processing)

    assertThrows<UploadNotAwaitingActionException> { importer.resolveWarnings(uploadId, true) }
  }

  @Test
  fun `resolveWarnings schedules import job`() {
    val uploadId = insertUpload(status = UploadStatus.AwaitingUserAction)
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())

    importer.resolveWarnings(uploadId, true)

    verify { scheduler.enqueue<SpeciesImporter>(any()) }
  }

  @Test
  fun `validateCsv detects existing scientific names`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nExisting name,,,,,,,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl)
    insertSpecies(scientificName = "Existing name")

    importer.validateCsv(uploadId)

    assertStatus(UploadStatus.AwaitingUserAction)
    assertProblems(
        listOf(
            UploadProblemsRow(
                uploadId = uploadId,
                typeId = UploadProblemType.DuplicateValue,
                isError = false,
                position = 2,
                field = "Scientific Name",
                message = messages.speciesCsvScientificNameExists(),
                value = "Existing name")))
  }

  @Test
  fun `validateCsv detects duplicate species even if they were renamed`() {
    every { fileStore.read(storageUrl) } answers
        {
          sizedInputStream("$header\nInitial name,,,,,,,")
        }
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    insertSpecies("Corrected name", initialScientificName = "Initial name")

    importer.validateCsv(uploadId)

    assertProblems(
        listOf(
            UploadProblemsRow(
                uploadId = uploadId,
                typeId = UploadProblemType.DuplicateValue,
                isError = false,
                position = 2,
                field = "Scientific Name",
                message = messages.speciesCsvScientificNameExists(),
                value = "Corrected name (Initial name)")))
  }

  @Test
  fun `validateCsv does not treat deleted species as name collisions`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nExisting name,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl)
    insertSpecies(scientificName = "Existing name", deletedTime = Instant.EPOCH)

    importer.validateCsv(uploadId)

    assertEquals(emptyList<UploadProblemsRow>(), uploadProblemsDao.findAll(), "Upload problems")
    assertStatus(UploadStatus.AwaitingProcessing)
  }

  @Test
  fun `validateCsv sets upload status to Invalid if there are validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("bogus")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl)

    importer.validateCsv(uploadId)

    assertStatus(UploadStatus.Invalid)
    assertProblems(
        listOf(
            UploadProblemsRow(
                uploadId = uploadId,
                typeId = UploadProblemType.MalformedValue,
                isError = true,
                position = 1,
                field = null,
                message = messages.csvBadHeader())))
  }

  @Test
  fun `validateCsv schedules import if there are no validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nNew name,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    val uploadId =
        insertUpload(
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
            "$header\nNew—name a–b,Common,Family,NT,false,Shrub,Recalcitrant,\"Tundra \r\n Mangroves \r\n\"") // note the dash types in the scientific name
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    insertSpecies(scientificName = "Existing name")

    importer.importCsv(uploadId, true)

    val expectedSpecies =
        listOf(
            SpeciesRow(
                id = SpeciesId(1),
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH),
            SpeciesRow(
                id = SpeciesId(2),
                organizationId = organizationId,
                scientificName = "New-name a-b", // dashes replaced by hyphens
                initialScientificName = "New-name a-b",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.NearThreatened,
                rare = false,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH),
        )

    val actualSpecies = speciesDao.findAll()
    val mappedSpeciesIds = mapTo1IndexedIds(actualSpecies, ::SpeciesId, SpeciesRow::id)
    assertEquals(
        expectedSpecies.map { it.copy(id = null) }.toSet(),
        actualSpecies.map { it.copy(id = null) }.toSet())

    val mappedSpeciesId2 = mappedSpeciesIds[SpeciesId(2)]
    val expectedEcosystemTypes =
        setOf(
            SpeciesEcosystemTypesRow(mappedSpeciesId2, EcosystemType.Tundra),
            SpeciesEcosystemTypesRow(mappedSpeciesId2, EcosystemType.Mangroves),
        )

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    val expectedGrowthForms =
        setOf(
            SpeciesGrowthFormsRow(mappedSpeciesId2, GrowthForm.Shrub),
        )

    val actualGrowthForms = speciesGrowthFormsDao.findAll().toSet()
    assertEquals(expectedGrowthForms, actualGrowthForms)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv throws exception if upload is not awaiting processing`() {
    val uploadId =
        insertUpload(
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
                "Existing name,Common,Family,en,false,Shrub,Recalcitrant,Tundra\n" +
                "Initial name,New common,NewFamily,lc,true,Shrub,Recalcitrant,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    val speciesId1 =
        insertSpecies(
            scientificName = "Existing name",
            ecosystemTypes = setOf(EcosystemType.Mangroves),
            growthForms = setOf(GrowthForm.Shrub))
    val speciesId2 =
        insertSpecies(
            scientificName = "New name",
            growthForms = setOf(GrowthForm.Shrub),
            initialScientificName = "Initial name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    val expectedSpecies =
        setOf(
            SpeciesRow(
                id = speciesId1,
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now),
            SpeciesRow(
                id = speciesId2,
                organizationId = organizationId,
                scientificName = "New name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                conservationCategoryId = ConservationCategory.LeastConcern,
                rare = true,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now))

    val actualSpecies = speciesDao.findAll().toSet()
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes = setOf(SpeciesEcosystemTypesRow(speciesId1, EcosystemType.Tundra))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    val expectedGrowthForms =
        setOf(
            SpeciesGrowthFormsRow(speciesId1, GrowthForm.Shrub),
            SpeciesGrowthFormsRow(speciesId2, GrowthForm.Shrub),
        )

    val actualGrowthForms = speciesGrowthFormsDao.findAll().toSet()
    assertEquals(expectedGrowthForms, actualGrowthForms)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv prefers current name over initial name when updating existing species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nDuplicate name,New common,NewFamily,vu,true,Shrub,Recalcitrant,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    val speciesId1 =
        insertSpecies(scientificName = "Duplicate name", initialScientificName = "Initial name")
    val speciesId2 =
        insertSpecies(
            scientificName = "Nonduplicate name", initialScientificName = "Duplicate name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    val expected =
        setOf(
            SpeciesRow(
                id = speciesId1,
                organizationId = organizationId,
                scientificName = "Duplicate name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                conservationCategoryId = ConservationCategory.Vulnerable,
                rare = true,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now),
            SpeciesRow(
                id = speciesId2,
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
                "Existing name,Common,Family,EN,false,Shrub,Recalcitrant,Tundra\n" +
                "Initial name,New common,NewFamily,LC,true,Shrub,Recalcitrant,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    val speciesId1 =
        insertSpecies(
            scientificName = "Existing name",
            growthForms = setOf(GrowthForm.Shrub),
            ecosystemTypes = setOf(EcosystemType.Mangroves))
    insertSpecies(scientificName = "New name", initialScientificName = "Initial name")

    clock.instant = Instant.EPOCH + Duration.ofDays(1)

    val expectedSpecies = speciesDao.findAll().toSet()

    importer.importCsv(uploadId, false)

    val actual = speciesDao.findAll().toSet()
    assertEquals(expectedSpecies, actual)

    val expectedEcosystemTypes =
        setOf(SpeciesEcosystemTypesRow(speciesId1, EcosystemType.Mangroves))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    val expectedGrowthForms = setOf(SpeciesGrowthFormsRow(speciesId1, GrowthForm.Shrub))

    val actualGrowthForms = speciesGrowthFormsDao.findAll().toSet()
    assertEquals(expectedGrowthForms, actualGrowthForms)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv updates existing deleted species even if overwrite flag is not set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nExisting name,Common,Family,EN,false,Shrub,Recalcitrant,Tundra\n")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    val speciesId1 =
        insertSpecies(
            scientificName = "Existing name",
            deletedTime = Instant.EPOCH,
            ecosystemTypes = setOf(EcosystemType.Mangroves))

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    val expectedSpecies =
        listOf(
            SpeciesRow(
                id = speciesId1,
                organizationId = organizationId,
                scientificName = "Existing name",
                initialScientificName = "Existing name",
                commonName = "Common",
                familyName = "Family",
                conservationCategoryId = ConservationCategory.Endangered,
                rare = false,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = now))

    val actualSpecies = speciesDao.findAll()
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes = setOf(SpeciesEcosystemTypesRow(speciesId1, EcosystemType.Tundra))

    val actualEcosystemTypes = speciesEcosystemTypesDao.findAll().toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)

    val expectedGrowthForms = setOf(SpeciesGrowthFormsRow(speciesId1, GrowthForm.Shrub))

    val actualGrowthForms = speciesGrowthFormsDao.findAll().toSet()
    assertEquals(expectedGrowthForms, actualGrowthForms)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv does not apply renames from deleted species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nInitial name,New common,NewFamily,,true,Shrub,Recalcitrant,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)
    insertSpecies(
        scientificName = "Renamed name",
        deletedTime = Instant.EPOCH,
        initialScientificName = "Initial name")

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    val expected =
        listOf(
            SpeciesRow(
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
                organizationId = organizationId,
                scientificName = "Initial name",
                initialScientificName = "Initial name",
                commonName = "New common",
                familyName = "NewFamily",
                rare = true,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = now,
                modifiedBy = userId,
                modifiedTime = now),
        )

    assertEquals(expected.toSet(), speciesDao.findAll().map { it.copy(id = null) }.toSet())
    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv sets upload to failed if an error occurs`() {
    val failingSpeciesStore = mockk<SpeciesStore>()
    val failingImporter =
        SpeciesImporter(
            dslContext,
            fileStore,
            messages,
            scheduler,
            speciesChecker,
            failingSpeciesStore,
            uploadProblemsDao,
            uploadsDao,
            uploadService,
            uploadStore,
            userStore)
    every { failingSpeciesStore.importSpecies(any(), any()) } throws RuntimeException("Failed!")
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nNew name,Common,Family,CR,false,Shrub,Recalcitrant,Tundra")

    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)

    failingImporter.importCsv(uploadId, true)

    assertStatus(UploadStatus.ProcessingFailed)
  }

  @Test
  fun `importCsv accepts localized values for enumerated fields`() {
    val gibberishTrue = "true".toGibberish()
    val gibberishShrub = "Shrub".toGibberish()
    val gibberishRecalcitrant = "Recalcitrant".toGibberish()
    val gibberishMangroves = "Mangroves".toGibberish()

    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "New name,,,EW,$gibberishTrue,$gibberishShrub,$gibberishRecalcitrant,$gibberishMangroves")
    val uploadId =
        insertUpload(
            locale = Locales.GIBBERISH,
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl)

    val expected =
        listOf(
            SpeciesRow(
                organizationId = organizationId,
                scientificName = "New name",
                initialScientificName = "New name",
                conservationCategoryId = ConservationCategory.ExtinctInTheWild,
                rare = true,
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH))

    importer.importCsv(uploadId, true)

    assertEquals(expected, speciesDao.findAll().map { it.copy(id = null) })
  }

  private fun assertProblems(expected: List<UploadProblemsRow>) {
    val actual = uploadProblemsDao.findAll().sortedBy { it.id!!.value }.map { it.copy(id = null) }
    assertEquals(expected, actual)
  }

  private fun assertStatus(expectedStatus: UploadStatus, id: UploadId = inserted.uploadId) {
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
