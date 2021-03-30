package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.AccessionPayload
import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.DeviceInfoPayload
import com.terraformation.seedbank.api.seedbank.GerminationPayload
import com.terraformation.seedbank.api.seedbank.GerminationTestPayload
import com.terraformation.seedbank.api.seedbank.WithdrawalPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.sequences.ACCESSION_NUMBER_SEQ
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.daos.AccessionPhotoDao
import com.terraformation.seedbank.db.tables.daos.AppDeviceDao
import com.terraformation.seedbank.db.tables.daos.BagDao
import com.terraformation.seedbank.db.tables.daos.GeolocationDao
import com.terraformation.seedbank.db.tables.daos.GerminationDao
import com.terraformation.seedbank.db.tables.daos.GerminationTestDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import com.terraformation.seedbank.db.tables.pojos.AccessionPhoto
import com.terraformation.seedbank.db.tables.pojos.AccessionStateHistory
import com.terraformation.seedbank.db.tables.pojos.Bag
import com.terraformation.seedbank.db.tables.pojos.GerminationTest
import com.terraformation.seedbank.db.tables.pojos.StorageLocation
import com.terraformation.seedbank.db.tables.records.AccessionStateHistoryRecord
import com.terraformation.seedbank.db.tables.references.ACCESSION
import com.terraformation.seedbank.db.tables.references.ACCESSION_GERMINATION_TEST_TYPE
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.seedbank.db.tables.references.NOTIFICATION
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.Geolocation
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType

internal class AccessionStoreTest : DatabaseTest() {
  @Autowired private lateinit var config: TerrawareServerConfig

  override val sequencesToReset
    get() =
        listOf(
            "accession_id_seq",
            "accession_number_seq",
            "app_device_id_seq",
            "bag_id_seq",
            "collection_event_id_seq",
            "germination_test_id_seq",
            "species_id_seq",
            "species_family_id_seq",
        )

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
  private lateinit var accessionDao: AccessionDao
  private lateinit var accessionPhotoDao: AccessionPhotoDao
  private lateinit var appDeviceDao: AppDeviceDao
  private lateinit var bagDao: BagDao
  private lateinit var geolocationDao: GeolocationDao
  private lateinit var germinationDao: GerminationDao
  private lateinit var germinationTestDao: GerminationTestDao
  private lateinit var storageLocationDao: StorageLocationDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    accessionDao = AccessionDao(jooqConfig)
    accessionPhotoDao = AccessionPhotoDao(jooqConfig)
    appDeviceDao = AppDeviceDao(jooqConfig)
    bagDao = BagDao(jooqConfig)
    geolocationDao = GeolocationDao(jooqConfig)
    germinationDao = GerminationDao(jooqConfig)
    germinationTestDao = GerminationTestDao(jooqConfig)
    storageLocationDao = StorageLocationDao(jooqConfig)

    val support = StoreSupport(config, dslContext)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    store =
        AccessionStore(
            dslContext,
            config,
            accessionPhotoDao,
            AppDeviceStore(dslContext, clock),
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            GerminationStore(dslContext),
            SpeciesFetcher(clock, support),
            WithdrawalStore(dslContext, clock),
            clock,
            support)

