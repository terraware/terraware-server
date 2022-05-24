package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.AppDeviceStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.AppDeviceModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.AppDeviceId
import com.terraformation.backend.db.BagId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.GeolocationId
import com.terraformation.backend.db.GerminationSeedType
import com.terraformation.backend.db.GerminationSubstrate
import com.terraformation.backend.db.GerminationTestId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.GerminationTreatment
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SourcePlantOrigin
import com.terraformation.backend.db.SpeciesEndangeredType
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.db.sequences.ACCESSION_NUMBER_SEQ
import com.terraformation.backend.db.tables.pojos.AccessionPhotosRow
import com.terraformation.backend.db.tables.pojos.AccessionStateHistoryRow
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.BagsRow
import com.terraformation.backend.db.tables.pojos.GerminationTestsRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.tables.records.AccessionStateHistoryRecord
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.backend.db.tables.references.ACCESSION_SECONDARY_COLLECTORS
import com.terraformation.backend.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.db.tables.references.APP_DEVICES
import com.terraformation.backend.db.tables.references.BAGS
import com.terraformation.backend.db.tables.references.GEOLOCATIONS
import com.terraformation.backend.db.tables.references.GERMINATION_TESTS
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.WITHDRAWALS
import com.terraformation.backend.mockUser
import com.terraformation.backend.seedbank.api.CreateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.DeviceInfoPayload
import com.terraformation.backend.seedbank.api.GerminationPayload
import com.terraformation.backend.seedbank.api.GerminationTestPayload
import com.terraformation.backend.seedbank.api.SeedQuantityPayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.kilograms
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionSource
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.model.GerminationModel
import com.terraformation.backend.seedbank.model.GerminationTestModel
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import org.jooq.Record
import org.jooq.Sequence
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

