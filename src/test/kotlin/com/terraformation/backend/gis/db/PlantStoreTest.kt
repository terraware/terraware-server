package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.ShapeType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import io.mockk.every
import io.mockk.mockk
import java.lang.IllegalArgumentException
import java.time.Clock
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
  private lateinit var layersDao: LayersDao
  private lateinit var plantsDao: PlantsDao
  private lateinit var speciesDao: SpeciesDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()

    featuresDao = FeaturesDao(jooqConfig)
    layersDao = LayersDao(jooqConfig)
    plantsDao = PlantsDao(jooqConfig)
    speciesDao = SpeciesDao(jooqConfig)

    store = PlantStore(clock, dslContext, plantsDao, speciesDao)
    every { clock.instant() } returns time1
    every { user.canCreateLayerData(featureId = any()) } returns true
    every { user.canReadLayerData(featureId = any()) } returns true
    every { user.canReadLayerData(layerId = any()) } returns true
    every { user.canUpdateLayerData(featureId = any()) } returns true
    every { user.canDeleteLayerData(any()) } returns true

    insertSiteData()
    insertLayer(id = layerId.value, siteId = siteId.value, layerType = LayerType.PlantsPlanted)
    insertFeature(id = featureId.value, layerId = layerId.value, shapeType = ShapeType.Point)
  }

  fun createPlant(row: PlantsRow): PlantsRow {
    return store.createPlant(row.featureId!!, row)
  }

  fun updatePlant(row: PlantsRow): PlantsRow {
    return store.updatePlant(row.featureId!!, row)
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
    val plant = createPlant(validCreateRequest)
    assertEquals(plant.createdTime, time1)
    assertEquals(plant.modifiedTime, time1)
    assertEquals(validCreateRequest.copy(createdTime = time1, modifiedTime = time1), plant)
    assertEquals(plant, plantsDao.fetchOneByFeatureId(validCreateRequest.featureId!!))
  }

  @Test
  fun `create throws IllegalArgumentException if feature id arguments do not match`() {
    assertThrows<IllegalArgumentException> {
      store.createPlant(nonExistentFeatureId, validCreateRequest)
    }
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't have create permission`() {
    every { user.canCreateLayerData(featureId = any()) } returns false
    assertThrows<AccessDeniedException> { createPlant(validCreateRequest) }
  }

  @Test
  fun `create fails with DataIntegrityViolationException if the label is an empty string`() {
    assertThrows<DataIntegrityViolationException> {
      createPlant(validCreateRequest.copy(label = ""))
    }
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
  fun `fetchPlantsList returns empty list when user doesn't have read permission`() {
    insertSeveralPlants(speciesIdsToCount)
    every { user.canReadLayerData(layerId = any()) } returns false
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
  fun `fetchPlantSummary applies min and max entered time filters when provided`() {
    insertSeveralPlants(speciesIdsToCount, enteredTime = time1)
    assertEquals(
        emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(layerId, minEnteredTime = time2))
    assertEquals(speciesIdsToCount, store.fetchPlantSummary(layerId, maxEnteredTime = time1))
    assertEquals(
        speciesIdsToCount,
        store.fetchPlantSummary(layerId, minEnteredTime = time1, maxEnteredTime = time1))
  }

  @Test
  fun `fetchPlantSummary returns an empty map if user doesn't have permission to read layer data`() {
    every { user.canReadLayerData(layerId = any()) } returns false
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
  fun `fetchPlant returns PlantsRow with timestamps`() {
    val plant = createPlant(validCreateRequest)
    val readResult = store.fetchPlant(plant.featureId!!)
    assertNotNull(readResult)
    assertEquals(time1, readResult!!.createdTime)
    assertEquals(time1, readResult.modifiedTime)
    assertEquals(plant, readResult)
  }

  @Test
  fun `fetchPlant returns null if user doesn't have read permission, even if they have create permission`() {
    val plant = createPlant(validCreateRequest)
    every { user.canReadLayerData(featureId = any()) } returns false
    assertNull(store.fetchPlant(plant.featureId!!))
  }

  @Test
  fun `fetchPlant returns null if plant doesn't exist`() {
    createPlant(validCreateRequest)
    assertNull(store.fetchPlant(nonExistentFeatureId))
  }

  @Test
  fun `update fails with PlantNotFoundException if user doesn't have update permission`() {
    val plant = createPlant(validCreateRequest)
    every { user.canUpdateLayerData(featureId = any()) } returns false
    assertThrows<PlantNotFoundException> { updatePlant(plant) }
  }

  @Test
  fun `update fails with PlantNotFoundException if feature id doesn't exist`() {
    val plant = createPlant(validCreateRequest)
    assertThrows<PlantNotFoundException> {
      updatePlant(plant.copy(featureId = nonExistentFeatureId))
    }
  }

  @Test
  fun `update fails with DataIntegrityViolationException if the label is an empty string`() {
    val plant = createPlant(validCreateRequest)
    assertThrows<DataIntegrityViolationException> { updatePlant(plant.copy(label = "")) }
  }
}
