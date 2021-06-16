package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.DeviceInfoPayload
import com.terraformation.seedbank.api.seedbank.GerminationPayload
import com.terraformation.seedbank.api.seedbank.GerminationTestPayload
import com.terraformation.seedbank.api.seedbank.SeedQuantityPayload
import com.terraformation.seedbank.api.seedbank.UpdateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.WithdrawalPayload
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.sequences.ACCESSION_NUMBER_SEQ
import com.terraformation.seedbank.db.tables.daos.AccessionPhotosDao
import com.terraformation.seedbank.db.tables.daos.AccessionsDao
import com.terraformation.seedbank.db.tables.daos.AppDevicesDao
import com.terraformation.seedbank.db.tables.daos.BagsDao
import com.terraformation.seedbank.db.tables.daos.GeolocationsDao
import com.terraformation.seedbank.db.tables.daos.GerminationTestsDao
import com.terraformation.seedbank.db.tables.daos.GerminationsDao
import com.terraformation.seedbank.db.tables.daos.SpeciesDao
import com.terraformation.seedbank.db.tables.daos.StorageLocationsDao
import com.terraformation.seedbank.db.tables.pojos.AccessionPhotosRow
import com.terraformation.seedbank.db.tables.pojos.AccessionStateHistoryRow
import com.terraformation.seedbank.db.tables.pojos.AccessionsRow
import com.terraformation.seedbank.db.tables.pojos.BagsRow
import com.terraformation.seedbank.db.tables.pojos.GerminationTestsRow
import com.terraformation.seedbank.db.tables.pojos.SpeciesRow
import com.terraformation.seedbank.db.tables.pojos.StorageLocationsRow
import com.terraformation.seedbank.db.tables.records.AccessionStateHistoryRecord
import com.terraformation.seedbank.db.tables.references.ACCESSIONS
import com.terraformation.seedbank.db.tables.references.ACCESSION_GERMINATION_TEST_TYPES
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTORS
import com.terraformation.seedbank.db.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.seedbank.grams
import com.terraformation.seedbank.kilograms
import com.terraformation.seedbank.model.AccessionModel
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.AppDeviceModel
import com.terraformation.seedbank.model.Geolocation
import com.terraformation.seedbank.model.GerminationModel
import com.terraformation.seedbank.model.GerminationTestModel
import com.terraformation.seedbank.model.SeedQuantityModel
import com.terraformation.seedbank.model.WithdrawalModel
import com.terraformation.seedbank.seeds
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoField
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
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
            "withdrawal_id_seq",
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
  private lateinit var accessionsDao: AccessionsDao
  private lateinit var accessionPhotosDao: AccessionPhotosDao
  private lateinit var appDevicesDao: AppDevicesDao
  private lateinit var bagsDao: BagsDao
  private lateinit var geolocationsDao: GeolocationsDao
  private lateinit var germinationsDao: GerminationsDao
  private lateinit var germinationTestsDao: GerminationTestsDao
  private lateinit var speciesDao: SpeciesDao
  private lateinit var storageLocationsDao: StorageLocationsDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    accessionsDao = AccessionsDao(jooqConfig)
    accessionPhotosDao = AccessionPhotosDao(jooqConfig)
    appDevicesDao = AppDevicesDao(jooqConfig)
    bagsDao = BagsDao(jooqConfig)
    geolocationsDao = GeolocationsDao(jooqConfig)
    germinationsDao = GerminationsDao(jooqConfig)
    germinationTestsDao = GerminationTestsDao(jooqConfig)
    speciesDao = SpeciesDao(jooqConfig)
    storageLocationsDao = StorageLocationsDao(jooqConfig)

    val support = StoreSupport(config, dslContext)

    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    store =
        AccessionStore(
            dslContext,
            config,
            accessionPhotosDao,
            AppDeviceStore(dslContext, clock),
            BagStore(dslContext),
            GeolocationStore(dslContext, clock),
            GerminationStore(dslContext),
            SpeciesStore(clock, dslContext, support),
            WithdrawalStore(dslContext, clock),
            clock,
            support,
        )

    insertSiteData()
  }

  @Test
  fun `create of empty accession populates default values`() {
    store.create(AccessionModel())

    assertEquals(
        AccessionsRow(
            id = 1,
            siteModuleId = config.siteModuleId,
            createdTime = clock.instant(),
            number = accessionNumbers[0],
            stateId = AccessionState.Pending),
        accessionsDao.fetchOneById(1))
  }

  @Test
  fun `create deals with collisions in accession numbers`() {
    store.create(AccessionModel())
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()
    store.create(AccessionModel())

    assertNotNull(accessionsDao.fetchOneByNumber(accessionNumbers[1]))
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    repeat(10) { store.create(AccessionModel()) }

    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000000000).execute()

    assertThrows(DuplicateKeyException::class.java) { store.create(AccessionModel()) }
  }

  @Test
  fun `create adds digit to accession number suffix if it exceeds 3 digits`() {
    dslContext.alterSequence(ACCESSION_NUMBER_SEQ).restartWith(197001010000001000).execute()
    val inserted = store.create(AccessionModel())
    assertEquals(inserted.accessionNumber, "197001011000")
  }

  @Test
  fun `existing rows are used for free-text fields that live in reference tables`() {
    val payload =
        AccessionModel(
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
    assertEquals(initialRow.speciesFamilyId, secondRow.speciesFamilyId, "Family")
    assertEquals(initialRow.primaryCollectorId, secondRow.primaryCollectorId, "Primary collector")

    assertEquals(2, getSecondaryCollectors(1).size, "Number of secondary collectors")

    assertEquals(getSecondaryCollectors(1), getSecondaryCollectors(2), "Secondary collectors")
  }

  @Test
  fun `bag numbers are not shared between accessions`() {
    val payload = AccessionModel(bagNumbers = setOf("bag 1", "bag 2"))
    store.create(payload)
    store.create(payload)

    val initialBags = bagsDao.fetchByAccessionId(1).toSet()
    val secondBags = bagsDao.fetchByAccessionId(2).toSet()

    assertNotEquals(initialBags, secondBags)
  }

  @Test
  fun `bags are inserted and deleted as needed`() {
    val initial = store.create(AccessionModel(bagNumbers = setOf("bag 1", "bag 2")))
    val initialBags = bagsDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API, so don't assume bag ID 1 is "bag 1".

    assertEquals(setOf(1L, 2L), initialBags.map { it.id }.toSet(), "Initial bag IDs")
    assertEquals(
        setOf("bag 1", "bag 2"), initialBags.map { it.bagNumber }.toSet(), "Initial bag numbers")

    val desired = initial.copy(bagNumbers = setOf("bag 2", "bag 3"))

    assertTrue(store.update(initial.accessionNumber!!, desired), "Update succeeded")

    val updatedBags = bagsDao.fetchByAccessionId(1)

    assertTrue(BagsRow(3, 1, "bag 3") in updatedBags, "New bag inserted")
    assertTrue(updatedBags.none { it.bagNumber == "bag 1" }, "Missing bag deleted")
    assertEquals(
        initialBags.filter { it.bagNumber == "bag 2" },
        updatedBags.filter { it.bagNumber == "bag 2" },
        "Existing bag is not replaced")
  }

  @Test
  fun `device info is inserted at creation`() {
    val payload = AccessionModel(deviceInfo = AppDeviceModel(model = "model"))
    store.create(payload)

    val appDevice = appDevicesDao.fetchOneById(1)
    assertNotNull(appDevice, "Device row should have been inserted")
    assertNull(appDevice?.appName, "App name should be null")
    assertEquals(appDevice?.model, "model")
  }

  @Test
  fun `device info is retrieved`() {
    val payload = AccessionModel(deviceInfo = AppDeviceModel(model = "model"))
    val initial = store.create(payload)

    val fetched = store.fetchByNumber(initial.accessionNumber!!)

    assertNotNull(fetched?.deviceInfo)
    assertEquals("model", fetched?.deviceInfo?.model)
    assertEquals(AccessionSource.SeedCollectorApp, initial.source)
  }

  @Test
  fun `geolocations are inserted and deleted as needed`() {
    val initial =
        store.create(
            AccessionModel(
                geolocations =
                    setOf(
                        Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                        Geolocation(BigDecimal(3), BigDecimal(4)))))
    val initialGeos = geolocationsDao.fetchByAccessionId(1)

    // Insertion order is not defined by the API.

    assertEquals(setOf(1L, 2L), initialGeos.map { it.id }.toSet(), "Initial location IDs")
    assertEquals(
        100.0, initialGeos.mapNotNull { it.gpsAccuracy }.first(), 0.1, "Accuracy is recorded")

    val desired =
        initial.copy(
            geolocations =
                setOf(
                    Geolocation(BigDecimal(1), BigDecimal(2), BigDecimal(100)),
                    Geolocation(BigDecimal(5), BigDecimal(6))))

    assertTrue(store.update(initial.accessionNumber!!, desired), "Update succeeded")

    val updatedGeos = geolocationsDao.fetchByAccessionId(1)

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
  fun `germination test types are inserted at creation time`() {
    store.create(AccessionModel(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(1))
            .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)

    assertEquals(listOf(GerminationTestType.Lab), types)
  }

  @Test
  fun `germination tests are inserted by update`() {
    val initial = store.create(AccessionModel())
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
    store.update(initial.accessionNumber!!, withTest.toModel())

    val updatedTests = germinationTestsDao.fetchByAccessionId(1)
    assertEquals(
        listOf(
            GerminationTestsRow(
                accessionId = 1,
                id = 1,
                remainingQuantity = BigDecimal(100),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                startDate = startDate,
                testType = GerminationTestType.Lab,
            )),
        updatedTests)

    val updatedAccession = accessionsDao.fetchOneById(1)
    assertNull(updatedAccession?.totalViabilityPercent, "totalViabilityPercent")
    assertNull(updatedAccession?.latestViabilityPercent, "latestViabilityPercent")
    assertNull(updatedAccession?.latestGerminationRecordingDate, "latestGerminationRecordingDate")
  }

  @Test
  fun `germination test types are inserted by update`() {
    val initial =
        store.create(AccessionModel(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired =
        initial.copy(
            germinationTestTypes = setOf(GerminationTestType.Lab, GerminationTestType.Nursery))
    store.update(initial.accessionNumber!!, desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(1))
            .fetch(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
    assertEquals(setOf(GerminationTestType.Lab, GerminationTestType.Nursery), types.toSet())
  }

  @Test
  fun `germination test types are deleted by update`() {
    val initial =
        store.create(AccessionModel(germinationTestTypes = setOf(GerminationTestType.Lab)))
    val desired = initial.copy(germinationTestTypes = setOf(GerminationTestType.Nursery))
    store.update(initial.accessionNumber!!, desired)

    val types =
        dslContext
            .select(ACCESSION_GERMINATION_TEST_TYPES.GERMINATION_TEST_TYPE_ID)
            .from(ACCESSION_GERMINATION_TEST_TYPES)
            .where(ACCESSION_GERMINATION_TEST_TYPES.ACCESSION_ID.eq(1))
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
    store.update(initial.accessionNumber!!, desired)

    val updatedTests = germinationTestsDao.fetchByAccessionId(1)
    assertEquals(
        listOf(
            GerminationTestsRow(
                id = 1,
                accessionId = 1,
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
    val updated = store.updateAndFetch(desired, initial.accessionNumber!!)

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

    assertThrows(IllegalArgumentException::class.java) {
      store.update(initial.accessionNumber!!, desired)
    }
  }

  @Test
  fun `germinations are inserted by update`() {
    val localDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
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
                        seedsSown = 200,
                        germinations =
                            listOf(
                                GerminationModel(
                                    recordingDate = localDate, seedsGerminated = 75)))))
    store.update(initial.accessionNumber!!, desired)

    val germinationTests = germinationTestsDao.fetchByAccessionId(1)
    assertEquals(1, germinationTests.size, "Number of germination tests after update")
    assertEquals(37, germinationTests[0].totalPercentGerminated, "totalPercentGerminated")
    assertEquals(75, germinationTests[0].totalSeedsGerminated, "totalSeedsGerminated")

    val germinations = germinationsDao.fetchByTestId(1)
    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedAccession = accessionsDao.fetchOneById(1)
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
          initialQuantity = seeds(100),
          germinationTests =
              listOf(
                  GerminationTestPayload(
                      testType = GerminationTestType.Lab,
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
                    GerminationTestModel(
                        id = initial.germinationTests[0].id,
                        testType = GerminationTestType.Lab,
                        seedsSown = 100,
                        germinations =
                            listOf(
                                GerminationModel(
                                    recordingDate = localDate, seedsGerminated = 75)))))
    store.update(initial.accessionNumber!!, desired)
    val germinations = germinationsDao.fetchByTestId(1)

    assertEquals(1, germinations.size, "Number of germinations after update")
    assertTrue(
        germinations.any { it.recordingDate == localDate && it.seedsGerminated == 75 },
        "First germination preserved")

    val updatedAccession = accessionsDao.fetchOneById(1)
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
    storageLocationsDao.insert(
        StorageLocationsRow(
            id = locationId,
            siteModuleId = config.siteModuleId,
            name = locationName,
            conditionId = StorageCondition.Freezer))

    val initial = store.create(AccessionModel())
    store.update(initial.accessionNumber!!, initial.copy(storageLocation = locationName))

    assertEquals(
        locationId,
        accessionsDao.fetchOneById(1)?.storageLocationId,
        "Existing storage location ID was used")

    val updated = store.fetchByNumber(initial.accessionNumber!!)!!
    assertEquals(locationName, updated.storageLocation, "Location name")
    assertEquals(StorageCondition.Freezer, updated.storageCondition, "Storage condition")
  }

  @Test
  fun `unknown storage locations are rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      val initial = store.create(AccessionModel())
      store.update(initial.accessionNumber!!, initial.copy(storageLocation = "bogus"))
    }
  }

  @Test
  fun `photo filenames are returned`() {
    val initial = store.create(AccessionModel())
    accessionPhotosDao.insert(
        AccessionPhotosRow(
            accessionId = 1,
            filename = "photo.jpg",
            uploadedTime = Instant.now(),
            capturedTime = Instant.now(),
            contentType = MediaType.IMAGE_JPEG_VALUE,
            size = 123))

    val fetched = store.fetchByNumber(initial.accessionNumber!!)

    assertEquals(listOf("photo.jpg"), fetched?.photoFilenames)
  }

  @Test
  fun `update recalculates estimated seed count`() {
    val initial = store.create(AccessionModel())
    store.update(
        initial.accessionNumber!!,
        initial.copy(
            processingMethod = ProcessingMethod.Weight,
            subsetCount = 1,
            subsetWeightQuantity = SeedQuantityModel(BigDecimal.ONE, SeedQuantityUnits.Ounces),
            total = SeedQuantityModel(BigDecimal.TEN, SeedQuantityUnits.Pounds)))
    val fetched = store.fetchByNumber(initial.accessionNumber!!)!!

    assertEquals(160, fetched.estimatedSeedCount, "Estimated seed count is added")

    store.update(initial.accessionNumber!!, fetched.copy(total = null))

    val fetchedAfterClear = store.fetchByNumber(initial.accessionNumber!!)!!

    assertNull(fetchedAfterClear.estimatedSeedCount, "Estimated seed count is removed")
  }

  @Test
  fun `update recalculates seeds remaining when seed count is filled in`() {
    val initial = store.create(AccessionModel())
    store.update(
        initial.accessionNumber!!,
        initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchByNumber(initial.accessionNumber!!)

    assertEquals(seeds<SeedQuantityModel>(10), fetched?.remaining)
  }

  @Test
  fun `update recalculates seeds remaining on withdrawal`() {
    val initial = store.create(AccessionModel())
    store.update(
        initial.accessionNumber!!,
        initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
    val fetched = store.fetchByNumber(initial.accessionNumber!!)

    assertEquals(seeds(10), fetched?.remaining)
  }

  @Test
  fun `update rejects future storageStartDate`() {
    val initial = store.create(AccessionModel())
    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
          initial.copy(storageStartDate = LocalDate.now(clock).plusDays(1)))
    }
  }

  @Test
  fun `absence of deviceInfo causes source to be set to Web`() {
    val initial = store.create(AccessionModel())
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
                deviceInfo = AppDeviceModel(appName = "collector"),
                collectedDate = initialCollectedDate,
                receivedDate = initialReceivedDate))
    val requested = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(initial.accessionNumber!!, requested)

    val actual = store.fetchByNumber(initial.accessionNumber!!)

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
                collectedDate = initialCollectedDate, receivedDate = initialReceivedDate))
    val desired = initial.copy(collectedDate = updatedDate, receivedDate = updatedDate)

    store.update(initial.accessionNumber!!, desired)

    val actual = store.fetchByNumber(initial.accessionNumber!!)

    assertEquals(desired, actual)
  }

  @Test
  fun `update generates withdrawals for new germination tests`() {
    val accession = createAccessionWithGerminationTest()
    val test = accession.germinationTests[0]

    assertEquals(
        listOf(
            WithdrawalModel(
                id = 1,
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

    val updated = store.updateAndFetch(accession, accession.accessionNumber!!)
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
        store.updateAndFetch(
            initial.copy(germinationTests = listOf(modifiedTest)), initial.accessionNumber!!)

    assertEquals(listOf(modifiedWithdrawal), afterTestModified.withdrawals)
  }

  @Test
  fun `update does not modify withdrawals when their germination tests are not modified`() {
    val initial = createAccessionWithGerminationTest()
    val updated =
        store.updateAndFetch(
            initial.copy(receivedDate = LocalDate.now()), initial.accessionNumber!!)

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `update removes withdrawals when germination tests are removed`() {
    val initial = createAccessionWithGerminationTest()
    val updated =
        store.updateAndFetch(
            initial.copy(germinationTests = emptyList()), initial.accessionNumber!!)

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
            initial.copy(withdrawals = listOf(modifiedInitialWithdrawal, newWithdrawal)),
            initial.accessionNumber!!)

    assertEquals(initial.withdrawals, updated.withdrawals)
  }

  @Test
  fun `state history row is inserted at creation time`() {
    val initial = store.create(AccessionModel())
    val historyRecords =
        dslContext
            .selectFrom(ACCESSION_STATE_HISTORY)
            .where(ACCESSION_STATE_HISTORY.ACCESSION_ID.eq(initial.id))
            .fetchInto(AccessionStateHistoryRow::class.java)

    assertEquals(
        listOf(
            AccessionStateHistoryRow(
                accessionId = 1,
                newStateId = AccessionState.Pending,
                reason = "Accession created",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `state transitions to Processing when seed count entered`() {
    val initial = store.create(AccessionModel())
    store.update(
        initial.accessionNumber!!,
        initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(100)))
    val fetched = store.fetchByNumber(initial.accessionNumber!!)

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
                accessionId = 1,
                newStateId = AccessionState.Processing,
                oldStateId = AccessionState.Pending,
                reason = "Seed count/weight has been entered",
                updatedTime = clock.instant())),
        historyRecords)
  }

  @Test
  fun `dryRun does not persist changes`() {
    val initial = store.create(AccessionModel(species = "Initial Species"))
    store.dryRun(initial.copy(species = "Modified Species"), initial.accessionNumber!!)
    val fetched = store.fetchByNumber(initial.accessionNumber!!)

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
          accession.copy(createdTime = clock.instant(), siteModuleId = config.siteModuleId))
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
    repeat(7) { store.create(AccessionModel()) }

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
          .update(ACCESSIONS)
          .set(ACCESSIONS.STATE_ID, currentState)
          .where(ACCESSIONS.ID.eq(accessionId))
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
      val accession = store.create(AccessionModel())

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
          store.countActive(Instant.ofEpochMilli(asOf.toLong())),
          "Count as of time $asOf")
    }
  }

  @Test
  fun `updateSpecies renames species when there is no name collision`() {
    val accession1 = store.create(AccessionModel(species = "species1"))
    val accession2 = store.create(AccessionModel(species = "species2"))

    val now = Instant.now().with(ChronoField.MILLI_OF_SECOND, 0)
    every { clock.instant() } returns now

    val newId = store.updateSpecies(1, "species1a")

    assertNull(newId, "No new species ID should be returned")
    assertEquals(
        SpeciesRow(id = 1, name = "species1a", createdTime = Instant.EPOCH, modifiedTime = now),
        speciesDao.fetchOneById(1),
        "Updated species")
    assertEquals(
        SpeciesRow(
            id = 2, name = "species2", createdTime = Instant.EPOCH, modifiedTime = Instant.EPOCH),
        speciesDao.fetchOneById(2),
        "Unmodified species")
    assertEquals(
        "species1a",
        store.fetchByNumber(accession1.accessionNumber!!)?.species,
        "Updated species name on accession")
    assertEquals(
        "species2",
        store.fetchByNumber(accession2.accessionNumber!!)?.species,
        "Unmodified species name on accession")
  }

  @Test
  fun `updateSpecies merges species when there is a name collision`() {
    val accession1 = store.create(AccessionModel(species = "species1"))
    val accession2 = store.create(AccessionModel(species = "species2"))

    val newId = store.updateSpecies(1, "species2")

    assertEquals(2, newId, "Existing species ID should be returned")
    assertNull(speciesDao.fetchOneById(1), "Old species should be deleted")
    assertEquals("species2", speciesDao.fetchOneById(2)?.name, "Unmodified species name")
    assertEquals(
        "species2",
        store.fetchByNumber(accession1.accessionNumber!!)?.species,
        "Updated species name on accession")
    assertEquals(
        "species2",
        store.fetchByNumber(accession2.accessionNumber!!)?.species,
        "Unmodified species name on accession")
  }

  @Test
  fun `updateSpecies throws exception when species does not exist`() {
    assertThrows(SpeciesNotFoundException::class.java) { store.updateSpecies(1, "nonexistent") }
  }

  @Test
  fun `update rejects weight-based withdrawals for count-based accessions`() {
    val initial = store.create(AccessionModel())

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
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
    val initial = store.create(AccessionModel())

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
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
    val initial = store.create(AccessionModel())

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
          initial.copy(
              processingMethod = ProcessingMethod.Weight,
              total = grams(10),
              subsetWeightQuantity = seeds(5)))
    }
  }

  @Test
  fun `update rejects withdrawals if accession total size not set`() {
    val initial = store.create(AccessionModel())

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
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
    val initial = store.create(AccessionModel())

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
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
            initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(1)),
            initial.accessionNumber!!)
    assertEquals(seeds<SeedQuantityModel>(1), withCountMethod.total)

    val withWeightMethod =
        store.updateAndFetch(
            withCountMethod.copy(processingMethod = ProcessingMethod.Weight, total = grams(2)),
            initial.accessionNumber!!)
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

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
          initial.copy(processingMethod = ProcessingMethod.Weight, total = grams(5)))
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

    assertThrows(IllegalArgumentException::class.java) {
      store.update(
          initial.accessionNumber!!,
          initial.copy(processingMethod = ProcessingMethod.Count, total = seeds(10)))
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
            rare = SpeciesRareType.Yes,
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

    accessionModelProperties.filter { it.name in propertyNames }.forEach { prop ->
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
            rare = SpeciesRareType.Yes,
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
            siteModuleId = config.siteModuleId,
            name = storageLocationName,
            conditionId = StorageCondition.Freezer))

    val initial = store.create(AccessionModel())
    val stored = store.updateAndFetch(update.toModel(), initial.accessionNumber!!)

    accessionModelProperties.filter { it.name in propertyNames }.forEach { prop ->
      assertNotNull(prop.get(stored), "Field ${prop.name} is null in stored object")
    }
  }

  private fun getSecondaryCollectors(accessionId: Long?): Set<Long> {
    with(ACCESSION_SECONDARY_COLLECTORS) {
      return dslContext
          .select(COLLECTOR_ID)
          .from(ACCESSION_SECONDARY_COLLECTORS)
          .where(ACCESSION_ID.eq(accessionId))
          .fetch(COLLECTOR_ID)
          .filterNotNull()
          .toSet()
    }
  }

  private fun createAndUpdate(
      edit: (UpdateAccessionRequestPayload) -> UpdateAccessionRequestPayload
  ): AccessionModel {
    val initial = store.create(AccessionModel())
    val edited = edit(initial.toUpdatePayload())
    return store.updateAndFetch(edited.toModel(), initial.accessionNumber!!)
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
