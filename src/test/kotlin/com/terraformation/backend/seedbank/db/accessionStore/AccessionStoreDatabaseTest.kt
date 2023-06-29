package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.AccessionSpeciesHasDeliveriesException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.db.seedbank.tables.pojos.GeolocationsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.pojos.WithdrawalsRow
import com.terraformation.backend.db.seedbank.tables.records.AccessionStateHistoryRecord
import com.terraformation.backend.db.seedbank.tables.references.ACCESSION_STATE_HISTORY
import com.terraformation.backend.seedbank.api.AccessionStateV2
import com.terraformation.backend.seedbank.api.CreateViabilityTestRequestPayload
import com.terraformation.backend.seedbank.api.CreateWithdrawalRequestPayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayloadV2
import com.terraformation.backend.seedbank.event.AccessionSpeciesChangedEvent
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.kilograms
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

internal class AccessionStoreDatabaseTest : AccessionStoreTest() {
  @Test
  fun `existing rows are used for free-text fields that live in reference tables`() {
    val payload = accessionModel(species = "test species")

    // First time inserts the reference table rows
    val initialAccession = store.create(payload)
    // Second time should reuse them
    val secondAccession = store.create(payload)

    val initialRow = accessionsDao.fetchOneById(initialAccession.id!!)!!
    val secondRow = accessionsDao.fetchOneById(secondAccession.id!!)!!

    assertNotEquals(initialRow.number, secondRow.number, "Accession numbers")
    assertEquals(initialRow.speciesId, secondRow.speciesId, "Species")
    assertEquals(
        initialRow.speciesId, initialAccession.speciesId, "Species ID as returned on insert")
    assertEquals(secondRow.speciesId, secondAccession.speciesId, "Species ID as returned on update")
  }

  @Test
  fun `update removes existing collectors if needed`() {
    val initial = store.create(accessionModel(collectors = listOf("primary", "second1", "second2")))

    store.update(initial.copy(collectors = listOf("second1")))

    assertEquals(
        listOf(AccessionCollectorsRow(initial.id, 0, "second1")),
        accessionCollectorsDao.findAll(),
        "Collectors are stored")
  }

  @Test
  fun `update writes all API payload fields to database`() {
    val storageLocationName = "Test Location"
    val today = LocalDate.now(clock)
    val update =
        UpdateAccessionRequestPayloadV2(
            bagNumbers = setOf("abc"),
            collectedDate = today,
            collectionSiteCity = "city",
            collectionSiteCoordinates =
                setOf(
                    Geolocation(
                        latitude = BigDecimal.ONE,
                        longitude = BigDecimal.TEN,
                        accuracy = BigDecimal(3))),
            collectionSiteCountryCode = "UG",
            collectionSiteCountrySubdivision = "subdivision",
            collectionSiteLandowner = "landowner",
            collectionSiteName = "name",
            collectionSiteNotes = "notes",
            collectionSource = CollectionSource.Reintroduced,
            collectors = listOf("primaryCollector", "second1", "second2"),
            dryingEndDate = today,
            facilityId = facilityId,
            notes = "notes",
            plantId = "plantId",
            plantsCollectedFrom = 10,
            receivedDate = today,
            remainingQuantity = kilograms(15),
            speciesId = SpeciesId(1),
            state = AccessionStateV2.Drying,
            storageLocation = storageLocationName,
            subsetCount = 5,
            subsetWeight = grams(9),
            viabilityPercent = 15,
        )

    // Some fields are named differently in the v2 API and the model class.
    val payloadFieldNames =
        mapOf(
            "geolocations" to "collectionSiteCoordinates",
            "founderId" to "plantId",
            "processingNotes" to "notes",
            "numberOfTrees" to "plantsCollectedFrom",
            "subsetWeightQuantity" to "subsetWeight",
            "totalViabilityPercent" to "viabilityPercent",
        )

    val updatePayloadProperties = UpdateAccessionRequestPayloadV2::class.declaredMemberProperties
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

    insertSpecies(1)
    insertStorageLocation(1, name = storageLocationName)

    val initial = store.create(accessionModel(source = DataSource.SeedCollectorApp))
    val stored = store.updateAndFetch(update.applyToModel(initial))

    accessionModelProperties
        .filter { (payloadFieldNames[it.name] ?: it.name) in propertyNames }
        .forEach { prop ->
          assertNotNull(prop.get(stored), "Field ${prop.name} is null in stored object")
        }

    assertEquals(
        listOf(
            AccessionCollectorsRow(stored.id, 0, "primaryCollector"),
            AccessionCollectorsRow(stored.id, 1, "second1"),
            AccessionCollectorsRow(stored.id, 2, "second2")),
        accessionCollectorsDao.findAll().sortedBy { it.position },
        "Collectors are stored")

    // Old species ID was null, so this doesn't count as a change.
    publisher.assertEventNotPublished(AccessionSpeciesChangedEvent::class.java)
  }

  @Test
  fun `update publishes event if species is changed`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()

    val initial = store.create(accessionModel(speciesId = speciesId1))
    store.update(initial.copy(speciesId = speciesId2))

