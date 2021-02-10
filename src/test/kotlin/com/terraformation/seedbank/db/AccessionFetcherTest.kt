package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.AccessionPayload
import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.DeviceInfoPayload
import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.api.seedbank.GerminationPayload
import com.terraformation.seedbank.api.seedbank.GerminationTestPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.daos.AppDeviceDao
import com.terraformation.seedbank.db.tables.daos.BagDao
import com.terraformation.seedbank.db.tables.daos.CollectionEventDao
import com.terraformation.seedbank.db.tables.daos.GerminationDao
import com.terraformation.seedbank.db.tables.daos.GerminationTestDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import com.terraformation.seedbank.db.tables.pojos.Bag
import com.terraformation.seedbank.db.tables.pojos.GerminationTest
import com.terraformation.seedbank.db.tables.pojos.StorageLocation
import com.terraformation.seedbank.db.tables.references.ACCESSION_GERMINATION_TEST_TYPE
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.model.AccessionNumberGenerator
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException

internal class AccessionFetcherTest : DatabaseTest() {
  @Autowired private lateinit var config: TerrawareServerConfig

  override val sequencesToReset
    get() =
        listOf(
            "accession_id_seq",
            "app_device_id_seq",
            "bag_id_seq",
            "collection_event_id_seq",
            "germination_test_id_seq",
            "species_id_seq",
            "species_family_id_seq",
        )