internal class AccessionStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  override val sequencesToReset: List<Sequence<Long>>
    get() = listOf(ACCESSION_NUMBER_SEQ)

  override val tablesToResetSequences: List<Table<out Record>>
    get() =
        listOf(ACCESSIONS, APP_DEVICES, BAGS, GEOLOCATIONS, GERMINATION_TESTS, SPECIES, WITHDRAWALS)

  private val accessionNumbers =
      listOf(
          "19700101000",
          "19700101001",
          "19700101002",
          "19700101003",
          "19700101004",
          "19700101005",
          "19700101006",
          "19700101007",
      )

  private val clock: Clock = mockk()

  private lateinit var store: AccessionStore
  private lateinit var parentStore: ParentStore

  private val facilityId = FacilityId(100)
  private val organizationId = OrganizationId(1)

  @BeforeEach
  fun init() {
    parentStore = ParentStore(dslContext)

    val speciesStore = SpeciesStore(clock, dslContext, speciesDao)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    every { user.canCreateAccession(any()) } returns true
    every { user.canCreateSpecies(organizationId) } returns true
    every { user.canDeleteSpecies(any()) } returns true
    every { user.canReadAccession(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdateAccession(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true

    store =
        AccessionStore(
            dslContext,
            AppDeviceStore(dslContext, clock),
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            GerminationStore(dslContext),
            parentStore,
            speciesStore,
            WithdrawalStore(dslContext, clock),
            clock,
        )

    insertSiteData()
  }

  @Test
  fun `create of empty accession populates default values`() {
    store.create(AccessionModel(facilityId = facilityId))

    assertEquals(
        AccessionsRow(
            id = AccessionId(1),
            facilityId = facilityId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            number = accessionNumbers[0],
            stateId = AccessionState.AwaitingCheckIn),
        accessionsDao.fetchOneById(AccessionId(1)))
  }

  @Test
  fun `create deals with collisions in accession numbers`() {
    store.create(AccessionModel(facilityId = facilityId))
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()
    store.create(AccessionModel(facilityId = facilityId))

    assertNotNull(accessionsDao.fetchOneByNumber(accessionNumbers[1]))
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    repeat(10) { store.create(AccessionModel(facilityId = facilityId)) }

    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()

    assertThrows<DuplicateKeyException> { store.create(AccessionModel(facilityId = facilityId)) }
  }

  @Test
  fun `create adds digit to accession number suffix if it exceeds 3 digits`() {
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000001000).execute()
    val inserted = store.create(AccessionModel(facilityId = facilityId))
    assertEquals(inserted.accessionNumber, "197001011000")
  }

  @Test
  fun `create with new species throws exception if user has no permission to create species`() {
    every { user.canCreateSpecies(organizationId) } returns false

    assertThrows<AccessDeniedException> {
      store.create(AccessionModel(facilityId = facilityId, species = "newSpecies"))
    }

    assertEquals(
        emptyList<AccessionsRow>(), accessionsDao.findAll(), "Should not have inserted accession")
  }

  @Test
  fun `existing rows are used for free-text fields that live in reference tables`() {
    val payload =
        AccessionModel(
            facilityId = facilityId,
            species = "test species",
            family = "test family",
            primaryCollector = "primary collector",
            secondaryCollectors = setOf("secondary 1", "secondary 2"))

    // First time inserts the reference table rows
    val initialAccession = store.create(payload)
    // Second time should reuse them
    val secondAccession = store.create(payload)

    val initialRow = accessionsDao.fetchOneByNumber(accessionNumbers[0])!!
    val secondRow = accessionsDao.fetchOneByNumber(accessionNumbers[1])!!

    assertNotEquals(initialRow.number, secondRow.number, "Accession numbers")
    assertEquals(initialRow.speciesId, secondRow.speciesId, "Species")
    assertEquals(
        initialRow.speciesId, initialAccession.speciesId, "Species ID as returned on insert")
    assertEquals(secondRow.speciesId, secondAccession.speciesId, "Species ID as returned on update")
    assertEquals(initialRow.familyName, secondRow.familyName, "Family")
    assertEquals(
        initialRow.primaryCollectorName, secondRow.primaryCollectorName, "Primary collector")

    assertEquals(2, getSecondaryCollectors(AccessionId(1)).size, "Number of secondary collectors")

    assertEquals(
        getSecondaryCollectors(AccessionId(1)),
        getSecondaryCollectors(AccessionId(2)),
        "Secondary collectors")
  }

  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = AccessionModel(bagNumbers = setOf("bag 1", "bag 2"), facilityId = facilityId)
    store.create(payload)
    store.create(payload)

    val initialBags = bagsDao.fetchByAccessionId(AccessionId(1)).toSet()
    val secondBags = bagsDao.fetchByAccessionId(AccessionId(2)).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial =
        store.create(AccessionModel(bagNumbers = setOf("bag 1", "bag 2"), facilityId = facilityId))
    val initialBags = bagsDao.fetchByAccessionId(AccessionId(1))

    // Insertion order is not defined by the API, so don't assume bag ID 1 is "bag 1".

    assertEquals(setOf(BagId(1), BagId(2)), initialBags.map { it.id }.toSet(), "Initial bag IDs")
    assertEquals(
        setOf("bag 1", "bag 2"), initialBags.map { it.bagNumber }.toSet(), "Initial bag numbers")

    val desired = initial.copy(bagNumbers = setOf("bag 2", "bag 3"))

    assertTrue(store.update(desired), "Update succeeded")

    val updatedBags = bagsDao.fetchByAccessionId(AccessionId(1))

    assertTrue(BagsRow(BagId(3), AccessionId(1), "bag 3") in updatedBags, "New bag inserted")
    assertTrue(updatedBags.none { it.bagNumber == "bag 1" }, "Missing bag deleted")
    assertEquals(
        initialBags.filter { it.bagNumber == "bag 2" },
        updatedBags.filter { it.bagNumber == "bag 2" },
        "Existing bag is not replaced")
  }

  @Test
  fun `device info is inserted at creation`() {
    val payload =
        AccessionModel(deviceInfo = AppDeviceModel(model = "model"), facilityId = facilityId)
    store.create(payload)

    val appDevice = appDevicesDao.fetchOneById(AppDeviceId(1))
    assertNotNull(appDevice, "Device row should have been inserted")
    assertNull(appDevice?.appName, "App name should be null")
    assertEquals(appDevice?.model, "model")
  }

  @Test
  fun `device info is retrieved`() {
    val payload =
        AccessionModel(deviceInfo = AppDeviceModel(model = "model"), facilityId = facilityId)
    val initial = store.create(payload)

    val fetched = store.fetchById(initial.id!!)

    assertNotNull(fetched?.deviceInfo)
    assertEquals("model", fetched?.deviceInfo?.model)
    assertEquals(AccessionSource.SeedCollectorApp, initial.source)
  }

  @Test
  fun `geolocations are inserted and deleted as needed`() {
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(3), BigDecimal(4)))))
    val initialGeos = geolocationsDao.fetchByAccessionId(AccessionId(1))

    // Insertion order is not defined by the API.

    assertEquals(
        setOf(GeolocationId(1), GeolocationId(2)),
        initialGeos.map { it.id }.toSet(),
        "Initial location IDs")
    assertEquals(100.0, initialGeos.firstNotNullOf { it.gpsAccuracy }, 0.1, "Accuracy is recorded")

    val desired =
        initial.copy(
            geolocations =
                setOf(
                    Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                    Geolocation(BigDecimal(5), BigDecimal(6))))

    assertTrue(store.update(desired), "Update succeeded")

    val updatedGeos = geolocationsDao.fetchByAccessionId(AccessionId(1))

    assertTrue(
        updatedGeos.any {
          it.id == GeolocationId(3) && it.latitude?.toInt() == 5 && it.longitude?.toInt() == 6
        },
        "New geo inserted")
    assertTrue(updatedGeos.none { it.latitude == BigDecimal(3) }, "Missing geo deleted")
    assertEquals(
        initialGeos.filter { it.latitude == BigDecimal(1) },
        updatedGeos.filter { it.latitude == BigDecimal(1) },
        "Existing geo retained")
  }

  @Test
  fun `germination test types are inserted at creation time`() {
    store.create(
        AccessionModel(
            germinationTestTypes = setOf(GerminationTestType.Lab), facilityId = facilityId))
    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(AccessionId(1)))
            .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)

    assertEquals(listOf(GerminationTestType.Lab), types)
  }

  @Test
  fun `germination tests are inserted by update`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val startDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val withTest =
        initial
            .toUpdatePayload()
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            testType = GerminationTestType.Lab, startDate = startDate)),
                processingMethod = ProcessingMethod.Count,
                initialQuantity = seeds(100))
    store.update(withTest.toModel(id = initial.id!!))

    val updatedTests = germinationTestsDao.fetchByAccessionId(AccessionId(1))
    assertEquals(
        listOf(
            GerminationTestsRow(
                accessionId = AccessionId(1),
                id = GerminationTestId(1),
                remainingQuantity = BigDecimal(100),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                startDate = startDate,
                testType = GerminationTestType.Lab,
            )),
        updatedTests)

    val updatedRow = accessionsDao.fetchOneById(AccessionId(1))
    assertNull(updatedRow?.totalViabilityPercent, "totalViabilityPercent")
    assertNull(updatedRow?.latestViabilityPercent, "latestViabilityPercent")
    assertNull(updatedRow?.latestGerminationRecordingDate, "latestGerminationRecordingDate")

    val updatedAccession = store.fetchById(AccessionId(1))!!
    assertNull(
        updatedAccession.germinationTests.first().germinations,
        "Empty list of germinations should be null in model")
  }

  @Test
  fun `germination test types are inserted by update`() {
    val initial =
        store.create(
            AccessionModel(
                germinationTestTypes = setOf(GerminationTestType.Lab), facilityId = facilityId))
    val desired =
        initial.copy(
            germinationTestTypes = setOf(GerminationTestType.Lab, GerminationTestType.Nursery))
    store.update(desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(AccessionId(1)))
            .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
    assertEquals(setOf(GerminationTestType.Lab, GerminationTestType.Nursery), types.toSet())
  }

  @Test
  fun `germination test types are deleted by update`() {
    val initial =
        store.create(
            AccessionModel(
                germinationTestTypes = setOf(GerminationTestType.Lab), facilityId = facilityId))
    val desired = initial.copy(germinationTestTypes = setOf(GerminationTestType.Nursery))
    store.update(desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(AccessionId(1)))
            .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
    assertEquals(listOf(GerminationTestType.Nursery), types)
  }

  @Test
  fun `existing germination tests are updated`() {
    val initial = createAndUpdate {
      it.copy(
          germinationTests = listOf(GerminationTestPayload(testType = GerminationTestType.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }

    val desired =
        initial.copy(
            germinationTests =
                listOf(
                    GerminationTestModel(
                        id = initial.germinationTests[0].id,
                        testType = GerminationTestType.Lab,
                        seedType = GerminationSeedType.Fresh,
                        treatment = GerminationTreatment.Scarify,
                        substrate = GerminationSubstrate.PaperPetriDish,
                        notes = "notes",
                        seedsSown = 5)))
    store.update(desired)

    val updatedTests = germinationTestsDao.fetchByAccessionId(AccessionId(1))
    assertEquals(
        listOf(
            GerminationTestsRow(
                id = GerminationTestId(1),
                accessionId = AccessionId(1),
                testType = GerminationTestType.Lab,
                seedTypeId = GerminationSeedType.Fresh,
                treatmentId = GerminationTreatment.Scarify,
                substrateId = GerminationSubstrate.PaperPetriDish,
                notes = "notes",
                seedsSown = 5,
                remainingQuantity = BigDecimal(95),
                remainingUnitsId = SeedQuantityUnits.Seeds)),
        updatedTests)
  }

  @Test
  fun `change to germination test weight remaining is propagated to withdrawal and accession`() {
    val initial = createAndUpdate {
      it.copy(
          germinationTests =
              listOf(
                  GerminationTestPayload(
                      testType = GerminationTestType.Lab, remainingQuantity = grams(75))),
          initialQuantity = grams(100),
          processingMethod = ProcessingMethod.Weight,
      )
    }

    assertEquals(
        grams<SeedQuantityModel>(75),
        initial.remaining,
        "Accession remaining quantity before update")
    assertEquals(
        grams<SeedQuantityModel>(75),
        initial.withdrawals[0].remaining,
        "Withdrawal quantities remaining before update")
    assertEquals(
        grams<SeedQuantityModel>(75),
        initial.germinationTests[0].remaining,
        "Test remaining quantity before update")

    val desired =
        initial.copy(
            germinationTests =
                listOf(
                    initial.germinationTests[0].copy(remaining = grams(60)),
                ),
        )
    val updated = store.updateAndFetch(desired)

    assertEquals(
        grams<SeedQuantityModel>(60),
        updated.remaining,
        "Accession remaining quantity after update")
    assertEquals(
        grams<SeedQuantityModel>(60),
        updated.withdrawals[0].remaining,
        "Withdrawal quantities remaining after update")
    assertEquals(
        grams<SeedQuantityModel>(60),
        updated.germinationTests[0].remaining,
        "Test remaining quantity after update")
  }

  @Test
  fun `cannot update germination test from a different accession`() {
    val other = createAndUpdate {
      it.copy(
          germinationTests = listOf(GerminationTestPayload(testType = GerminationTestType.Nursery)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }
    val initial = createAndUpdate {
      it.copy(
          germinationTests = listOf(GerminationTestPayload(testType = GerminationTestType.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100))
    }
    val desired =
        initial.copy(
            germinationTests =
                listOf(
                    GerminationTestModel(
                        id = other.germinationTests[0].id,
                        testType = GerminationTestType.Lab,
                        seedType = GerminationSeedType.Fresh,
                        treatment = GerminationTreatment.Scarify,
                        substrate = GerminationSubstrate.PaperPetriDish,
                        notes = "notes",
                        seedsSown = 5)))

    assertThrows<IllegalArgumentException> { store.update(desired) }
  }

  @Test
  fun `germinations are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial = createAndUpdate {
      it.copy(
          germinationTests = listOf(GerminationTestPayload(testType = GerminationTestType.Lab)),
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(200))
    }
    val desired =
        initial.copy(
            germinationTests =
                listOf(
                    GerminationTestModel(
                        id = initial.germinationTests[0].id,
                        testType = GerminationTestType.Lab,
                        seedsSown = 200,
                        germinations =
                            listOf(
                                GerminationModel(
                                    recordingDate = localDate, seedsGerminated = 75)))))
    store.update(desired)

    val germinationTests = germinationTestsDao.fetchByAccessionId(AccessionId(1))
    assertEquals(1, germinationTests.size, "Number of germination tests after update")
    assertEquals(37, germinationTests[0].totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, germinationTests[0].totalSeedsGerminated, "totalSeedsGerminated")

    val germinations = germinationsDao.fetchByTestId(GerminationTestId(1))
    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedAccession = accessionsDao.fetchOneById(AccessionId(1))
    assertEquals(37, updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    assertEquals(37, updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    assertEquals(
        localDate,
        updatedAccession?.latestGerminationRecordingDate,
        "latestGerminationRecordingDate")
  }

  @Test
  fun `germinations are deleted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(2000),
          germinationTests =
              listOf(
                  GerminationTestPayload(
                      testType = GerminationTestType.Lab,
                      seedsSown = 1000,
                      germinations =
                          listOf(
                              GerminationPayload(recordingDate = localDate, seedsGerminated = 75),
                              GerminationPayload(
                                  recordingDate = localDate.plusDays(1), seedsGerminated = 456)))))
    }

    val desired =
        initial.copy(
            germinationTests =
                listOf(
                    initial.germinationTests[0].copy(
                        germinations =
                            listOf(
                                GerminationModel(
                                    recordingDate = localDate, seedsGerminated = 75)))))
    store.update(desired)
    val germinations = germinationsDao.fetchByTestId(GerminationTestId(1))

    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedGerminationTest = germinationTestsDao.fetchOneById(GerminationTestId(1))!!
    assertEquals(7, updatedGerminationTest.totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, updatedGerminationTest.totalSeedsGerminated, "totalSeedsGerminated")

    val updatedAccession = accessionsDao.fetchOneById(AccessionId(1))
    assertEquals(7, updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    assertEquals(7, updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    assertEquals(
        localDate,
        updatedAccession?.latestGerminationRecordingDate,
        "latestGerminationRecordingDate")
  }

  @Test
  fun `valid storage locations are accepted and cause storage condition to be populated`() {
    val locationId = StorageLocationId(12345678)
    val locationName = "Test Location"
    storageLocationsDao.insert(
        StorageLocationsRow(
            conditionId = StorageCondition.Freezer,
            createdBy = user.userId,
            createdTime = clock.instant(),
            facilityId = facilityId,
            id = locationId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = locationName))

    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(storageLocation = locationName))

    assertEquals(
        locationId,
        accessionsDao.fetchOneById(AccessionId(1))?.storageLocationId,
        "Existing storage location ID was used")

    val updated = store.fetchById(initial.id!!)!!
    assertEquals(locationName, updated.storageLocation, "Location name")
    assertEquals(StorageCondition.Freezer, updated.storageCondition, "Storage condition")
  }

  @Test
  fun `unknown storage locations are rejected`() {
    assertThrows<IllegalArgumentException> {
      val initial = store.create(AccessionModel(facilityId = facilityId))
      store.update(initial.copy(storageLocation = "bogus"))
    }
  }

  @Test
  fun `photo filenames are returned`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val photosRow =
        PhotosRow(
            fileName = "photo.jpg",
            createdBy = user.userId,
            createdTime = Instant.now(),
            capturedTime = Instant.now(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
            size = 123,
            storageUrl = URI("file:///photo.jpg"),
        )
    photosDao.insert(photosRow)

    accessionPhotosDao.insert(
        AccessionPhotosRow(accessionId = AccessionId(1), photoId = photosRow.id))

    val fetched = store.fetchById(initial.id!!)

    assertEquals(listOf("photo.jpg"), fetched?.photoFilenames)
  }

  @Test
  fun `update recalculates estimated seed count`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(
        initial.copy(
            processingMethod = ProcessingMethod.Weight,
            subsetCount = 1,
            subsetWeightQuantity = SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Ounces),
            total = SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Pounds)))
    val fetched = store.fetchById(initial.id!!)!!

    assertEquals(160, fetched.estimatedSeedCount, "Estimated seed count is added")

    store.update(fetched.copy(total = null))

    val fetchedAfterClear = store.fetchById(initial.id!!)!!

    assertNull(fetchedAfterClear.estimatedSeedCount, "Estimated seed count is removed")
  }

  @Test
  fun `update recalculates seeds remaining when seed count is filled in`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchById(initial.id!!)

    assertEquals(seeds<SeedQuantityModel>(10), fetched?.remaining)
  }

  @Test
  fun `update recalculates seeds remaining on withdrawal`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchById(initial.id!!)

    assertEquals(seeds(10), fetched?.remaining)
  }

  @Test
  fun `update rejects future storageStartDate`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(storageStartDate = LocalDate.now(clock).plusDays(1)))
    }
  }

  @Test
  fun `absence of deviceInfo causes source to be set to Web`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    assertEquals(AccessionSource.Web, initial.source)
  }

  @Test
  fun `update ignores received and collected date edits for accessions from seed collector app`() {
    val initialCollectedDate = LocalDate.of(2021, 1, 1)
    val initialReceivedDate = LocalDate.of(2021, 1, 2)
    val updatedDate = LocalDate.of(2021, 2, 2)
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                deviceInfo = AppDeviceModel(appName = "collector"),
                collectedDate = initialCollectedDate,
                receivedDate = initialReceivedDate))
    val requested = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(requested)

    val actual = store.fetchById(initial.id!!)

    assertEquals(initial, actual)
  }

  @Test
  fun `update ignores received and collected date edits for accessions from web`() {
    val initialCollectedDate = LocalDate.of(2021, 1, 1)
    val initialReceivedDate = LocalDate.of(2021, 1, 2)
    val updatedDate = LocalDate.of(2021, 2, 2)
    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                collectedDate = initialCollectedDate,
                receivedDate = initialReceivedDate))
    val desired = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(desired)

    val actual = store.fetchById(initial.id!!)

    assertEquals(desired, actual)
  }

  @Test
  fun `update generates withdrawals for new germination tests`() {
    val accession = createAccessionWithGerminationTest()
    val test = accession.germinationTests[0]

    assertEquals(
        listOf(
            WithdrawalModel(
                id = WithdrawalId(1),
                accessionId = accession.id,
                date = test.startDate!!,
                purpose = WithdrawalPurpose.GerminationTesting,
                withdrawn = SeedQuantityModel(BigDecimal(5), SeedQuantityUnits.Seeds),
                germinationTestId = test.id,
                remaining = seeds(5))),
        accession.withdrawals)
  }

  @Test
  fun `update correctly deducts from seed count for germination tests`() {
    val accession = createAccessionWithGerminationTest()

    assertEquals(
        seeds<SeedQuantityModel>(5), accession.remaining, "Seeds remaining after test creation")

    val updated = store.updateAndFetch(accession)
    assertEquals(
        seeds<SeedQuantityModel>(5), updated.remaining, "Seeds remaining after test update")
  }

  @Test
  fun `update modifies withdrawals when their germination tests are modified`() {
    val initial = createAccessionWithGerminationTest()
    val initialTest = initial.germinationTests[0]
    val initialWithdrawal = initial.withdrawals[0]

    val modifiedStartDate = initialTest.startDate!!.plusDays(10)
    val modifiedTest = initialTest.copy(startDate = modifiedStartDate, seedsSown = 6)
    val modifiedWithdrawal =
        initialWithdrawal.copy(
            date = modifiedTest.startDate!!,
            withdrawn = seeds(modifiedTest.seedsSown!!),
            remaining = seeds(4))

    val afterTestModified =
        store.updateAndFetch(initial.copy(germinationTests = listOf(modifiedTest)))

    assertEquals(listOf(modifiedWithdrawal), afterTestModified.withdrawals)
  }

  @Test
  fun `update does not modify withdrawals when their germination tests are not modified`() {
    val initial = createAccessionWithGerminationTest()
    val updated = store.updateAndFetch(initial.copy(receivedDate = LocalDate.now()))

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update removes withdrawals when germination tests are removed`() {
    val initial = createAccessionWithGerminationTest()
    val updated = store.updateAndFetch(initial.copy(germinationTests = emptyList()))

    assertEquals(emptyList<GerminationTestModel>(), updated.withdrawals)
  }

  @Test
  fun `update ignores germination test withdrawals in accession object`() {
    val initial = createAccessionWithGerminationTest()
    val initialWithdrawal = initial.withdrawals[0]

    val modifiedInitialWithdrawal =
        initialWithdrawal.copy(date = initialWithdrawal.date.plusDays(1), withdrawn = seeds(100))
    val newWithdrawal =
        WithdrawalModel(
            date = LocalDate.now(),
            purpose = WithdrawalPurpose.GerminationTesting,
            withdrawn = seeds(1),
            germinationTestId = initialWithdrawal.germinationTestId)

    val updated =
        store.updateAndFetch(
            initial.copy(withdrawals = listOf(modifiedInitialWithdrawal, newWithdrawal)))

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `state history row is inserted at creation time`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession created",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `checkIn transitions state to Pending`() {
    every { clock.instant() } returns Instant.EPOCH.plusMillis(600)

    val initial = store.create(AccessionModel(facilityId = facilityId))
    val updated = store.checkIn(initial.id!!)

    assertEquals(AccessionState.Pending, updated.state)
    assertEquals(
        Instant.EPOCH,
        updated.checkedInTime,
        "Checked-in time should be truncated to 1-second accuracy")

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Pending))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.Pending,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Accession has been checked in",
                updatedTime = clock.instant())),
        historyRecords)

    assertEquals(store.fetchById(initial.id!!), updated, "Return value should match database")
  }

  @Test
  fun `checkIn does not modify accession that is already checked in`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.checkIn(initial.id!!)

    every { clock.instant() } returns Instant.EPOCH.plusSeconds(30)
    val updated = store.checkIn(initial.id!!)

    assertEquals(Instant.EPOCH, updated.checkedInTime, "Checked-in time")
  }

  @Test
  fun `checkedInTime in model is ignored by update`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    store.update(initial.copy(checkedInTime = Instant.EPOCH, primaryCollector = "test"))
    val updated = store.fetchById(initial.id!!)!!

    assertEquals(AccessionState.AwaitingCheckIn, updated.state, "State")
    assertNull(updated.checkedInTime, "Checked-in time")
  }

  @Test
  fun `state transitions to Processing when seed count entered`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(100)))
    val fetched = store.fetchById(initial.id!!)

    assertEquals(AccessionState.Processing, fetched?.state)
    assertEquals(LocalDate.now(clock), fetched?.processingStartDate)

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Processing))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = AccessionId(1),
                newStateId = AccessionState.Processing,
                oldStateId = AccessionState.AwaitingCheckIn,
                reason = "Seed count/weight has been entered",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `dryRun does not persist changes`() {
    val initial = store.create(AccessionModel(species = "Initial Species", facilityId = facilityId))
    store.dryRun(initial.copy(species = "Modified Species"))
    val fetched = store.fetchById(initial.id!!)

    assertEquals(initial.species, fetched?.species)
  }

  @Test
  fun `fetchTimedStateTransitionCandidates matches correct dates based on state`() {
    val today = LocalDate.now(clock)
    val yesterday = today.minusDays(1)
    val tomorrow = today.plusDays(1)
    val twoWeeksAgo = today.minusDays(14)

    val shouldMatch =
        listOf(
            AccessionsRow(
                number = "ProcessingTimePassed",
                stateId = AccessionState.Processing,
                processingStartDate = twoWeeksAgo),
            AccessionsRow(
                number = "ProcessingToDrying",
                stateId = AccessionState.Processing,
                dryingStartDate = today),
            AccessionsRow(
                number = "ProcessedToDrying",
                stateId = AccessionState.Processed,
                dryingStartDate = today),
            AccessionsRow(
                number = "DryingToDried", stateId = AccessionState.Drying, dryingEndDate = today),
            AccessionsRow(
                number = "DryingToStorage",
                stateId = AccessionState.Drying,
                storageStartDate = today),
            AccessionsRow(
                number = "DriedToStorage",
                stateId = AccessionState.Dried,
                storageStartDate = yesterday),
        )

    val shouldNotMatch =
        listOf(
            AccessionsRow(
                number = "NoSeedCountYet",
                stateId = AccessionState.Pending,
                processingStartDate = twoWeeksAgo),
            AccessionsRow(
                number = "ProcessingTimeNotUpYet",
                stateId = AccessionState.Processing,
                processingStartDate = yesterday),
            AccessionsRow(
                number = "ProcessedToStorage",
                stateId = AccessionState.Processed,
                storageStartDate = today),
            AccessionsRow(
                number = "DriedToStorageTomorrow",
                stateId = AccessionState.Dried,
                storageStartDate = tomorrow),
        )

    (shouldMatch + shouldNotMatch).forEach { accession ->
      accessionsDao.insert(
          accession.copy(
              createdBy = user.userId,
              createdTime = clock.instant(),
              facilityId = facilityId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant()))
    }

    val expected = shouldMatch.map { it.number!! }.toSortedSet()
    val actual =
        store.fetchTimedStateTransitionCandidates().map { it.accessionNumber!! }.toSortedSet()

    assertEquals(expected, actual)
  }

  /**
   * Tests the query that generates the summary page's statistics.
   *
   * Use a test data set of accession state changes on either side of the time boundaries we're
   * going to be scanning. The test will look for accessions in the Processing state. In the
   * diagram, `[` is when the state enters Processing and `]` is when it enters Processed.
   *
   * ```
   *    1   2   3   4   5   Time
   *        |-------|       Search window for both time boundaries
   *        |------------   Search window for startingAt test
   *    ------------|       Search window for sinceBefore test
   *
   * 1  [---------------]   Not counted: no longer Processing
   * 2  [----------------   Counted (sinceBefore, unbounded): entered before window
   * 3      [------------   Counted (all): entered during window, never exited
   * 4      [-----------]   Not counted: no longer Processing
   * 5              [----   Counted (all): entered during window (inclusive), never exited
   * 6                  [   Counted (sinceAfter, unbounded): entered after window
   * 7                  [   Counted (sinceAfter, unbounded): entered after window
   * ```
   */
  @Test
  fun `countInState correctly examines state changes`() {
    // Insert dummy accession rows so we can use the accession IDs
    repeat(7) { store.create(AccessionModel(facilityId = facilityId)) }

    listOf(1 to 6, 1 to null, 2 to null, 2 to 5, 4 to null, 6 to null, 6 to null).forEachIndexed {
        index,
        (processingStartTime, processedStartTime) ->
      val accessionId = AccessionId((index + 1).toLong())
      val currentState =
          if (processedStartTime == null) AccessionState.Processing else AccessionState.Processed

      dslContext
          .insertInto(ACCESSION_STATE_HISTORY)
          .set(
              AccessionStateHistoryRecord(
                  accessionId = accessionId,
                  updatedTime = Instant.ofEpochMilli(processingStartTime.toLong()),
                  oldStateId = AccessionState.Pending,
                  newStateId = AccessionState.Processing,
                  reason = "test"))
          .execute()

      if (processedStartTime != null) {
        dslContext
            .insertInto(ACCESSION_STATE_HISTORY)
            .set(
                AccessionStateHistoryRecord(
                    accessionId = accessionId,
                    updatedTime = Instant.ofEpochMilli(processedStartTime.toLong()),
                    oldStateId = AccessionState.Processing,
                    newStateId = AccessionState.Processed,
                    reason = "test"))
            .execute()
      }

      dslContext
          .update(ACCESSIONS)
          .set(ACCESSIONS.STATE_ID, currentState)
          .where(ACCESSIONS.ID.eq(accessionId))
          .execute()
    }

    assertEquals(
        2,
        store.countInState(
            facilityId,
            state = AccessionState.Processing,
            sinceAfter = Instant.ofEpochMilli(2),
            sinceBefore = Instant.ofEpochMilli(4)),
        "Search with both time bounds")

    assertEquals(
        4,
        store.countInState(
            facilityId, state = AccessionState.Processing, sinceAfter = Instant.ofEpochMilli(2)),
        "Search with startingAt")

    assertEquals(
        3,
        store.countInState(
            facilityId, state = AccessionState.Processing, sinceBefore = Instant.ofEpochMilli(4)),
        "Search with sinceBefore")

    assertEquals(
        5, store.countInState(facilityId, AccessionState.Processing), "Search without time bounds")
  }

  /**
   * Test data:
   *
   * ```
   *     1  2  3  4  5
   * 1   [--]
   * 2   [-----]
   * 3   [--------]
   * 4   [-----------]
   * 5   [------------
   * 6         [------
   * ```
   */
  @Test
  fun `countActive correctly examines history`() {
    listOf(1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to null, 3 to null).forEach {
        (createdTime, withdrawnTime) ->
      every { clock.instant() } returns Instant.ofEpochMilli(createdTime.toLong())
      val accession = store.create(AccessionModel(facilityId = facilityId))

      if (withdrawnTime != null) {
        dslContext
            .insertInto(ACCESSION_STATE_HISTORY)
            .set(
                AccessionStateHistoryRecord(
                    accessionId = accession.id,
                    updatedTime = Instant.ofEpochMilli(withdrawnTime.toLong()),
                    oldStateId = AccessionState.InStorage,
                    newStateId = AccessionState.Withdrawn,
                    reason = "test"))
            .execute()

        dslContext
            .update(ACCESSIONS)
            .set(ACCESSIONS.STATE_ID, AccessionState.Withdrawn)
            .where(ACCESSIONS.ID.eq(accession.id))
            .execute()
      }
    }

    val expectedCounts = listOf(0, 5, 4, 4, 3, 2, 2)
    expectedCounts.forEachIndexed { asOf, expectedCount ->
      assertEquals(
          expectedCount,
          store.countActive(facilityId, Instant.ofEpochMilli(asOf.toLong())),
          "Count as of time $asOf")
    }
  }

  @Test
  fun `update rejects weight-based withdrawals for count-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Count,
              total = seeds(50),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.now(clock),
                          withdrawn = grams(1),
                          purpose = WithdrawalPurpose.Other))))
    }
  }

  @Test
  fun `update rejects withdrawals without remaining quantity for weight-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(100),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.now(clock), purpose = WithdrawalPurpose.Other))))
    }
  }

  @Test
  fun `update computes remaining quantity on withdrawals for count-based accessions`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100),
          withdrawals =
              listOf(
                  WithdrawalPayload(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      withdrawnQuantity = seeds(10))))
    }

    assertEquals(
        seeds<SeedQuantityModel>(90),
        initial.withdrawals[0].remaining,
        "Quantity remaining on withdrawal")
    assertEquals(seeds<SeedQuantityModel>(90), initial.remaining, "Quantity remaining on accession")
  }

  @Test
  fun `update requires subset weight to use weight units`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(10),
              subsetWeightQuantity = seeds(5)))
    }
  }

  @Test
  fun `update rejects withdrawals if accession total size not set`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Count,
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          date = LocalDate.EPOCH,
                          purpose = WithdrawalPurpose.Other,
                          withdrawn = seeds(1)))))
    }
  }

  @Test
  fun `update rejects germination tests without remaining quantity for weight-based accessions`() {
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<IllegalArgumentException> {
      store.update(
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(100),
              germinationTests = listOf(GerminationTestModel(testType = GerminationTestType.Lab))))
    }
  }

  @Test
  fun `update computes remaining quantity on germination tests for count-based accessions`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(100),
          germinationTests =
              listOf(GerminationTestPayload(testType = GerminationTestType.Lab, seedsSown = 10)))
    }

    assertEquals(
        seeds<SeedQuantityModel>(90),
        initial.germinationTests[0].remaining,
        "Quantity remaining on test")
    assertEquals(seeds<SeedQuantityModel>(90), initial.remaining, "Quantity remaining on accession")
  }

  @Test
  fun `update allows processing method to change if no tests or withdrawals exist`() {
    val initial = createAndUpdate { it.copy(processingMethod = ProcessingMethod.Weight) }

    val withCountMethod =
        store.updateAndFetch(
            initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(1)))
    assertEquals(seeds<SeedQuantityModel>(1), withCountMethod.total)

    val withWeightMethod =
        store.updateAndFetch(
            withCountMethod.copy(processingMethod = ProcessingMethod.Weight, total = grams(2)))
    assertEquals(grams<SeedQuantityModel>(2), withWeightMethod.total)
  }

  @Test
  fun `update does not allow processing method to change if germination test exists`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(10),
          germinationTests = listOf(GerminationTestPayload(testType = GerminationTestType.Lab)))
    }

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(processingMethod = ProcessingMethod.Weight, total = grams(5)))
    }
  }

  @Test
  fun `update does not allow processing method to change if withdrawal exists`() {
    val initial = createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Weight,
          initialQuantity = grams(10),
          withdrawals =
              listOf(
                  WithdrawalPayload(
                      date = LocalDate.EPOCH,
                      purpose = WithdrawalPurpose.Other,
                      remainingQuantity = grams(5))))
    }

    assertThrows<IllegalArgumentException> {
      store.update(initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    }
  }

  @Test
  fun `create writes all fields to database`() {
    val today = LocalDate.now(clock)
    val accession =
        CreateAccessionRequestPayload(
            bagNumbers = setOf("abc"),
            collectedDate = today,
            deviceInfo =
                DeviceInfoPayload(
                    appBuild = "build",
                    appName = "name",
                    brand = "brand",
                    model = "model",
                    name = "name",
                    osType = "osType",
                    osVersion = "osVersion",
                    uniqueId = "uniqueId"),
            endangered = SpeciesEndangeredType.Unsure,
            environmentalNotes = "envNotes",
            facilityId = facilityId,
            family = "family",
            fieldNotes = "fieldNotes",
            founderId = "founderId",
            geolocations =
                setOf(
                    Geolocation(
                        latitude = BigDecimal.ONE,
                        longitude = BigDecimal.TEN,
                        accuracy = BigDecimal(3))),
            germinationTestTypes = setOf(GerminationTestType.Lab),
            landowner = "landowner",
            numberOfTrees = 10,
            primaryCollector = "primaryCollector",
            rare = RareType.Yes,
            receivedDate = today,
            secondaryCollectors = setOf("second1", "second2"),
            siteLocation = "siteLocation",
            sourcePlantOrigin = SourcePlantOrigin.Wild,
            species = "species",
        )

    val createPayloadProperties = CreateAccessionRequestPayload::class.declaredMemberProperties
    val accessionModelProperties = AccessionModel::class.declaredMemberProperties
    val propertyNames = createPayloadProperties.map { it.name }.toSet()

    createPayloadProperties.forEach { prop ->
      assertNotNull(prop.get(accession), "Field ${prop.name} is null in example object")
    }

    val stored = store.create(accession.toModel())

    accessionModelProperties
        .filter { it.name in propertyNames }
        .forEach { prop ->
          assertNotNull(prop.get(stored), "Field ${prop.name} is null in stored object")
        }
  }

  @Test
  fun `update writes all fields to database`() {
    val storageLocationName = "Test Location"
    val today = LocalDate.now(clock)
    val update =
        UpdateAccessionRequestPayload(
            bagNumbers = setOf("abc"),
            collectedDate = today,
            cutTestSeedsCompromised = 20,
            cutTestSeedsEmpty = 21,
            cutTestSeedsFilled = 22,
            dryingEndDate = today,
            dryingMoveDate = today,
            dryingStartDate = today,
            endangered = SpeciesEndangeredType.Unsure,
            environmentalNotes = "envNotes",
            family = "family",
            fieldNotes = "fieldNotes",
            founderId = "founderId",
            geolocations =
                setOf(
                    Geolocation(
                        latitude = BigDecimal.ONE,
                        longitude = BigDecimal.TEN,
                        accuracy = BigDecimal(3))),
            germinationTestTypes = setOf(GerminationTestType.Lab),
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        remainingQuantity = grams(10),
                        testType = GerminationTestType.Lab,
                        startDate = today)),
            initialQuantity = kilograms(432),
            landowner = "landowner",
            numberOfTrees = 10,
            nurseryStartDate = today,
            primaryCollector = "primaryCollector",
            processingMethod = ProcessingMethod.Weight,
            processingNotes = "processingNotes",
            processingStaffResponsible = "procStaff",
            processingStartDate = today,
            rare = RareType.Yes,
            receivedDate = today,
            secondaryCollectors = setOf("second1", "second2"),
            siteLocation = "siteLocation",
            sourcePlantOrigin = SourcePlantOrigin.Wild,
            species = "species",
            storageLocation = storageLocationName,
            storageNotes = "storageNotes",
            storagePackets = 5,
            storageStaffResponsible = "storageStaff",
            storageStartDate = today,
            subsetCount = 32,
            subsetWeight = grams(33),
            targetStorageCondition = StorageCondition.Freezer,
            withdrawals =
                listOf(
                    WithdrawalPayload(
                        date = today,
                        purpose = WithdrawalPurpose.Other,
                        destination = "destination",
                        notes = "notes",
                        remainingQuantity = grams(42),
                        staffResponsible = "staff",
                        withdrawnQuantity = seeds(41))),
        )

    val updatePayloadProperties = UpdateAccessionRequestPayload::class.declaredMemberProperties
    val accessionModelProperties = AccessionModel::class.declaredMemberProperties
    val propertyNames = updatePayloadProperties.map { it.name }.toSet()

    updatePayloadProperties.forEach { prop ->
      if (prop.visibility == KVisibility.PUBLIC) {
        try {
          assertNotNull(prop.get(update), "Field ${prop.name} is null in example object")
        } catch (e: Exception) {
          fail("Unable to read ${prop.name}", e)
        }
      }
    }

    storageLocationsDao.insert(
        StorageLocationsRow(
            conditionId = StorageCondition.Freezer,
            createdBy = user.userId,
            createdTime = clock.instant(),
            facilityId = facilityId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            name = storageLocationName))

    val initial = store.create(AccessionModel(facilityId = facilityId))
    val stored = store.updateAndFetch(update.toModel(initial.id!!))

    accessionModelProperties
        .filter { it.name in propertyNames }
        .forEach { prop ->
          assertNotNull(prop.get(stored), "Field ${prop.name} is null in stored object")
        }
  }

  @Test
  fun `create does not write to database if user does not have permission`() {
    every { user.canCreateAccession(facilityId) } returns false
    every { user.canReadFacility(facilityId) } returns true

    assertThrows<AccessDeniedException> { store.create(AccessionModel(facilityId = facilityId)) }
  }

  @Test
  fun `fetchByNumber does not return accession if user does not have permission`() {
    every { user.canReadAccession(any()) } returns false

    val initial = store.create(AccessionModel(facilityId = facilityId))
    assertNotNull(initial, "Should have created accession successfully")
    assertNull(store.fetchById(initial.id!!), "Should not have been able to read accession")
  }

  @Test
  fun `update does not write to database if user does not have permission`() {
    every { user.canUpdateAccession(any()) } returns false
    val initial = store.create(AccessionModel(facilityId = facilityId))

    assertThrows<AccessDeniedException> { store.update(initial.copy(numberOfTrees = 1)) }

    val afterUpdate = store.fetchById(initial.id!!)
    assertNotNull(afterUpdate, "Should be able to read accession after updating")
    assertNull(afterUpdate?.numberOfTrees, "Update should not have been written")
  }

  @Nested
  inner class MultipleFacilities {
    private val otherFacilityId = FacilityId(500)

    @BeforeEach
    fun createOtherFacility() {
      insertFacility(otherFacilityId, 10)
    }

    @Test
    fun `can create accessions with different facility IDs`() {
      val accessionInMainFacility = store.create(AccessionModel(facilityId = facilityId))
      val accessionInOtherFacility = store.create(AccessionModel(facilityId = otherFacilityId))

      assertNotEquals(
          accessionInMainFacility.accessionNumber,
          accessionInOtherFacility.accessionNumber,
          "Accession number")

      assertEquals(accessionInMainFacility.facilityId, facilityId, "Accession in main facility")
      assertEquals(
          accessionInOtherFacility.facilityId, otherFacilityId, "Accession in other facility")
    }

    @Test
    fun `countActive only counts accessions from the requested facility`() {
      store.create(AccessionModel(facilityId = facilityId))
      assertEquals(1, store.countActive(facilityId, clock.instant()))
      assertEquals(0, store.countActive(otherFacilityId, clock.instant()))
    }

    @Test
    fun `countInState without timing only counts accessions from requested facility`() {
      val initial = store.create(AccessionModel(facilityId = facilityId))
      assertEquals(1, store.countInState(facilityId, initial.state!!))
      assertEquals(0, store.countInState(otherFacilityId, initial.state!!))
    }

    @Test
    fun `countInState with timing only counts accessions from requested facility`() {
      val initial = store.create(AccessionModel(facilityId = facilityId))
      assertEquals(
          1, store.countInState(facilityId, initial.state!!, Instant.EPOCH, clock.instant()))
      assertEquals(
          0, store.countInState(otherFacilityId, initial.state!!, Instant.EPOCH, clock.instant()))
    }
  }

  private fun getSecondaryCollectors(accessionId: AccessionId?): Set<String> {
    with(ACCESSION_SECONDARY_COLLECTORS) {
      return dslContext
          .select(NAME)
          .from(ACCESSION_SECONDARY_COLLECTORS)
          .where(ACCESSION_ID.eq(accessionId))
          .fetch(NAME)
          .filterNotNull()
          .toSet()
    }
  }

  private fun createAndUpdate(
      edit: (UpdateAccessionRequestPayload) -> UpdateAccessionRequestPayload
  ): AccessionModel {
    val initial = store.create(AccessionModel(facilityId = facilityId))
    val edited = edit(initial.toUpdatePayload())
    return store.updateAndFetch(edited.toModel(initial.id!!))
  }

  private fun createAccessionWithGerminationTest(): AccessionModel {
    return createAndUpdate {
      it.copy(
          processingMethod = ProcessingMethod.Count,
          initialQuantity = seeds(10),
          germinationTests =
              listOf(
                  GerminationTestPayload(
                      testType = GerminationTestType.Lab,
                      startDate = LocalDate.of(2021, 4, 1),
                      seedsSown = 5)))
    }
  }

  private fun AccessionModel.toUpdatePayload(): UpdateAccessionRequestPayload {
    return UpdateAccessionRequestPayload(
        bagNumbers = bagNumbers,
        collectedDate = collectedDate,
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        dryingEndDate = dryingEndDate,
        dryingMoveDate = dryingMoveDate,
        dryingStartDate = dryingStartDate,
        endangered = endangered,
        environmentalNotes = environmentalNotes,
        family = family,
        fieldNotes = fieldNotes,
        founderId = founderId,
        geolocations = geolocations,
        germinationTests = germinationTests.map { GerminationTestPayload(it) },
        germinationTestTypes = germinationTestTypes,
        initialQuantity = total?.let { SeedQuantityPayload(it) },
        landowner = landowner,
        numberOfTrees = numberOfTrees,
        nurseryStartDate = nurseryStartDate,
        primaryCollector = primaryCollector,
        processingMethod = processingMethod,
        processingNotes = processingNotes,
        processingStaffResponsible = processingStaffResponsible,
        processingStartDate = processingStartDate,
        rare = rare,
        receivedDate = receivedDate,
        secondaryCollectors = secondaryCollectors,
        siteLocation = siteLocation,
        sourcePlantOrigin = sourcePlantOrigin,
        species = species,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageNotes = storageNotes,
        storageStaffResponsible = storageStaffResponsible,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeight = subsetWeightQuantity?.let { SeedQuantityPayload(it) },
        targetStorageCondition = targetStorageCondition,
        withdrawals = withdrawals.map { WithdrawalPayload(it) },
    )
  }
}
