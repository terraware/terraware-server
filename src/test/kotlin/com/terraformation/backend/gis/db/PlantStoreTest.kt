package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.PostgresFuzzySearchOperators
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.assertPointsEqual
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.gis.model.FeatureModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class PlantStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private val siteId = SiteId(10)
  private val layerId = LayerId(100)
  private val nonExistentLayerId = LayerId(401)
  private val featureId = FeatureId(1000)
  private val nonExistentFeatureId = FeatureId(400)

  private val validCreateRequest = PlantsRow(featureId = featureId, label = "Some plant label")
  private val speciesIdsToCount = mapOf(SpeciesId(1) to 4, SpeciesId(2) to 1, SpeciesId(3) to 3)
  private val nonExistentSpeciesId = SpeciesId(402)

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private lateinit var store: PlantStore
  private lateinit var featuresDao: FeaturesDao
  private lateinit var postgresFuzzySearchOperators: PostgresFuzzySearchOperators
  private lateinit var layersDao: LayersDao
  private lateinit var plantsDao: PlantsDao
  private lateinit var speciesDao: SpeciesDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()

    featuresDao = FeaturesDao(jooqConfig)
    postgresFuzzySearchOperators = PostgresFuzzySearchOperators()
    layersDao = LayersDao(jooqConfig)
    plantsDao = PlantsDao(jooqConfig)
    speciesDao = SpeciesDao(jooqConfig)

    store =
        PlantStore(
            clock, dslContext, featuresDao, postgresFuzzySearchOperators, plantsDao, speciesDao)
    every { clock.instant() } returns time1
    every { user.canReadFeature(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canUpdateFeature(any()) } returns true

    insertSiteData()
    insertLayer(id = layerId.value, siteId = siteId.value, layerType = LayerType.PlantsPlanted)
    insertFeature(id = featureId.value, layerId = layerId.value)
  }

  fun insertSeveralPlants(
      speciesIdsToCount: Map<SpeciesId, Int>,
      enteredTime: Instant = time1
  ): Map<SpeciesId, List<FeatureId>> {
    val speciesIdToFeatureIds = mutableMapOf<SpeciesId, List<FeatureId>>()
    speciesIdsToCount.forEach {
      val currSpeciesId = it.key
      speciesDao.insert(
          SpeciesRow(
              id = currSpeciesId,
              createdTime = time1,
              modifiedTime = time1,
              // Make the speciesName the same as the species id to simplify testing
              name = currSpeciesId.toString()))

      val featureIds = mutableListOf<FeatureId>()

      repeat(it.value) {
        val randomFeatureId = FeatureId(Random.nextLong())
        featureIds.add(randomFeatureId)

        // All features are associated with the class variable layerId
        insertFeature(
            id = randomFeatureId.value, layerId = layerId.value, enteredTime = enteredTime)

        plantsDao.insert(
            PlantsRow(
                featureId = randomFeatureId,
                speciesId = currSpeciesId,
                createdTime = time1,
                modifiedTime = time2))
      }
      speciesIdToFeatureIds[currSpeciesId] = featureIds
    }

    return speciesIdToFeatureIds
  }

  @Test
  fun `create adds a new row to Plants table, returns PlantsRow with populated timestamps`() {
    val plant = store.createPlant(validCreateRequest)
    assertEquals(time1, plant.createdTime)
    assertEquals(time1, plant.modifiedTime)
    assertEquals(validCreateRequest.copy(createdTime = time1, modifiedTime = time1), plant)
    assertEquals(plant, plantsDao.fetchOneByFeatureId(validCreateRequest.featureId!!))
  }

  @Test
  fun `create does not modify row passed in, returns a different row instance`() {
    val requestCopy = validCreateRequest.copy()
    val plant = store.createPlant(validCreateRequest)
    assertTrue(
        requestCopy == validCreateRequest,
        "validCreateRequest is not being modified by the plant store")
    assertFalse(
        validCreateRequest === plant,
        "the plant store does not return back the same instance it received")
  }

  @Test
  fun `create ignores timestamp fields if they are set`() {
    val plant =
        store.createPlant(validCreateRequest.copy(createdTime = time2, modifiedTime = time2))
    assertEquals(time1, plant.createdTime)
    assertEquals(time1, plant.modifiedTime)
  }

  @Test
  fun `create throws IllegalArgumentException if feature id is null`() {
    assertThrows<IllegalArgumentException> {
      store.createPlant(validCreateRequest.copy(featureId = null))
    }
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't have permission`() {
    every { user.canUpdateFeature(any()) } returns false
    assertThrows<AccessDeniedException> { store.createPlant(validCreateRequest) }
  }

  @Test
  fun `create fails with FeatureNotFoundException if feature doesn't exist`() {
    assertThrows<FeatureNotFoundException> {
      store.createPlant(validCreateRequest.copy(featureId = nonExistentFeatureId))
    }
  }

  @Test
  fun `create fails with DataIntegrityViolationException if the label is an empty string`() {
    assertThrows<DataIntegrityViolationException> {
      store.createPlant(validCreateRequest.copy(label = ""))
    }
  }

  @Test
  fun `fetchPlant returns PlantsRow with timestamps`() {
    val plant = store.createPlant(validCreateRequest)
    val readResult = store.fetchPlant(plant.featureId!!)
    assertNotNull(readResult)
    assertEquals(time1, readResult!!.createdTime)
    assertEquals(time1, readResult.modifiedTime)
    assertEquals(plant, readResult)
  }

  @Test
  fun `fetchPlant returns null if user doesn't have read permission, even if they have create permission`() {
    val plant = store.createPlant(validCreateRequest)
    every { user.canReadFeature(any()) } returns false
    assertNull(store.fetchPlant(plant.featureId!!))
  }

  @Test
  fun `fetchPlant returns null if plant doesn't exist`() {
    store.createPlant(validCreateRequest)
    assertNull(store.fetchPlant(nonExistentFeatureId))
  }

  @Test
  fun `fetchPlantList elements contain all feature and plant data`() {
    val feature =
        FeatureModel(
            id = nonExistentFeatureId,
            layerId = layerId,
            geom = newPoint(60.8, -45.6, 0.0, SRID.LONG_LAT),
            gpsHorizAccuracy = 10.28,
            gpsVertAccuracy = 20.08,
            attrib = "Some attrib",
            notes = "Some notes",
            enteredTime = time1,
        )
    insertFeature(
        feature.id!!.value,
        feature.layerId.value,
        feature.geom,
        feature.gpsHorizAccuracy,
        feature.gpsVertAccuracy,
        feature.attrib,
        feature.notes,
        feature.enteredTime!!)

    val species =
        SpeciesRow(id = SpeciesId(15), createdTime = time1, modifiedTime = time2, name = "Koa")
    speciesDao.insert(species)

    val plant =
        store.createPlant(
            PlantsRow(
                featureId = feature.id,
                label = "Some label",
                speciesId = species.id,
                naturalRegen = true,
                datePlanted = LocalDate.EPOCH))

    val expectedPlantFeatureData =
        FetchPlantListResult(
            featureId = plant.featureId!!,
            label = plant.label,
            speciesId = species.id,
            naturalRegen = plant.naturalRegen,
            datePlanted = plant.datePlanted,
            layerId = feature.layerId,
            gpsHorizAccuracy = feature.gpsHorizAccuracy,
            gpsVertAccuracy = feature.gpsVertAccuracy,
            attrib = feature.attrib,
            notes = feature.notes,
            enteredTime = feature.enteredTime,
            geom = feature.geom,
        )
    val actualPlantFeatureData = store.fetchPlantsList(layerId)[0]

    assertPointsEqual(expectedPlantFeatureData.geom, actualPlantFeatureData.geom)
    assertEquals(
        expectedPlantFeatureData, actualPlantFeatureData.copy(geom = expectedPlantFeatureData.geom))
  }

  @Test
  fun `fetchPlantList returns all plants in the layer when no filters are applied`() {
    val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
    val plantsFetched = store.fetchPlantsList(layerId)
    val expectedFeatureIds = speciesIdToFeatureIds.map { it -> it.value.map { it } }.flatten()
    val actualFeatureIds = plantsFetched.map { it.featureId }
    assertEquals(expectedFeatureIds, actualFeatureIds)
  }

  @Test
  fun `fetchPlantList filters on species id when it is provided`() {
    val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
    val speciesIdFilter = speciesIdToFeatureIds.keys.elementAt(0)
    // For testing simplicity, insertSeveralPlants makes species name and id the same
    val plantsFetched = store.fetchPlantsList(layerId, speciesName = speciesIdFilter.toString())
    val expectedFeatureIds =
        speciesIdToFeatureIds.filter { it.key == speciesIdFilter }.values.flatten()
    val actualFeatureIds = plantsFetched.map { it.featureId }
    assertEquals(expectedFeatureIds, actualFeatureIds)

    assertEquals(
        emptyList<FetchPlantListResult>(),
        store.fetchPlantsList(layerId, speciesName = nonExistentSpeciesId.toString()))
  }

  @Test
  fun `fetchPlantsList filters on min and max entered times when they are provided`() {
    val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount, enteredTime = time1)
    val plantsFetched = store.fetchPlantsList(layerId, minEnteredTime = time1)
    val maxPlantsFetched = store.fetchPlantsList(layerId, maxEnteredTime = time1)
    assertEquals(plantsFetched, maxPlantsFetched)
    val expectedFeatureIds = speciesIdToFeatureIds.map { it -> it.value.map { it } }.flatten()
    val actualFeatureIds = plantsFetched.map { it.featureId }
    assertEquals(expectedFeatureIds, actualFeatureIds)

    assertEquals(
        emptyList<FetchPlantListResult>(), store.fetchPlantsList(layerId, minEnteredTime = time2))
    assertEquals(
        emptyList<FetchPlantListResult>(),
        store.fetchPlantsList(layerId, maxEnteredTime = time1.minusSeconds(1)))
  }

  @Test
  fun `fetchPlantsList filters on notes when provided`() {
    val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
    val featureId = speciesIdToFeatureIds.values.flatten().first()
    val feature = featuresDao.fetchOneById(featureId)!!
    val note = "special note to filter by"
    feature.notes = note
    featuresDao.update(feature)

    val plantsFetched = store.fetchPlantsList(layerId, notes = note)
    assertEquals(1, plantsFetched.size)
    assertEquals(featureId, plantsFetched.last().featureId)

    assertEquals(
        emptyList<FetchPlantListResult>(),
        store.fetchPlantsList(layerId, notes = "note that doesn't exist"))
  }

  @Test
  fun `fetchPlantsList filters on notes using fuzzy (approximate) matching`() {
    val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
    val featureId = speciesIdToFeatureIds.values.flatten().first()
    val feature = featuresDao.fetchOneById(featureId)!!
    feature.notes = "special note to filter by"
    featuresDao.update(feature)

    // Deliberately misspell "special"
    val plantsFetched = store.fetchPlantsList(layerId, notes = "specal")
    assertEquals(1, plantsFetched.size)
    assertEquals(featureId, plantsFetched.last().featureId)
  }

  @Test
  fun `fetchPlantsList returns empty list when user doesn't have read permission`() {
    insertSeveralPlants(speciesIdsToCount)
    every { user.canReadLayer(any()) } returns false
    assertEquals(emptyList<FetchPlantListResult>(), store.fetchPlantsList(layerId))
  }

  @Test
  fun `fetchPlantsList returns empty list when layer doesn't exist`() {
    assertEquals(emptyList<FetchPlantListResult>(), store.fetchPlantsList(nonExistentLayerId))
  }

  @Test
  fun `fetchPlantsList returns empty list when there are no plants in the layer`() {
    assertEquals(emptyList<FetchPlantListResult>(), store.fetchPlantsList(layerId))
  }

  @Test
  fun `fetchPlantSummary counts how many plants of each species exist in a layer`() {
    insertSeveralPlants(speciesIdsToCount)
    assertEquals(speciesIdsToCount, store.fetchPlantSummary(layerId))
  }

  @Test
  fun `fetchPlantSummary uses -1 as a sentinel species ID to count plants where species ID = null`() {
    insertSeveralPlants(speciesIdsToCount)
    val featuresWithoutSpecies = mutableListOf<FeatureId>()
    repeat(3) {
      val randomFeatureId = FeatureId(Random.nextLong())
      featuresWithoutSpecies.add(randomFeatureId)
      insertFeature(id = randomFeatureId.value, layerId = layerId.value, enteredTime = time1)
      plantsDao.insert(
          PlantsRow(featureId = randomFeatureId, createdTime = time1, modifiedTime = time2))
    }
    val expectedSpeciesIdsToCount = speciesIdsToCount.toMutableMap()
    expectedSpeciesIdsToCount[SpeciesId(-1)] = 3
    assertEquals(expectedSpeciesIdsToCount, store.fetchPlantSummary(layerId))
  }

  @Test
  fun `fetchPlantSummary applies min and max entered time filters when provided`() {
    insertSeveralPlants(speciesIdsToCount, enteredTime = time1)
    assertEquals(
        emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(layerId, minEnteredTime = time2))
    assertEquals(
        emptyMap<SpeciesId, Int>(),
        store.fetchPlantSummary(layerId, maxEnteredTime = time1.minusSeconds(1)))
    assertEquals(speciesIdsToCount, store.fetchPlantSummary(layerId, maxEnteredTime = time1))
    assertEquals(
        speciesIdsToCount,
        store.fetchPlantSummary(layerId, minEnteredTime = time1, maxEnteredTime = time1))
  }

  @Test
  fun `fetchPlantSummary returns an empty map if user doesn't have permission to read layer data`() {
    every { user.canReadLayer(any()) } returns false
    insertSeveralPlants(speciesIdsToCount)
    assertEquals(emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(layerId))
  }

  @Test
  fun `fetchPlantSummary returns an empty map if the layer doesn't exist`() {
    assertEquals(emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(nonExistentLayerId))
  }

  @Test
  fun `fetchPlantSummary returns an empty map if there are no plants in the layer`() {
    assertEquals(emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(layerId))
  }

  @Test
  fun `update changes the row in the database, returns row with updated timestamps`() {
    val plant = store.createPlant(validCreateRequest)
    val plannedUpdates = plant.copy(label = "test update writes to db")
    every { clock.instant() } returns time2
    val updatedResult = store.updatePlant(plannedUpdates)

    assertEquals(plannedUpdates.copy(createdTime = time1, modifiedTime = time2), updatedResult)
    assertEquals(updatedResult, plantsDao.fetchOneByFeatureId(featureId))
  }

  @Test
  fun `update does not modify row passed in, returns a different row instance`() {
    val plant = store.createPlant(validCreateRequest)
    val plannedUpdates = plant.copy(label = "referential equality test")
    val plannedUpdatesCopy = plannedUpdates.copy()
    val updated = store.updatePlant(plannedUpdates)
    assertTrue(
        plannedUpdatesCopy == plannedUpdates,
        "instance we passed as an argument is not being modified by the plant store")
    assertFalse(
        updated === plannedUpdates,
        "the plant store does not return back the same instance it received")
  }

  @Test
  fun `update does not update timestamps if there's no change from the database`() {
    val plant = store.createPlant(validCreateRequest)
    val updated = store.updatePlant(plant)
    assertEquals(plant, updated)
  }

  @Test
  fun `update ignores timestamps if they are set`() {
    val plant = store.createPlant(validCreateRequest)
    val ignoredTime = time2.plusSeconds(5)
    every { clock.instant() } returns time2
    val updatedPlant =
        store.updatePlant(
            plant.copy(
                label = "update ignores timestamps",
                createdTime = ignoredTime,
                modifiedTime = ignoredTime))
    assertEquals(time1, updatedPlant.createdTime)
    assertEquals(time2, updatedPlant.modifiedTime)
  }

  @Test
  fun `update fails with IllegalArgumentException if feature id is null`() {
    assertThrows<IllegalArgumentException> {
      store.updatePlant(validCreateRequest.copy(featureId = null))
    }
  }

  @Test
  fun `update fails with PlantNotFoundException if user doesn't have update permission`() {
    val plant = store.createPlant(validCreateRequest)
    every { user.canUpdateFeature(any()) } returns false
    assertThrows<PlantNotFoundException> { store.updatePlant(plant) }
  }

  @Test
  fun `update fails with PlantNotFoundException if plant doesn't exist`() {
    val plant = store.createPlant(validCreateRequest)
    assertThrows<PlantNotFoundException> {
      store.updatePlant(plant.copy(featureId = nonExistentFeatureId))
    }
  }

  @Test
  fun `update fails with DataIntegrityViolationException if the label is an empty string`() {
    val plant = store.createPlant(validCreateRequest)
    assertThrows<DataIntegrityViolationException> { store.updatePlant(plant.copy(label = "")) }
  }
}
