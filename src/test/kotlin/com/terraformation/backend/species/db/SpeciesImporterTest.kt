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
import com.terraformation.backend.db.default_schema.PlantMaterialSourcingMethod
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SuccessionalGroup
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UploadProblemType
import com.terraformation.backend.db.default_schema.UploadStatus
import com.terraformation.backend.db.default_schema.UploadType
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.UploadProblemsRow
import com.terraformation.backend.db.default_schema.tables.records.SpeciesEcosystemTypesRecord
import com.terraformation.backend.db.default_schema.tables.records.SpeciesGrowthFormsRecord
import com.terraformation.backend.db.default_schema.tables.records.SpeciesPlantMaterialSourcingMethodsRecord
import com.terraformation.backend.db.default_schema.tables.records.SpeciesRecord
import com.terraformation.backend.db.default_schema.tables.records.SpeciesSuccessionalGroupsRecord
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_ECOSYSTEM_TYPES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_GROWTH_FORMS
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
import org.junit.jupiter.api.Assertions.assertEquals
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
        speciesProblemsDao,
    )
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
        userStore,
    )
  }

  private val header = speciesCsvColumnNames.joinToString(",")

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
              type = UploadType.SpeciesCSV,
          )

      importer.validateCsv(uploadId)

      assertTableEmpty(UPLOAD_PROBLEMS)
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
        emptyList<List<Locale>>(),
        localesWithSameTemplates,
        "Locales with same CSV template",
    )
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
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nExisting name,,,,,,,,,,,,,")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl,
        )
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
                value = "Existing name",
            )
        )
    )
  }

  @Test
  fun `validateCsv detects duplicate species even if they were renamed`() {
    every { fileStore.read(storageUrl) } answers
        {
          sizedInputStream("$header\nInitial name,,,,,,,,,,,,,")
        }
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
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
                value = "Corrected name (Initial name)",
            )
        )
    )
  }

  @Test
  fun `validateCsv does not treat deleted species as name collisions`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nExisting name,,,,,,,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl,
        )
    insertSpecies(scientificName = "Existing name", deletedTime = Instant.EPOCH)

    importer.validateCsv(uploadId)

    assertTableEmpty(UPLOAD_PROBLEMS)
    assertStatus(UploadStatus.AwaitingProcessing)
  }

  @Test
  fun `validateCsv sets upload status to Invalid if there are validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("bogus")
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl,
        )

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
                message = messages.csvBadHeader(),
            )
        )
    )
  }

  @Test
  fun `validateCsv schedules import if there are no validation errors`() {
    every { fileStore.read(storageUrl) } returns sizedInputStream("$header\nNew name,,,,,,,,,,,,,")
    every { scheduler.enqueue<SpeciesImporter>(any()) } returns JobId(UUID.randomUUID())
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl,
        )

    importer.validateCsv(uploadId)

    assertTableEmpty(UPLOAD_PROBLEMS)
    assertStatus(UploadStatus.AwaitingProcessing)
    verify { scheduler.enqueue<SpeciesImporter>(any()) }
  }

  @Test
  fun `importCsv creates new species with normalized scientific name`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nNew—name a–b," + // note the dash types in the scientific name
                "Common,Family,NT,false,\"Shrub\nHerb\",Recalcitrant,\"Tundra \r\n Mangroves \r\n\"," +
                "Native,\"Pioneer\nMature\",Eco role,Local uses,\"Wildling harvest\nOther\",Facts\n"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    val existingSpeciesId = insertSpecies(scientificName = "Existing name")

    importer.importCsv(uploadId, true)

    val newSpeciesId = speciesDao.findAll().map { it.id!! }.maxOf { it }

    assertTableEquals(
        listOf(
            SpeciesRecord(
                createdBy = userId,
                createdTime = Instant.EPOCH,
                id = existingSpeciesId,
                initialScientificName = "Existing name",
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Existing name",
            ),
            SpeciesRecord(
                commonName = "Common",
                conservationCategoryId = ConservationCategory.NearThreatened,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                ecologicalRoleKnown = "Eco role",
                familyName = "Family",
                id = newSpeciesId,
                initialScientificName = "New-name a-b",
                localUsesKnown = "Local uses",
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH,
                nativeEcosystem = "Native",
                organizationId = organizationId,
                otherFacts = "Facts",
                rare = false,
                scientificName = "New-name a-b", // dashes replaced by hyphens
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ),
        )
    )

    assertTableEquals(
        setOf(
            SpeciesEcosystemTypesRecord(newSpeciesId, EcosystemType.Tundra),
            SpeciesEcosystemTypesRecord(newSpeciesId, EcosystemType.Mangroves),
        )
    )

    assertTableEquals(
        setOf(
            SpeciesGrowthFormsRecord(newSpeciesId, GrowthForm.Herb),
            SpeciesGrowthFormsRecord(newSpeciesId, GrowthForm.Shrub),
        )
    )

    assertTableEquals(
        setOf(
            SpeciesSuccessionalGroupsRecord(newSpeciesId, SuccessionalGroup.Pioneer),
            SpeciesSuccessionalGroupsRecord(newSpeciesId, SuccessionalGroup.Mature),
        )
    )

    assertTableEquals(
        setOf(
            SpeciesPlantMaterialSourcingMethodsRecord(
                newSpeciesId,
                PlantMaterialSourcingMethod.Other,
            ),
            SpeciesPlantMaterialSourcingMethodsRecord(
                newSpeciesId,
                PlantMaterialSourcingMethod.WildlingHarvest,
            ),
        )
    )

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv throws exception if upload is not awaiting processing`() {
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingValidation,
            storageUrl = storageUrl,
        )

    assertThrows<IllegalStateException> { importer.importCsv(uploadId, true) }
  }

  @Test
  fun `importCsv updates existing species if overwrite flag is set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "Existing name,Common,Family,en,false,Shrub,Recalcitrant,Tundra,,,,,,\n" +
                "Initial name,New common,NewFamily,lc,true,Shrub,Recalcitrant,,Native," +
                "Mature,Eco,Local,Other,Facts"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    val speciesId1 =
        insertSpecies(
            scientificName = "Existing name",
            ecosystemTypes = setOf(EcosystemType.Mangroves),
            growthForms = setOf(GrowthForm.Shrub),
            plantMaterialSourcingMethods = setOf(PlantMaterialSourcingMethod.WildlingHarvest),
            successionalGroups = setOf(SuccessionalGroup.Pioneer),
        )
    val speciesId2 =
        insertSpecies(
            scientificName = "New name",
            ecosystemTypes = setOf(EcosystemType.Mangroves),
            growthForms = setOf(GrowthForm.Shrub),
            initialScientificName = "Initial name",
        )

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    assertTableEquals(
        setOf(
            SpeciesRecord(
                commonName = "Common",
                conservationCategoryId = ConservationCategory.Endangered,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                familyName = "Family",
                id = speciesId1,
                initialScientificName = "Existing name",
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = organizationId,
                rare = false,
                scientificName = "Existing name",
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ),
            SpeciesRecord(
                commonName = "New common",
                conservationCategoryId = ConservationCategory.LeastConcern,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                ecologicalRoleKnown = "Eco",
                familyName = "NewFamily",
                id = speciesId2,
                initialScientificName = "Initial name",
                localUsesKnown = "Local",
                modifiedBy = userId,
                modifiedTime = now,
                nativeEcosystem = "Native",
                organizationId = organizationId,
                otherFacts = "Facts",
                rare = true,
                scientificName = "New name",
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ),
        )
    )

    assertTableEquals(SpeciesEcosystemTypesRecord(speciesId1, EcosystemType.Tundra))

    assertTableEquals(
        setOf(
            SpeciesGrowthFormsRecord(speciesId1, GrowthForm.Shrub),
            SpeciesGrowthFormsRecord(speciesId2, GrowthForm.Shrub),
        )
    )

    assertTableEquals(
        SpeciesPlantMaterialSourcingMethodsRecord(speciesId2, PlantMaterialSourcingMethod.Other)
    )

    assertTableEquals(SpeciesSuccessionalGroupsRecord(speciesId2, SuccessionalGroup.Mature))

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv prefers current name over initial name when updating existing species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nDuplicate name,New common,NewFamily,vu,true,Shrub,Recalcitrant,,,,,,,"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    val speciesId1 =
        insertSpecies(scientificName = "Duplicate name", initialScientificName = "Initial name")
    val speciesId2 =
        insertSpecies(
            scientificName = "Nonduplicate name",
            initialScientificName = "Duplicate name",
        )

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, true)

    assertTableEquals(
        setOf(
            SpeciesRecord(
                commonName = "New common",
                conservationCategoryId = ConservationCategory.Vulnerable,
                createdBy = userId,
                createdTime = Instant.EPOCH,
                familyName = "NewFamily",
                id = speciesId1,
                initialScientificName = "Initial name",
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = organizationId,
                rare = true,
                scientificName = "Duplicate name",
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ),
            SpeciesRecord(
                createdBy = userId,
                createdTime = Instant.EPOCH,
                id = speciesId2,
                initialScientificName = "Duplicate name",
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Nonduplicate name",
            ),
        )
    )
    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv leaves existing species alone if overwrite flag is not set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "Existing name,Common,Family,EN,false,Shrub,Recalcitrant,Tundra,,,,,,\n" +
                "Initial name,New common,NewFamily,LC,true,Shrub,Recalcitrant,,,,,,,"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    insertSpecies(
        scientificName = "Existing name",
        growthForms = setOf(GrowthForm.Shrub),
        ecosystemTypes = setOf(EcosystemType.Mangroves),
    )
    insertSpecies(scientificName = "New name", initialScientificName = "Initial name")

    clock.instant = Instant.EPOCH + Duration.ofDays(1)

    val expectedSpecies = dslContext.fetch(SPECIES)
    val expectedEcosystemTypes = dslContext.fetch(SPECIES_ECOSYSTEM_TYPES)
    val expectedGrowthForms = dslContext.fetch(SPECIES_GROWTH_FORMS)

    importer.importCsv(uploadId, false)

    assertTableEquals(expectedSpecies)
    assertTableEquals(expectedEcosystemTypes)
    assertTableEquals(expectedGrowthForms)

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv updates existing deleted species even if overwrite flag is not set`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nExisting name,Common,Family,EN,false,Shrub,Recalcitrant,Tundra,,,,,,\n"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    val speciesId1 =
        insertSpecies(
            scientificName = "Existing name",
            deletedTime = Instant.EPOCH,
            ecosystemTypes = setOf(EcosystemType.Mangroves),
        )

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    assertTableEquals(
        SpeciesRecord(
            commonName = "Common",
            conservationCategoryId = ConservationCategory.Endangered,
            createdBy = userId,
            createdTime = Instant.EPOCH,
            familyName = "Family",
            id = speciesId1,
            initialScientificName = "Existing name",
            modifiedBy = userId,
            modifiedTime = now,
            organizationId = organizationId,
            rare = false,
            scientificName = "Existing name",
            seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
        )
    )

    assertTableEquals(SpeciesEcosystemTypesRecord(speciesId1, EcosystemType.Tundra))

    assertTableEquals(SpeciesGrowthFormsRecord(speciesId1, GrowthForm.Shrub))

    assertStatus(UploadStatus.Completed)
  }

  @Test
  fun `importCsv does not apply renames from deleted species`() {
    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\nInitial name,New common,NewFamily,,true,Shrub,Recalcitrant,,,,,,,"
        )
    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )
    insertSpecies(
        scientificName = "Renamed name",
        deletedTime = Instant.EPOCH,
        initialScientificName = "Initial name",
    )

    val now = clock.instant() + Duration.ofDays(1)
    clock.instant = now

    importer.importCsv(uploadId, false)

    assertTableEquals(
        setOf(
            SpeciesRecord(
                createdBy = userId,
                createdTime = Instant.EPOCH,
                deletedBy = userId,
                deletedTime = Instant.EPOCH,
                initialScientificName = "Initial name",
                modifiedBy = userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                scientificName = "Renamed name",
            ),
            SpeciesRecord(
                commonName = "New common",
                createdBy = userId,
                createdTime = now,
                familyName = "NewFamily",
                initialScientificName = "Initial name",
                modifiedBy = userId,
                modifiedTime = now,
                organizationId = organizationId,
                rare = true,
                scientificName = "Initial name",
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ),
        )
    )

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
            userStore,
        )
    every { failingSpeciesStore.importSpecies(any(), any()) } throws RuntimeException("Failed!")
    every { fileStore.read(storageUrl) } returns
        sizedInputStream("$header\nNew name,Common,Family,CR,false,Shrub,Recalcitrant,Tundra")

    val uploadId =
        insertUpload(
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )

    failingImporter.importCsv(uploadId, true)

    assertStatus(UploadStatus.ProcessingFailed)
  }

  @Test
  fun `importCsv accepts localized values for enumerated fields`() {
    val gibberishTrue = "true".toGibberish()
    val gibberishShrub = "Shrub".toGibberish()
    val gibberishRecalcitrant = "Recalcitrant".toGibberish()
    val gibberishMangroves = "Mangroves".toGibberish()
    val gibberishEarlySecondary = "Early secondary".toGibberish()
    val gibberishSeedlingPurchase = "Seedling purchase".toGibberish()

    every { fileStore.read(storageUrl) } returns
        sizedInputStream(
            "$header\n" +
                "New name,,,EW,$gibberishTrue,$gibberishShrub,$gibberishRecalcitrant," +
                "$gibberishMangroves,,$gibberishEarlySecondary,,,$gibberishSeedlingPurchase,\n"
        )
    val uploadId =
        insertUpload(
            locale = Locales.GIBBERISH,
            organizationId = organizationId,
            status = UploadStatus.AwaitingProcessing,
            storageUrl = storageUrl,
        )

    importer.importCsv(uploadId, true)

    val speciesId = speciesDao.findAll().map { it.id }.first()

    assertTableEquals(
        SpeciesRecord(
            conservationCategoryId = ConservationCategory.ExtinctInTheWild,
            createdBy = userId,
            createdTime = Instant.EPOCH,
            id = speciesId,
            initialScientificName = "New name",
            modifiedBy = userId,
            modifiedTime = Instant.EPOCH,
            organizationId = organizationId,
            rare = true,
            scientificName = "New name",
            seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
        )
    )
    assertTableEquals(
        SpeciesSuccessionalGroupsRecord(
            speciesId = speciesId,
            successionalGroupId = SuccessionalGroup.EarlySecondary,
        )
    )
    assertTableEquals(
        SpeciesPlantMaterialSourcingMethodsRecord(
            plantMaterialSourcingMethodId = PlantMaterialSourcingMethod.SeedlingPurchase,
            speciesId = speciesId,
        )
    )
  }

  private fun assertProblems(expected: List<UploadProblemsRow>) {
    val actual = uploadProblemsDao.findAll().sortedBy { it.id }.map { it.copy(id = null) }
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
    @JvmStatic
    fun supportedLocales() =
        listOf(Locales.ENGLISH, Locales.FRENCH, Locales.GIBBERISH, Locales.SPANISH)
  }
}
