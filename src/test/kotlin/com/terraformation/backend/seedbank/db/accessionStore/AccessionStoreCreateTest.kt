package com.terraformation.backend.seedbank.db.accessionStore

import com.terraformation.backend.db.FacilityTypeMismatchException
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.IDENTIFIER_SEQUENCES
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryId
import com.terraformation.backend.db.seedbank.AccessionQuantityHistoryType
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionQuantityHistoryRow
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.seedbank.api.AccessionStateV2
import com.terraformation.backend.seedbank.api.CreateAccessionRequestPayloadV2
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class AccessionStoreCreateTest : AccessionStoreTest() {
  @Test
  fun `create of empty accession populates default values`() {
    store.create(accessionModel())

    assertEquals(
        AccessionsRow(
            id = AccessionId(1),
            facilityId = facilityId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            dataSourceId = DataSource.Web,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            number = "70-1-1-001",
            stateId = AccessionState.AwaitingCheckIn),
        accessionsDao.fetchOneById(AccessionId(1)))
  }

  @Test
  fun `create of accession with processing notes is supported`() {
    store.create(accessionModel(processingNotes = "test processing notes"))

    assertEquals(
        "test processing notes", accessionsDao.fetchOneById(AccessionId(1))?.processingNotes)
  }

  @Test
  fun `create deals with collisions in accession numbers`() {
    val model1 = store.create(accessionModel())
    dslContext.deleteFrom(IDENTIFIER_SEQUENCES).execute()
    val model2 = store.create(accessionModel())

    assertNotNull(model1.accessionNumber, "First accession should have a number")
    assertNotNull(model2.accessionNumber, "Second accession should have a number")
    assertNotEquals(
        model1.accessionNumber,
        model2.accessionNumber,
        "Accession numbers should be unique within facility")
  }

  @Test
  fun `create gives up if it can't generate an unused accession number`() {
    repeat(10) { store.create(accessionModel()) }

    dslContext.deleteFrom(IDENTIFIER_SEQUENCES).execute()

    assertThrows<DuplicateKeyException> { store.create(accessionModel()) }
  }

  @Test
  fun `create with isManualState allows initial state to be set`() {
    store.create(accessionModel(state = AccessionState.Processing))

    val row = accessionsDao.fetchOneById(AccessionId(1))!!
    assertEquals(AccessionState.Processing, row.stateId)
  }

  @Test
  fun `create with isManualState defaults to Awaiting Check-In if not supplied by caller`() {
    store.create(accessionModel())

    val row = accessionsDao.fetchOneById(AccessionId(1))!!
    assertEquals(AccessionState.AwaitingCheckIn, row.stateId)
  }

  @Test
  fun `create with isManualState does not allow setting state to Used Up if quantity is nonzero`() {
    assertThrows<IllegalArgumentException> {
      store.create(accessionModel(remaining = seeds(1), state = AccessionState.UsedUp))
    }
  }

  @Test
  fun `create with isManualState does not allow v1-only states`() {
    assertThrows<IllegalArgumentException> {
      store.create(accessionModel(state = AccessionState.Dried))
    }
  }

  @Test
  fun `create with isManualState uses caller-supplied species ID`() {
    val oldSpeciesId = SpeciesId(1)
    val newSpeciesId = SpeciesId(2)
    val oldSpeciesName = "Old species"
    val newSpeciesName = "New species"
    insertSpecies(oldSpeciesId, oldSpeciesName)
    insertSpecies(newSpeciesId, newSpeciesName)

    val initial = store.create(accessionModel(species = oldSpeciesName, speciesId = newSpeciesId))

    assertEquals(newSpeciesId, initial.speciesId, "Species ID")
    assertEquals(newSpeciesName, initial.species, "Species name")
  }

  @Test
  fun `create does not allow creating an accession at a nursery facility`() {
    val nurseryFacilityId = FacilityId(2)

    insertFacility(nurseryFacilityId, type = FacilityType.Nursery)

    assertThrows<FacilityTypeMismatchException> {
      store.create(accessionModel(facilityId = nurseryFacilityId))
    }
  }

  @Test
  fun `create inserts quantity history row if quantity is specified`() {
    val initial = store.create(accessionModel(remaining = seeds(10)))

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
    store.create(accessionModel())

    assertEquals(emptyList<AccessionQuantityHistoryRow>(), accessionQuantityHistoryDao.findAll())
  }

  @Test
  fun `create writes all API payload fields to database`() {
    insertProject(name = "Project Foo")
    insertSpecies(1)
    insertSubLocation(1, facilityId, "Location 1")

    val today = LocalDate.now(clock)
    val accession =
        CreateAccessionRequestPayloadV2(
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
            collectionSiteName = "siteName",
            collectionSiteNotes = "siteNotes",
            collectionSource = CollectionSource.Other,
            collectors = listOf("primaryCollector", "second1", "second2"),
            facilityId = facilityId,
            notes = "notes",
            plantId = "plantId",
            plantsCollectedFrom = 10,
            projectId = inserted.projectId,
            receivedDate = today,
            source = DataSource.FileImport,
            speciesId = SpeciesId(1),
            state = AccessionStateV2.AwaitingProcessing,
            subLocation = "Location 1",
        )

    // Some fields are named differently in the v2 API and the model class.
    val payloadFieldNames =
        mapOf(
            "geolocations" to "collectionSiteCoordinates",
            "founderId" to "plantId",
            "numberOfTrees" to "plantsCollectedFrom",
        )

    val createPayloadProperties = CreateAccessionRequestPayloadV2::class.declaredMemberProperties
    val accessionModelProperties = AccessionModel::class.declaredMemberProperties
    val propertyNames = createPayloadProperties.map { it.name }.toSet()

    createPayloadProperties.forEach { prop ->
      assertNotNull(prop.get(accession), "Field ${prop.name} is null in example object")
    }

    val stored = store.create(accession.toModel(clock))

    accessionModelProperties
        .filter { (payloadFieldNames[it.name] ?: it.name) in propertyNames }
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

    assertEquals(stored.projectName, "Project Foo")
  }

  @Test
  fun `create does not write to database if user does not have permission`() {
    every { user.canCreateAccession(facilityId) } returns false
    every { user.canReadFacility(facilityId) } returns true

    assertThrows<AccessDeniedException> { store.create(accessionModel()) }
  }

  @Test
  fun `throws exception if project is in different organization than facility`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)
    val projectId = insertProject(organizationId = otherOrganizationId)

    assertThrows<ProjectInDifferentOrganizationException> {
      store.create(accessionModel(projectId = projectId))
    }
  }

  @Test
  fun `throws exception if no permission to read project`() {
    val projectId = ProjectId(1000)
    every { user.canReadProject(projectId) } returns false

    assertThrows<ProjectNotFoundException> { store.create(accessionModel(projectId = projectId)) }
  }
}