    publisher.assertEventPublished(
        AccessionSpeciesChangedEvent(initial.id!!, speciesId1, speciesId2))
  }

  @Test
  fun `update throws exception on species change if accession has deliveries`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val initial = store.create(accessionModel(speciesId = speciesId1))

    val nurseryFacilityId = FacilityId(2)
    insertFacility(nurseryFacilityId, type = FacilityType.Nursery)
    insertBatch(BatchesRow(accessionId = initial.id!!))
    insertWithdrawal()
    insertBatchWithdrawal()
    insertPlantingSite()
    insertDelivery()

    assertThrows<AccessionSpeciesHasDeliveriesException> {
      store.update(initial.copy(speciesId = speciesId2))
    }
  }

  @Test
  fun `update throws exception on species change if accession has deliveries`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val initial = store.create(accessionModel(speciesId = speciesId1))

    val nurseryFacilityId = FacilityId(2)
    insertFacility(nurseryFacilityId, type = FacilityType.Nursery)
    insertBatch(BatchesRow(accessionId = initial.id!!))
    insertWithdrawal()
    insertBatchWithdrawal()
    insertPlantingSite()
    insertDelivery()

    assertThrows<AccessionSpeciesHasDeliveriesException> {
      store.update(initial.copy(speciesId = speciesId2))
    }
  }

  @Test
  fun `delete removes data from child tables`() {
    val storageLocationName = "Test Location"
    val today = LocalDate.now(clock)
    val update =
        UpdateAccessionRequestPayloadV2(
            bagNumbers = setOf("abc"),
            collectedDate = today,
            collectionSiteCoordinates =
                setOf(
                    Geolocation(
                        latitude = BigDecimal.ONE,
                        longitude = BigDecimal.TEN,
                        accuracy = BigDecimal(3))),
            collectors = listOf("collector 1", "collector 2"),
            facilityId = facilityId,
            receivedDate = today,
            remainingQuantity = seeds(100),
            speciesId = SpeciesId(1),
            state = AccessionStateV2.InStorage,
            storageLocation = storageLocationName,
        )

    val viabilityTest =
        CreateViabilityTestRequestPayload(
            seedsTested = 10, startDate = today, testType = ViabilityTestType.Lab)
    val withdrawal =
        CreateWithdrawalRequestPayload(
            date = today,
            purpose = WithdrawalPurpose.Other,
            notes = "notes",
            withdrawnQuantity = seeds(41))

    insertSpecies(1)
    insertStorageLocation(1, name = storageLocationName)

    val initial = store.create(accessionModel())
    val accessionId = initial.id!!

    val updated =
        update
            .applyToModel(initial)
            .addViabilityTest(viabilityTest.toModel(accessionId))
            .addWithdrawal(withdrawal.toModel(accessionId))
    store.update(updated)

    store.delete(accessionId)

    assertEquals(
        emptyList<AccessionCollectorsRow>(), accessionCollectorsDao.findAll(), "Collectors")
    assertEquals(emptyList<AccessionsRow>(), accessionsDao.findAll(), "Accessions")
    assertEquals(
        emptyList<AccessionStateHistoryRecord>(),
        dslContext.selectFrom(ACCESSION_STATE_HISTORY).fetch(),
        "Accession State History")
    assertEquals(emptyList<BagsRow>(), bagsDao.findAll(), "Bags")
    assertEquals(emptyList<GeolocationsRow>(), geolocationsDao.findAll(), "Geolocations")
    assertEquals(emptyList<ViabilityTestsRow>(), viabilityTestsDao.findAll(), "Viability Tests")
    assertEquals(
        emptyList<ViabilityTestResultsRow>(),
        viabilityTestResultsDao.findAll(),
        "Viability test results")
    assertEquals(emptyList<WithdrawalsRow>(), withdrawalsDao.findAll(), "Withdrawals")
  }

  @Test
  fun `fetchOneById uses species names from species table`() {
    val speciesId = SpeciesId(1)
    val oldScientificName = "Test Scientific Name"
    val newScientificName = "New Scientific Name"
    val commonName = "Test Common Name"
    insertSpecies(speciesId, scientificName = oldScientificName, commonName = commonName)

    val initial = store.create(accessionModel(speciesId = speciesId))

    speciesDao.update(speciesDao.fetchOneById(speciesId)!!.copy(scientificName = newScientificName))

    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(newScientificName, fetched.species, "Scientific name")
    assertEquals(commonName, fetched.speciesCommonName, "Common name")
  }

  @Test
  fun `fetchOneById detects delivery to planting site`() {
    val speciesId = insertSpecies()
    val initial = store.create(accessionModel(speciesId = speciesId))

    assertFalse(initial.hasDeliveries, "Should not have delivery after initial creation")

    val nurseryFacilityId = FacilityId(2)
    insertFacility(nurseryFacilityId, type = FacilityType.Nursery)
    insertBatch(BatchesRow(accessionId = initial.id!!))
    insertWithdrawal()
    insertBatchWithdrawal()
    insertPlantingSite()
    insertDelivery()

    val withDelivery = store.fetchOneById(initial.id!!)
    assertTrue(withDelivery.hasDeliveries, "Delivery should be indicated in accession")

    val otherAccession =
        store.fetchOneById(store.create(accessionModel(speciesId = speciesId)).id!!)
    assertFalse(
        otherAccession.hasDeliveries, "Delivery existence should not affect other accessions")
  }

  @Test
  fun `dryRun does not persist changes`() {
    val initial = store.create(accessionModel(species = "Initial Species"))
    store.dryRun(initial.copy(species = "Modified Species"))
    val fetched = store.fetchOneById(initial.id!!)

    assertEquals(initial.species, fetched.species)
  }
}
