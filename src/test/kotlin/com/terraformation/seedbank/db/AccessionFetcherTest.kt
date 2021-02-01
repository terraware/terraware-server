package com.terraformation.seedbank.db

import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.api.seedbank.AccessionPayload
import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.Geolocation
import com.terraformation.seedbank.db.tables.daos.AccessionDao
import com.terraformation.seedbank.db.tables.daos.BagDao
import com.terraformation.seedbank.db.tables.daos.CollectionEventDao
import com.terraformation.seedbank.db.tables.pojos.Accession
import com.terraformation.seedbank.db.tables.pojos.Bag
import com.terraformation.seedbank.db.tables.references.ACCESSION_SECONDARY_COLLECTOR
import com.terraformation.seedbank.db.tables.references.BAG
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
            "bag_id_seq",
            "collection_event_id_seq",
            "species_id_seq",
            "species_family_id_seq",
        )

  private val accessionNumberGenerator = mockk<AccessionNumberGenerator>()
  private val clock = Clock.fixed(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC)

  private lateinit var fetcher: AccessionFetcher
  private lateinit var accessionDao: AccessionDao
  private lateinit var bagDao: BagDao
  private lateinit var collectionEventDao: CollectionEventDao

  private val accessionNumbers = listOf("one", "two", "three", "four")

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    accessionDao = AccessionDao(jooqConfig)
    bagDao = BagDao(jooqConfig)
    collectionEventDao = CollectionEventDao(jooqConfig)

    fetcher = AccessionFetcher(dslContext, config)

    fetcher.accessionNumberGenerator = accessionNumberGenerator
    fetcher.clock = clock

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
  fun `valid storage condition is accepted`() {
    val initial = fetcher.create(CreateAccessionRequestPayload())

    fetcher.update(
        initial.accessionNumber,
        AccessionPayload(initial).copy(targetStorageCondition = "Refrigerator"))

    val row = accessionDao.fetchOneById(1)!!
    assertEquals("Storage condition ID", 1, row.targetStorageCondition)
  }

  @Test
  fun `undefined storage condition is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      val initial = fetcher.create(CreateAccessionRequestPayload())

      fetcher.update(
          initial.accessionNumber,
          AccessionPayload(initial).copy(targetStorageCondition = "Amazon warehouse"))
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

  private fun getBagIds(accessionId: Long?): Set<Long> {
    with(BAG) {
      return dslContext
          .select(ID)
          .from(BAG)
          .where(ACCESSION_ID.eq(accessionId))
          .fetch(ID)
          .filterNotNull()
          .toSet()
    }
  }
}
