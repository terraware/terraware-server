package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.IDENTIFIER_SEQUENCES
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.SourcePlantOrigin
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionQuantityHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.seedbank.api.CreateAccessionRequestPayload
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.seeds
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.reflect.full.declaredMemberProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class AccessionStoreCreateTest : AccessionStoreTest() {
  @Test
  fun `create of empty accession populates default values`() {
    store.create(AccessionModel(facilityId = facilityId))

    assertEquals(
        AccessionsRow(
            id = AccessionId(1),
            facilityId = facilityId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            dataSourceId = DataSource.Web,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            number = "19700101000",
            stateId = AccessionState.AwaitingCheckIn),
        accessionsDao.fetchOneById(AccessionId(1)))
  }

  @Test
  fun `create of accession with processing notes is supported`() {
    store.create(AccessionModel(facilityId = facilityId, processingNotes = "test processing notes"))

    assertEquals(
        "test processing notes", accessionsDao.fetchOneById(AccessionId(1))?.processingNotes)
  }

  @Test
  fun `create deals with collisions in accession numbers`() {
    val model1 = store.create(AccessionModel(facilityId = facilityId))
    dslContext.deleteFrom(IDENTIFIER_SEQUENCES).execute()
    val model2 = store.create(AccessionModel(facilityId = facilityId))

    assertNotNull(model1.accessionNumber, "First accession should have a number")
    assertNotNull(model2.accessionNumber, "Second accession should have a number")
    assertNotEquals(
        model1.accessionNumber,
        model2.accessionNumber,
        "Accession numbers should be unique within facility")
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    repeat(10) { store.create(AccessionModel(facilityId = facilityId)) }

    dslContext.deleteFrom(IDENTIFIER_SEQUENCES).execute()

    assertThrows<DuplicateKeyException> { store.create(AccessionModel(facilityId = facilityId)) }
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
  fun `create with isManualState allows initial state to be set`() {
    store.create(
        AccessionModel(
            facilityId = facilityId, isManualState = true, state = AccessionState.Processing))

    val row = accessionsDao.fetchOneById(AccessionId(1))!!
    assertEquals(AccessionState.Processing, row.stateId)

    // Remove this once we don't need v1 interoperability and checkedInTime goes away.
    assertNotNull(row.checkedInTime, "Accession should be counted as checked in")
  }

  @Test
  fun `create with isManualState defaults to Awaiting Check-In if not supplied by caller`() {
    store.create(AccessionModel(facilityId = facilityId, isManualState = true))

    val row = accessionsDao.fetchOneById(AccessionId(1))!!
    assertEquals(AccessionState.AwaitingCheckIn, row.stateId)

    // Remove this once we don't need v1 interoperability and checkedInTime goes away.
    assertNull(row.checkedInTime, "Accession should not be counted as checked in")
  }

  @Test
  fun `create with isManualState does not allow setting state to Used Up`() {
    assertThrows<IllegalArgumentException> {
      store.create(
          AccessionModel(
              facilityId = facilityId, isManualState = true, state = AccessionState.UsedUp))
    }
  }

  @Test
  fun `create with isManualState does not allow v1-only states`() {
    assertThrows<IllegalArgumentException> {
      store.create(
          AccessionModel(
              facilityId = facilityId, isManualState = true, state = AccessionState.Dried))
    }
  }

  @Test
  fun `create without isManualState uses caller-supplied species name`() {
    val oldSpeciesId = SpeciesId(1)
    val newSpeciesId = SpeciesId(2)
    val oldSpeciesName = "Old species"
    val newSpeciesName = "New species"
    insertSpecies(oldSpeciesId, oldSpeciesName)
    insertSpecies(newSpeciesId, newSpeciesName)

    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId, species = newSpeciesName, speciesId = oldSpeciesId))

    assertEquals(newSpeciesId, initial.speciesId, "Species ID")
    assertEquals(newSpeciesName, initial.species, "Species name")
  }

  @Test
  fun `create with isManualState uses caller-supplied species ID`() {
    val oldSpeciesId = SpeciesId(1)
    val newSpeciesId = SpeciesId(2)
    val oldSpeciesName = "Old species"
    val newSpeciesName = "New species"
    insertSpecies(oldSpeciesId, oldSpeciesName)
    insertSpecies(newSpeciesId, newSpeciesName)

    val initial =
        store.create(
            AccessionModel(
                facilityId = facilityId,
                isManualState = true,
                species = oldSpeciesName,
                speciesId = newSpeciesId))

    assertEquals(newSpeciesId, initial.speciesId, "Species ID")
    assertEquals(newSpeciesName, initial.species, "Species name")
  }

  @Test
  fun `create does not allow creating an accession at a nursery facility`() {
    val nurseryFacilityId = FacilityId(2)

    insertFacility(nurseryFacilityId, type = FacilityType.Nursery)

    assertThrows<FacilityTypeMismatchException> {
      store.create(AccessionModel(facilityId = nurseryFacilityId))
    }
  }

  @Test
  fun `create inserts quantity history row if quantity is specified`() {
    val initial =
        store.create(
            AccessionModel(facilityId = facilityId, isManualState = true, remaining = seeds(10)))

    assertEquals(
        listOf(
            AccessionQuantityHistoryRow(
                accessionId = initial.id,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                historyTypeId = AccessionQuantityHistoryType.Observed,
                id = AccessionQuantityHistoryId(1),
                remainingQuantity = BigDecimal.TEN,
                remainingUnitsId = SeedQuantityUnits.Seeds)),
        accessionQuantityHistoryDao.findAll())
  }

  @Test
  fun `create does not insert quantity history row if quantity is not specified`() {
    store.create(AccessionModel(facilityId = facilityId, isManualState = true))

    assertEquals(emptyList<AccessionQuantityHistoryRow>(), accessionQuantityHistoryDao.findAll())
  }

  @Test
  fun `create writes all fields to database`() {
    val today = LocalDate.now(clock)
    val accession =
        CreateAccessionRequestPayload(
            bagNumbers = setOf("abc"),
            collectedDate = today,
            collectionSiteCity = "city",
            collectionSiteCountryCode = "UG",
            collectionSiteCountrySubdivision = "subdivision",
            collectionSiteLandowner = "landowner",
            collectionSiteName = "siteName",
            collectionSiteNotes = "siteNotes",
            collectionSource = CollectionSource.Other,
            collectors = listOf("primaryCollector", "second1", "second2"),
            environmentalNotes = "envNotes",
            facilityId = facilityId,
            fieldNotes = "fieldNotes",
            founderId = "founderId",
            geolocations =
                setOf(
                    Geolocation(
                        latitude = BigDecimal.ONE,
                        longitude = BigDecimal.TEN,
                        accuracy = BigDecimal(3))),
            landowner = "landowner",
            numberOfTrees = 10,
            receivedDate = today,
            siteLocation = "siteLocation",
            source = DataSource.FileImport,
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

    // Check fields that have different names in the create payload and the model.
    assertEquals(DataSource.FileImport, stored.source, "Data source")

    assertEquals(
        listOf(
            AccessionCollectorsRow(stored.id, 0, "primaryCollector"),
            AccessionCollectorsRow(stored.id, 1, "second1"),
            AccessionCollectorsRow(stored.id, 2, "second2")),
        accessionCollectorsDao.findAll().sortedBy { it.position },
        "Collectors are stored")
  }

  @Test
  fun `create does not write to database if user does not have permission`() {
    every { user.canCreateAccession(facilityId) } returns false
    every { user.canReadFacility(facilityId) } returns true

    assertThrows<AccessDeniedException> { store.create(AccessionModel(facilityId = facilityId)) }
  }
}