  private val accessionNumberGenerator = mockk<AccessionNumberGenerator>()
  private val clock = Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC)

  private lateinit var fetcher: AccessionFetcher
  private lateinit var accessionDao: AccessionDao
  private lateinit var appDeviceDao: AppDeviceDao
  private lateinit var bagDao: BagDao
  private lateinit var collectionEventDao: CollectionEventDao
  private lateinit var germinationDao: GerminationDao
  private lateinit var germinationTestDao: GerminationTestDao
  private lateinit var storageLocationDao: StorageLocationDao

  private val accessionNumbers = listOf("one", "two", "three", "four")

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    accessionDao = AccessionDao(jooqConfig)
    appDeviceDao = AppDeviceDao(jooqConfig)
    bagDao = BagDao(jooqConfig)
    collectionEventDao = CollectionEventDao(jooqConfig)
    germinationDao = GerminationDao(jooqConfig)
    germinationTestDao = GerminationTestDao(jooqConfig)
    storageLocationDao = StorageLocationDao(jooqConfig)

    fetcher =
        AccessionFetcher(
            dslContext,
            config,
            AppDeviceFetcher(dslContext, clock),
            BagFetcher(dslContext),
            CollectionEventFetcher(dslContext, clock),
            GerminationFetcher(dslContext),
            WithdrawalFetcher(dslContext, clock),
            clock)

    fetcher.accessionNumberGenerator = accessionNumberGenerator

    every { accessionNumberGenerator.generateAccessionNumber() } returnsMany accessionNumbers
  }

  @Test
  fun `create of empty accession populates default values`() {
    fetcher.create(CreateAccessionRequestPayload())

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
    val collidingAccessionNumbers = listOf("one", "one", "two")
    every { accessionNumberGenerator.generateAccessionNumber() } returnsMany
        collidingAccessionNumbers

    fetcher.create(CreateAccessionRequestPayload())
    fetcher.create(CreateAccessionRequestPayload())

    assertNotNull(accessionDao.fetchOneByNumber("two"))
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    every { accessionNumberGenerator.generateAccessionNumber() } returns ("duplicate")

    fetcher.create(CreateAccessionRequestPayload())

    assertThrows(DuplicateKeyException::class.java) {
      fetcher.create(CreateAccessionRequestPayload())
    }
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
    fetcher.create(payload)
    // Second time should reuse them
    fetcher.create(payload)

    val initialRow = accessionDao.fetchOneById(1)!!
    val secondRow = accessionDao.fetchOneById(2)!!

    assertNotEquals("Accession numbers", initialRow.number, secondRow.number)
    assertEquals("Species", initialRow.speciesId, secondRow.speciesId)
    assertEquals("Family", initialRow.speciesFamilyId, secondRow.speciesFamilyId)
    assertEquals("Primary collector", initialRow.primaryCollectorId, secondRow.primaryCollectorId)

    assertEquals("Number of secondary collectors", 2, getSecondaryCollectors(1).size)

    assertEquals("Secondary collectors", getSecondaryCollectors(1), getSecondaryCollectors(2))
  }

  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = CreateAccessionRequestPayload(bagNumbers = setOf("bag 1", "bag 2"))
    fetcher.create(payload)
    fetcher.create(payload)

    val initialBags = bagDao.fetchByAccessionId(1).toSet()
    val secondBags = bagDao.fetchByAccessionId(2).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial =
        fetcher.create(CreateAccessionRequestPayload(bagNumbers = setOf("bag 1", "bag 2")))
    val initialBags = bagDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API, so don't assume bag ID 1 is "bag 1".

    assertEquals("Initial bag IDs", setOf(1L, 2L), initialBags.map { it.id }.toSet())
    assertEquals(
        "Initial bag numbers", setOf("bag 1", "bag 2"), initialBags.map { it.label }.toSet())

    val desired = AccessionPayload(initial).copy(bagNumbers = setOf("bag 2", "bag 3"))

    assertTrue("Update succeeded", fetcher.update(initial.accessionNumber, desired))

    val updatedBags = bagDao.fetchByAccessionId(1)

    assertTrue("New bag inserted", Bag(3, 1, "bag 3") in updatedBags)
    assertTrue("Missing bag deleted", updatedBags.none { it.label == "bag 1" })
    assertEquals(
        "Existing bag is not replaced",
        initialBags.filter { it.label == "bag 2" },
        updatedBags.filter { it.label == "bag 2" })
  }

  @Test
  fun `device info is inserted at creation`() {
    val payload = CreateAccessionRequestPayload(deviceInfo = DeviceInfoPayload(model = "model"))
    fetcher.create(payload)

    val appDevice = appDeviceDao.fetchOneById(1)
    assertNotNull("Device row should have been inserted", appDevice)
    assertNull("App name should be null", appDevice?.appName)
    assertEquals(appDevice?.model, "model")
  }

  @Test
  fun `device info is retrieved`() {
    val payload = CreateAccessionRequestPayload(deviceInfo = DeviceInfoPayload(model = "model"))
    val initial = fetcher.create(payload)

    val fetched = fetcher.fetchByNumber(initial.accessionNumber)

    assertNotNull(fetched?.deviceInfo)
    assertEquals("model", fetched?.deviceInfo?.model)
  }

  @Test
  fun `geolocations are inserted and deleted as needed`() {
    val initial =
        fetcher.create(
            CreateAccessionRequestPayload(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(3), BigDecimal(4)))))
    val initialGeos = collectionEventDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API.

    assertEquals("Initial location IDs", setOf(1L, 2L), initialGeos.map { it.id }.toSet())
    assertEquals(
        "Accuracy is recorded", 100.0, initialGeos.mapNotNull { it.gpsAccuracy }.first(), 0.1)

    val desired =
        AccessionPayload(initial)
            .copy(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(5), BigDecimal(6))))

    assertTrue("Update succeeded", fetcher.update(initial.accessionNumber, desired))

    val updatedGeos = collectionEventDao.fetchByAccessionId(1)

    assertTrue(
        "New geo inserted",
        updatedGeos.any { it.id == 3L && it.latitude?.toInt() == 5 && it.longitude?.toInt() == 6 })
    assertTrue("Missing geo deleted", updatedGeos.none { it.latitude == BigDecimal(3) })
    assertEquals(
        "Existing geo retained",
        initialGeos.filter { it.latitude == BigDecimal(1) },
        updatedGeos.filter { it.latitude == BigDecimal(1) })
  }

  @Test
  fun `germination tests are inserted at creation time`() {
    fetcher.create(
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
        "Lab test is inserted",
        initialTests.any {
          it.testType == GerminationTestType.Lab &&
              it.substrateId == GerminationSubstrate.AgarPetriDish &&
              it.startDate == null
        })
    assertTrue(
        "Nursery test is inserted",
        initialTests.any {
          it.testType == GerminationTestType.Nursery &&
              it.substrateId == GerminationSubstrate.NurseryMedia &&
              it.startDate == null
        })
  }

  @Test
  fun `germination test types are inserted at creation time`() {
    fetcher.create(
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
    val initial = fetcher.create(CreateAccessionRequestPayload())
    val startDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val withTest =
        AccessionPayload(initial)
            .copy(
                germinationTests =
                    listOf(
                        GerminationTestPayload(
                            testType = GerminationTestType.Lab, startDate = startDate)))
    fetcher.update(initial.accessionNumber, withTest)

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
    assertNull("totalViabilityPercent", updatedAccession?.totalViabilityPercent)
    assertNull("latestViabilityPercent", updatedAccession?.latestViabilityPercent)
    assertNull("latestGerminationRecordingDate", updatedAccession?.latestGerminationRecordingDate)
  }

  @Test
  fun `germination test types are inserted by update`() {
    val initial =
        fetcher.create(
            CreateAccessionRequestPayload(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired =
        AccessionPayload(initial)
            .copy(
                germinationTestTypes = setOf(GerminationTestType.Lab, GerminationTestType.Nursery))
    fetcher.update(initial.accessionNumber, desired)

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
        fetcher.create(
            CreateAccessionRequestPayload(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired =
        AccessionPayload(initial).copy(germinationTestTypes = setOf(GerminationTestType.Nursery))
    fetcher.update(initial.accessionNumber, desired)

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
        fetcher.create(
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
    fetcher.update(initial.accessionNumber, desired)

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
        fetcher.create(
            CreateAccessionRequestPayload(
                germinationTests =
                    listOf(GerminationTestPayload(testType = GerminationTestType.Nursery))))
    val initial =
        fetcher.create(
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
      fetcher.update(initial.accessionNumber, desired)
    }
  }

  @Test
  fun `germinations are inserted by create`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    fetcher.create(
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

    assertEquals("Number of germinations inserted", 2, germinations.size)
    assertTrue(
        "First germination inserted",
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 123 })
    assertTrue(
        "First germination inserted",
        germinations.any { it.recordingDate == localDate.plusDays(1) && it.seedsGerminated == 456 })
  }

  @Test
  fun `germinations are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial =
        fetcher.create(
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
                            seedsSown = 100,
                            germinations =
                                listOf(
                                    GerminationPayload(
                                        recordingDate = localDate, seedsGerminated = 75)))))
    fetcher.update(initial.accessionNumber, desired)
    val germinations = germinationDao.fetchByTestId(1)

    assertEquals("Number of germinations after update", 1, germinations.size)
    assertTrue(
        "First germination preserved",
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 })

    val updatedAccession = accessionDao.fetchOneById(1)
    assertEquals("totalViabilityPercent", 75, updatedAccession?.totalViabilityPercent)
    assertEquals("latestViabilityPercent", 75, updatedAccession?.latestViabilityPercent)
    assertEquals(
        "latestGerminationRecordingDate",
        localDate,
        updatedAccession?.latestGerminationRecordingDate)
  }

  @Test
  fun `germinations are deleted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
    val initial =
        fetcher.create(
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
    fetcher.update(initial.accessionNumber, desired)
    val germinations = germinationDao.fetchByTestId(1)

    assertEquals("Number of germinations after update", 1, germinations.size)
    assertTrue(
        "First germination preserved",
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 })

    val updatedAccession = accessionDao.fetchOneById(1)
    assertEquals("totalViabilityPercent", 75, updatedAccession?.totalViabilityPercent)
    assertEquals("latestViabilityPercent", 75, updatedAccession?.latestViabilityPercent)
    assertEquals(
        "latestGerminationRecordingDate",
        localDate,
        updatedAccession?.latestGerminationRecordingDate)
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

    val initial = fetcher.create(CreateAccessionRequestPayload())
    fetcher.update(
        initial.accessionNumber, AccessionPayload(initial).copy(storageLocation = locationName))

    assertEquals(
        "Existing storage location ID was used",
        locationId,
        accessionDao.fetchOneById(1)?.storageLocationId)

    val updated = fetcher.fetchByNumber(initial.accessionNumber)!!
    assertEquals("Location name", locationName, updated.storageLocation)
    assertEquals("Storage condition", StorageCondition.Freezer, updated.storageCondition)
  }

  @Test
  fun `unknown storage locations are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      val initial = fetcher.create(CreateAccessionRequestPayload())
      fetcher.update(
          initial.accessionNumber, AccessionPayload(initial).copy(storageLocation = "bogus"))
    }
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