    insertSiteData()
  }

  @Test
  fun `create of empty accession populates default values`() {
    store.create(CreateAccessionRequestPayload())

    assertEquals(
        Accession(
            id = 1,
            siteModuleId = config.siteModuleId,
            createdTime = clock.instant(),
            number = accessionNumbers[0],
            stateId = AccessionState.Pending),
        accessionDao.fetchOneById(1))
  }

  @Test
  fun `create deals with collisions in accession numbers`() {
    store.create(CreateAccessionRequestPayload())
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()
    store.create(CreateAccessionRequestPayload())

    assertNotNull(accessionDao.fetchOneByNumber(accessionNumbers[1]))
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    repeat(10) { store.create(CreateAccessionRequestPayload()) }

    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()

    assertThrows(DuplicateKeyException::class.java) {
      store.create(CreateAccessionRequestPayload())
    }
  }

  @Test
  fun `create adds digit to accession number suffix if it exceeds 3 digits`() {
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000001000).execute()
    val inserted = store.create(CreateAccessionRequestPayload())
    assertEquals(inserted.accessionNumber, "197001011000")
  }

  @Test
  fun `existing rows are used for free-text fields that live in reference tables`() {
    val payload =
        CreateAccessionRequestPayload(
            species = "test species",
            family = "test family",
            primaryCollector = "primary collector",
            secondaryCollectors = setOf("secondary 1", "secondary 2"))

    // First time inserts the reference table rows
    store.create(payload)
    // Second time should reuse them
    store.create(payload)

    val initialRow = accessionDao.fetchOneByNumber(accessionNumbers[0])!!
    val secondRow = accessionDao.fetchOneByNumber(accessionNumbers[1])!!

    assertNotEquals(initialRow.number, secondRow.number, "Accession numbers")
    assertEquals(initialRow.speciesId, secondRow.speciesId, "Species")
    assertEquals(initialRow.speciesFamilyId, secondRow.speciesFamilyId, "Family")
    assertEquals(initialRow.primaryCollectorId, secondRow.primaryCollectorId, "Primary collector")

    assertEquals(2, getSecondaryCollectors(1).size, "Number of secondary collectors")

    assertEquals(getSecondaryCollectors(1), getSecondaryCollectors(2), "Secondary collectors")
  }

  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = CreateAccessionRequestPayload(bagNumbers = setOf("bag 1", "bag 2"))
    store.create(payload)
    store.create(payload)

    val initialBags = bagDao.fetchByAccessionId(1).toSet()
    val secondBags = bagDao.fetchByAccessionId(2).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial = store.create(CreateAccessionRequestPayload(bagNumbers = setOf("bag 1", "bag 2")))
    val initialBags = bagDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API, so don't assume bag ID 1 is "bag 1".

    assertEquals(setOf(1L, 2L), initialBags.map { it.id }.toSet(), "Initial bag IDs")
    assertEquals(
        setOf("bag 1", "bag 2"), initialBags.map { it.bagNumber }.toSet(), "Initial bag numbers")

    val desired = AccessionPayload(initial).copy(bagNumbers = setOf("bag 2", "bag 3"))

    assertTrue(store.update(initial.accessionNumber, desired), "Update succeeded")

    val updatedBags = bagDao.fetchByAccessionId(1)

    assertTrue(Bag(3, 1, "bag 3") in updatedBags, "New bag inserted")
    assertTrue(updatedBags.none { it.bagNumber == "bag 1" }, "Missing bag deleted")
    assertEquals(
        initialBags.filter { it.bagNumber == "bag 2" },
        updatedBags.filter { it.bagNumber == "bag 2" },
        "Existing bag is not replaced")
  }

  @Test
  fun `device info is inserted at creation`() {
    val payload = CreateAccessionRequestPayload(deviceInfo = DeviceInfoPayload(model = "model"))
    store.create(payload)

    val appDevice = appDeviceDao.fetchOneById(1)
    assertNotNull(appDevice, "Device row should have been inserted")
    assertNull(appDevice?.appName, "App name should be null")
    assertEquals(appDevice?.model, "model")
  }

  @Test
  fun `device info is retrieved`() {
    val payload = CreateAccessionRequestPayload(deviceInfo = DeviceInfoPayload(model = "model"))
    val initial = store.create(payload)

    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertNotNull(fetched?.deviceInfo)
    assertEquals("model", fetched?.deviceInfo?.model)
    assertEquals(AccessionSource.SeedCollectorApp, initial.source)
  }

  @Test
  fun `geolocations are inserted and deleted as needed`() {
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(3), BigDecimal(4)))))
    val initialGeos = geolocationDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API.

    assertEquals(setOf(1L, 2L), initialGeos.map { it.id }.toSet(), "Initial location IDs")
    assertEquals(
        100.0, initialGeos.mapNotNull { it.gpsAccuracy }.first(), 0.1, "Accuracy is recorded")

    val desired =
        AccessionPayload(initial)
            .copy(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(5), BigDecimal(6))))

    assertTrue(store.update(initial.accessionNumber, desired), "Update succeeded")

    val updatedGeos = geolocationDao.fetchByAccessionId(1)

    assertTrue(
        updatedGeos.any { it.id == 3L && it.latitude?.toInt() == 5 && it.longitude?.toInt() == 6 },
        "New geo inserted")
    assertTrue(updatedGeos.none { it.latitude == BigDecimal(3) }, "Missing geo deleted")
    assertEquals(
        initialGeos.filter { it.latitude == BigDecimal(1) },
        updatedGeos.filter { it.latitude == BigDecimal(1) },
        "Existing geo retained")
  }

  @Test
  fun `germination tests are inserted at creation time`() {
    store.create(
        CreateAccessionRequestPayload(
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        substrate = GerminationSubstrate.AgarPetriDish),
                    GerminationTestPayload(
                        testType = GerminationTestType.Nursery,
                        substrate = GerminationSubstrate.NurseryMedia))))
    val initialTests = germinationTestDao.fetchByAccessionId(1)

    assertTrue(
        initialTests.any {
          it.testType == GerminationTestType.Lab &&
              it.substrateId == GerminationSubstrate.AgarPetriDish &&
              it.startDate == null
        },
        "Lab test is inserted")
    assertTrue(
        initialTests.any {
          it.testType == GerminationTestType.Nursery &&
              it.substrateId == GerminationSubstrate.NurseryMedia &&
              it.startDate == null
        },
        "Nursery test is inserted")
  }

  @Test
  fun `germination test types are inserted at creation time`() {
    store.create(
        CreateAccessionRequestPayload(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPE)
            .where(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID.eq(1))
            .fetch(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)

    assertEquals(listOf(GerminationTestType.Lab), types)
  }

  @Test
  fun `germination tests are inserted by update`() {
    val initial = store.create(CreateAccessionRequestPayload())
    val startDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val withTest =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            testType = GerminationTestType.Lab, startDate = startDate)))
    store.update(initial.accessionNumber, withTest)

    val updatedTests = germinationTestDao.fetchByAccessionId(1)
    assertEquals(
        listOf(
            GerminationTest(
                id = 1,
                accessionId = 1,
                testType = GerminationTestType.Lab,
                startDate = startDate)),
        updatedTests)

    val updatedAccession = accessionDao.fetchOneById(1)
    assertNull(updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    assertNull(updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    assertNull(updatedAccession?.latestGerminationRecordingDate, "latestGerminationRecordingDate")
  }

  @Test
  fun `germination test types are inserted by update`() {
    val initial =
        store.create(
            CreateAccessionRequestPayload(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTestTypes = setOf(GerminationTestType.Lab, GerminationTestType.Nursery))
    store.update(initial.accessionNumber, desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPE)
            .where(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID.eq(1))
            .fetch(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
    assertEquals(setOf(GerminationTestType.Lab, GerminationTestType.Nursery), types.toSet())
  }

  @Test
  fun `germination test types are deleted by update`() {
    val initial =
        store.create(
            CreateAccessionRequestPayload(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired =
        AccessionPayload(initial).copy(germinationTestTypes = setOf(GerminationTestType.Nursery))
    store.update(initial.accessionNumber, desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPE)
            .where(ACCESSION_GERMINATION_TEST_TYPE.ACCESSION_ID.eq(1))
            .fetch(ACCESSION_GERMINATION_TEST_TYPE.GERMINATION_TEST_TYPE_ID)
    assertEquals(listOf(GerminationTestType.Nursery), types)
  }

  @Test
  fun `existing germination tests are updated`() {
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(GerminationTestPayload(testType = GerminationTestType.Lab))))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            id = initial.germinationTests?.get(0)?.id,
                            testType = GerminationTestType.Lab,
                            seedType = GerminationSeedType.Fresh,
                            treatment = GerminationTreatment.Scarify,
                            substrate = GerminationSubstrate.PaperPetriDish,
                            notes = "notes",
                            seedsSown = 5)))
    store.update(initial.accessionNumber, desired)

    val updatedTests = germinationTestDao.fetchByAccessionId(1)
    assertEquals(
        listOf(
            GerminationTest(
                id = 1,
                accessionId = 1,
                testType = GerminationTestType.Lab,
                seedTypeId = GerminationSeedType.Fresh,
                treatmentId = GerminationTreatment.Scarify,
                substrateId = GerminationSubstrate.PaperPetriDish,
                notes = "notes",
                seedsSown = 5)),
        updatedTests)
  }

  @Test
  fun `cannot update germination test from a different accession`() {
    val other =
        store.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(GerminationTestPayload(testType = GerminationTestType.Nursery))))
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(GerminationTestPayload(testType = GerminationTestType.Lab))))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            id = other.germinationTests?.get(0)?.id,
                            testType = GerminationTestType.Lab,
                            seedType = GerminationSeedType.Fresh,
                            treatment = GerminationTreatment.Scarify,
                            substrate = GerminationSubstrate.PaperPetriDish,
                            notes = "notes",
                            seedsSown = 5)))

    assertThrows(IllegalArgumentException::class.java) {
      store.update(initial.accessionNumber, desired)
    }
  }

  @Test
  fun `germinations are inserted by create`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    store.create(
        CreateAccessionRequestPayload(
            germinationTests =
                listOf(
                    GerminationTestPayload(
                        testType = GerminationTestType.Lab,
                        germinations =
                            listOf(
                                GerminationPayload(
                                    recordingDate = localDate, seedsGerminated = 123),
                                GerminationPayload(
                                    recordingDate = localDate.plusDays(1),
                                    seedsGerminated = 456))))))
    val germinations = germinationDao.fetchByTestId(1)

    assertEquals(2, germinations.size, "Number of germinations inserted")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 123 },
        "First germination inserted")
    assertTrue(
        germinations.any { it.recordingDate == localDate.plusDays(1) && it.seedsGerminated == 456 },
        "First germination inserted")
  }

  @Test
  fun `germinations are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(GerminationTestPayload(testType = GerminationTestType.Lab))))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            id = initial.germinationTests?.get(0)?.id,
                            testType = GerminationTestType.Lab,
                            seedsSown = 200,
                            germinations =
                                listOf(
                                    GerminationPayload(
                                        recordingDate = localDate, seedsGerminated = 75)))))
    store.update(initial.accessionNumber, desired)

    val germinationTests = germinationTestDao.fetchByAccessionId(1)
    assertEquals(1, germinationTests.size, "Number of germination tests after update")
    assertEquals(37, germinationTests[0].totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, germinationTests[0].totalSeedsGerminated, "totalSeedsGerminated")

    val germinations = germinationDao.fetchByTestId(1)
    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedAccession = accessionDao.fetchOneById(1)
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
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            testType = GerminationTestType.Lab,
                            germinations =
                                listOf(
                                    GerminationPayload(
                                        recordingDate = localDate, seedsGerminated = 75),
                                    GerminationPayload(
                                        recordingDate = localDate.plusDays(1),
                                        seedsGerminated = 456))))))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            id = initial.germinationTests?.get(0)?.id,
                            testType = GerminationTestType.Lab,
                            seedsSown = 100,
                            germinations =
                                listOf(
                                    GerminationPayload(
                                        recordingDate = localDate, seedsGerminated = 75)))))
    store.update(initial.accessionNumber, desired)
    val germinations = germinationDao.fetchByTestId(1)

    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedAccession = accessionDao.fetchOneById(1)
    assertEquals(75, updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    assertEquals(75, updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    assertEquals(
        localDate,
        updatedAccession?.latestGerminationRecordingDate,
        "latestGerminationRecordingDate")
  }

  @Test
  fun `valid storage locations are accepted and cause storage condition to be populated`() {
    val locationId = 12345678L
    val locationName = "Test Location"
    storageLocationDao.insert(
        StorageLocation(
            id = locationId,
            siteModuleId = config.siteModuleId,
            name = locationName,
            conditionId = StorageCondition.Freezer))

    val initial = store.create(CreateAccessionRequestPayload())
    store.update(
        initial.accessionNumber, AccessionPayload(initial).copy(storageLocation = locationName))

    assertEquals(
        locationId,
        accessionDao.fetchOneById(1)?.storageLocationId,
        "Existing storage location ID was used")

    val updated = store.fetchByNumber(initial.accessionNumber)!!
    assertEquals(locationName, updated.storageLocation, "Location name")
    assertEquals(StorageCondition.Freezer, updated.storageCondition, "Storage condition")
  }

  @Test
  fun `unknown storage locations are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      val initial = store.create(CreateAccessionRequestPayload())
      store.update(
          initial.accessionNumber, AccessionPayload(initial).copy(storageLocation = "bogus"))
    }
  }

  @Test
  fun `photo filenames are returned`() {
    val initial = store.create(CreateAccessionRequestPayload())
    accessionPhotoDao.insert(
        AccessionPhoto(
            accessionId = 1,
            filename = "photo.jpg",
            uploadedTime = Instant.now(),
            capturedTime = Instant.now(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            size = 123))

    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertEquals(listOf("photo.jpg"), fetched?.photoFilenames)
  }

  @Test
  fun `update recalculates estimated seed count`() {
    val initial = store.create(CreateAccessionRequestPayload())
    store.update(
        initial.accessionNumber,
        initial.copy(
            subsetCount = 1,
            subsetWeightGrams = BigDecimal.ONE,
            totalWeightGrams = BigDecimal.TEN,
        ))
    val fetched = store.fetchByNumber(initial.accessionNumber)!!

    assertEquals(10, fetched.estimatedSeedCount, "Estimated seed count is added")
    assertEquals(10, fetched.effectiveSeedCount, "Effective seed count is added")

    store.update(initial.accessionNumber, fetched.copy(totalWeightGrams = null))

    val fetchedAfterClear = store.fetchByNumber(initial.accessionNumber)!!

    assertNull(fetchedAfterClear.estimatedSeedCount, "Estimated seed count is removed")
    assertNull(fetchedAfterClear.effectiveSeedCount, "Effective seed count is removed")
  }

  @Test
  fun `update recalculates seeds remaining when seed count is filled in`() {
    val initial = store.create(CreateAccessionRequestPayload())
    store.update(initial.accessionNumber, initial.copy(seedsCounted = 10))
    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertEquals(10, fetched?.seedsRemaining)
  }

  @Test
  fun `update recalculates seeds remaining on withdrawal`() {
    val initial = store.create(CreateAccessionRequestPayload())
    store.update(initial.accessionNumber, initial.copy(seedsCounted = 10))
    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertEquals(10, fetched?.seedsRemaining)
  }

  @Test
  fun `update rejects future storageStartDate`() {
    val initial = store.create(CreateAccessionRequestPayload())
    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber,
          initial.copy(storageStartDate = LocalDate.now(clock).plusDays(1)))
    }
  }

  @Test
  fun `absence of deviceInfo causes source to be set to Web`() {
    val initial = store.create(CreateAccessionRequestPayload())
    assertEquals(AccessionSource.Web, initial.source)
  }

  @Test
  fun `update ignores received and collected date edits for accessions from seed collector app`() {
    val initialCollectedDate = LocalDate.of(2021, 1, 1)
    val initialReceivedDate = LocalDate.of(2021, 1, 2)
    val updatedDate = LocalDate.of(2021, 2, 2)
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                deviceInfo = DeviceInfoPayload(appName = "collector"),
                collectedDate = initialCollectedDate,
                receivedDate = initialReceivedDate))
    val requested = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(initial.accessionNumber, requested)

    val actual = store.fetchByNumber(initial.accessionNumber)

    assertEquals(initial, actual)
  }

  @Test
  fun `update ignores received and collected date edits for accessions from web`() {
    val initialCollectedDate = LocalDate.of(2021, 1, 1)
    val initialReceivedDate = LocalDate.of(2021, 1, 2)
    val updatedDate = LocalDate.of(2021, 2, 2)
    val initial =
        store.create(
            CreateAccessionRequestPayload(
                collectedDate = initialCollectedDate, receivedDate = initialReceivedDate))
    val desired = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(initial.accessionNumber, desired)

    val actual = store.fetchByNumber(initial.accessionNumber)

    assertEquals(desired, actual)
  }

  @Test
  fun `state history row is inserted at creation time`() {
    val initial = store.create(CreateAccessionRequestPayload())
    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .fetchInto(AccessionStateHistory::class.java)

    assertEquals(
        listOf(
            AccessionStateHistory(
                accessionId = 1,
                newStateId = AccessionState.Pending,
                reason = "Accession created",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `state transitions to Processing when seed count entered`() {
    val initial = store.create(CreateAccessionRequestPayload())
    store.update(initial.accessionNumber, initial.copy(seedsCounted = 10))
    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertEquals(AccessionState.Processing, fetched?.state)
    assertEquals(LocalDate.now(clock), fetched?.processingStartDate)

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Processing))
            .fetchInto(AccessionStateHistory::class.java)

    assertEquals(
        listOf(
            AccessionStateHistory(
                accessionId = 1,
                newStateId = AccessionState.Processing,
                oldStateId = AccessionState.Pending,
                reason = "Seeds have been counted",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `state short-circuits to Withdrawn when seeds withdrawn during processing`() {
    val initial = store.create(CreateAccessionRequestPayload())
    store.update(initial.accessionNumber, initial.copy(seedsCounted = 10))
    store.update(
        initial.accessionNumber,
        AccessionPayload(initial)
            .copy(
                seedsCounted = 10,
                withdrawals =
                    listOf(
                        WithdrawalPayload(
                            date = LocalDate.now(),
                            purpose = WithdrawalPurpose.Other,
                            seedsWithdrawn = 10))))
    val fetched = store.fetchByNumber(initial.accessionNumber)

    assertEquals(AccessionState.Withdrawn, fetched?.state)

    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .and(ACCESSION_STATE_HISTORY.NEW_STATE_ID.eq(AccessionState.Withdrawn))
            .fetchInto(AccessionStateHistory::class.java)

    assertEquals(
        listOf(
            AccessionStateHistory(
                accessionId = 1,
                newStateId = AccessionState.Withdrawn,
                oldStateId = AccessionState.Processing,
                reason = "No seeds remaining",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `fetchTimedStateTransitionCandidates matches correct dates based on state`() {
    val today = LocalDate.now(clock)
    val yesterday = today.minusDays(1)
    val tomorrow = today.plusDays(1)
    val twoWeeksAgo = today.minusDays(14)

    val shouldMatch =
        listOf(
            Accession(
                number = "ProcessingTimePassed",
                stateId = AccessionState.Processing,
                processingStartDate = twoWeeksAgo),
            Accession(
                number = "ProcessingToDrying",
                stateId = AccessionState.Processing,
                dryingStartDate = today),
            Accession(
                number = "ProcessedToDrying",
                stateId = AccessionState.Processed,
                dryingStartDate = today),
            Accession(
                number = "DryingToDried", stateId = AccessionState.Drying, dryingEndDate = today),
            Accession(
                number = "DryingToStorage",
                stateId = AccessionState.Drying,
                storageStartDate = today),
            Accession(
                number = "DriedToStorage",
                stateId = AccessionState.Dried,
                storageStartDate = yesterday),
        )

    val shouldNotMatch =
        listOf(
            Accession(
                number = "NoSeedCountYet",
                stateId = AccessionState.Pending,
                processingStartDate = twoWeeksAgo),
            Accession(
                number = "ProcessingTimeNotUpYet",
                stateId = AccessionState.Processing,
                processingStartDate = yesterday),
            Accession(
                number = "ProcessedToStorage",
                stateId = AccessionState.Processed,
                storageStartDate = today),
            Accession(
                number = "DriedToStorageTomorrow",
                stateId = AccessionState.Dried,
                storageStartDate = tomorrow),
        )

    (shouldMatch + shouldNotMatch).forEach { accession ->
      accessionDao.insert(
          accession.copy(createdTime = clock.instant(), siteModuleId = config.siteModuleId))
    }

    val expected = shouldMatch.map { it.number!! }.toSortedSet()
    val actual =
        store.fetchTimedStateTransitionCandidates().map { it.accessionNumber }.toSortedSet()

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
    deleteDevEnvironmentData()

    // Insert dummy accession rows so we can use the accession IDs
    repeat(7) { store.create(CreateAccessionRequestPayload()) }

    listOf(1 to 6, 1 to null, 2 to null, 2 to 5, 4 to null, 6 to null, 6 to null).forEachIndexed {
        index,
        (processingStartTime, processedStartTime) ->
      val accessionId = (index + 1).toLong()
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
          .update(ACCESSION)
          .set(ACCESSION.STATE_ID, currentState)
          .where(ACCESSION.ID.eq(accessionId))
          .execute()
    }

    assertEquals(
        2,
        store.countInState(
            state = AccessionState.Processing,
            sinceAfter = Instant.ofEpochMilli(2),
            sinceBefore = Instant.ofEpochMilli(4)),
        "Search with both time bounds")

    assertEquals(
        4,
        store.countInState(state = AccessionState.Processing, sinceAfter = Instant.ofEpochMilli(2)),
        "Search with startingAt")

    assertEquals(
        3,
        store.countInState(
            state = AccessionState.Processing, sinceBefore = Instant.ofEpochMilli(4)),
        "Search with sinceBefore")

    assertEquals(5, store.countInState(AccessionState.Processing), "Search without time bounds")
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
      val accession = store.create(CreateAccessionRequestPayload())

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
            .update(ACCESSION)
            .set(ACCESSION.STATE_ID, AccessionState.Withdrawn)
            .where(ACCESSION.ID.eq(accession.id))
            .execute()
      }
    }

    val expectedCounts = listOf(0, 5, 4, 4, 3, 2, 2)
    expectedCounts.forEachIndexed { asOf, expectedCount ->
      assertEquals(
          expectedCount,
          store.countActive(Instant.ofEpochMilli(asOf.toLong())),
          "Count as of time $asOf")
    }
  }

  private fun deleteDevEnvironmentData() {
    dslContext.deleteFrom(NOTIFICATION).execute()
    dslContext.deleteFrom(ACCESSION).execute()
  }

  private fun getSecondaryCollectors(accessionId: Long?): Set<Long> {
    with(ACCESSION_SECONDARY_COLLECTOR) {
      return dslContext
          .select(COLLECTOR_ID)
          .from(ACCESSION_SECONDARY_COLLECTOR)
          .where(ACCESSION_ID.eq(accessionId))
          .fetch(COLLECTOR_ID)
          .filterNotNull()
          .toSet()
    }
  }
}
