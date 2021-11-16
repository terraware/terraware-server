package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.HealthState
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.PlantObservationNotFoundException
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
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

internal class PlantObservationsStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private val siteId = SiteId(10)
  private val layerId = LayerId(100)
  private val featureId = FeatureId(1000)
  private val nonExistentFeatureId = FeatureId(400)
  private val nonExistentPlantObservationId = PlantObservationId(400)

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val clientTime = time1.minusSeconds(1)
  private val time2 = time1.plusSeconds(1)

  private val validCreateRequest =
      PlantObservationsRow(
          featureId = featureId,
          timestamp = clientTime,
          healthStateId = HealthState.Good,
          flowers = true,
          seeds = false,
          pests = "None",
          height = 2.0,
          dbh = 0.5,
      )

  private lateinit var store: PlantObservationsStore
  private lateinit var featuresDao: FeaturesDao
  private lateinit var layersDao: LayersDao
  private lateinit var plantsDao: PlantsDao
  private lateinit var observDao: PlantObservationsDao
  private lateinit var speciesDao: SpeciesDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()

    featuresDao = FeaturesDao(jooqConfig)
    layersDao = LayersDao(jooqConfig)
    plantsDao = PlantsDao(jooqConfig)
    observDao = PlantObservationsDao(jooqConfig)
    speciesDao = SpeciesDao(jooqConfig)

    store = PlantObservationsStore(clock, plantsDao, observDao)
    every { clock.instant() } returns time1
    every { user.canReadFeature(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canUpdateFeature(any()) } returns true

    insertSiteData()
    insertLayer(id = layerId, siteId = siteId, layerType = LayerType.PlantsPlanted)
    insertFeature(id = featureId, layerId = layerId)
    insertPlant(featureId = featureId)
  }

  @Test
  fun `create adds a new row to the Plant Observations table, returns server generated id and timestamps`() {
    val observation = store.create(validCreateRequest)
    assertNotNull(observation.id)
    assertEquals(
        validCreateRequest.copy(id = observation.id, createdTime = time1, modifiedTime = time1),
        observation)
    assertEquals(observation, observDao.fetchOneById(observation.id!!))
  }

  @Test
  fun `create does not modify row passed in, returns a different row instance`() {
    val requestCopy = validCreateRequest.copy()
    val response = store.create(validCreateRequest)
    assertTrue(requestCopy == validCreateRequest, "store does not alter ValidCreateRequest fields")
    assertFalse(
        response === validCreateRequest, "store returns different instance of PlantObservationsRow")
  }

  @Test
  fun `create ignores 'created' and 'modified' timestamps if they are set`() {
    val request = validCreateRequest.copy(createdTime = clientTime, modifiedTime = clientTime)
    val observation = store.create(request)
    assertEquals(time1, observation.createdTime)
    assertEquals(time1, observation.modifiedTime)
  }

  @Test
  fun `create throws IllegalArgumentException if feature id is null`() {
    assertThrows<IllegalArgumentException> {
      store.create(validCreateRequest.copy(featureId = null))
    }
  }

  @Test
  fun `create fails with FeatureNotFoundException if user doesn't have read permission`() {
    every { user.canUpdateFeature(any()) } returns false
    every { user.canReadFeature(any()) } returns false
    assertThrows<FeatureNotFoundException> { store.create(validCreateRequest) }
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't have create permission`() {
    every { user.canUpdateFeature(any()) } returns false
    assertThrows<AccessDeniedException> { store.create(validCreateRequest) }
  }

  @Test
  fun `create fails with IllegalArgumentException if plant doesn't exist`() {
    assertThrows<IllegalArgumentException> {
      store.create(validCreateRequest.copy(featureId = nonExistentFeatureId))
    }
  }

  @Test
  fun `create fails with DataIntegrityViolationException if 'pests' is an empty string`() {
    assertThrows<DataIntegrityViolationException> {
      store.create(validCreateRequest.copy(pests = ""))
    }
  }

  @Test
  fun `fetch returns PlantObservationsRow with timestamps`() {
    val observation = store.create(validCreateRequest)
    val fetched = store.fetch(observation.id!!)
    assertNotNull(fetched)
    assertEquals(time1, fetched!!.createdTime)
    assertEquals(time1, fetched.modifiedTime)
    assertEquals(observation, fetched)
  }

  @Test
  fun `fetch returns null if user doesn't have read permission`() {
    val observation = store.create(validCreateRequest)
    every { user.canReadFeature(any()) } returns false
    assertNull(store.fetch(observation.id!!))
  }

  @Test
  fun `fetch returns null if the plant observation doesn't exist`() {
    assertNull(store.fetch(nonExistentPlantObservationId))
  }

  @Test
  fun `fetchList returns list of PlantObservationRows`() {
    val observations = mutableListOf<PlantObservationsRow>()
    repeat(3) { observations.add(store.create(validCreateRequest)) }
    val fetched = store.fetchList(featureId)
    assertEquals(observations, fetched)
  }

  @Test
  fun `fetchList returns an empty list when there are no plant observations`() {
    assertEquals(emptyList<PlantObservationsRow>(), store.fetchList(featureId))
  }

  @Test
  fun `fetchList returns an empty list when user doesn't have read permission`() {
    store.create(validCreateRequest)
    every { user.canReadFeature(any()) } returns false
    assertEquals(emptyList<PlantObservationsRow>(), store.fetchList(featureId))
  }

  @Test
  fun `update changes the row in the database, returns row with updated timestamps`() {
    val created = store.create(validCreateRequest)
    every { clock.instant() } returns time2
    val plannedUpdate = created.copy(pests = "bugs in code")
    val updated = store.update(plannedUpdate)

    assertEquals(plannedUpdate.copy(createdTime = time1, modifiedTime = time2), updated)
    assertEquals(updated, observDao.fetchOneById(created.id!!))
  }

  @Test
  fun `update does not modify row passed in, returns a different row instance`() {
    val created = store.create(validCreateRequest)
    val plannedUpdate = created.copy(pests = "referential equality bugs in code")
    val plannedUpdateCopy = plannedUpdate.copy()
    val updated = store.update(plannedUpdate)

    assertTrue(
        plannedUpdateCopy == plannedUpdate,
        "instance passed as argument is not being modified by the plant store")
    assertFalse(
        plannedUpdate === updated,
        "the plant store does not return back the same instance it received")
  }

  @Test
  fun `update does not update timestamps if there's no change from the database`() {
    val created = store.create(validCreateRequest)
    val updated = store.update(created)
    assertEquals(created, updated)
  }

  @Test
  fun `update ignores created and modified timestamps if they are set`() {
    val created = store.create(validCreateRequest)
    val toUpdate = created.copy(pests = "bugs", createdTime = clientTime, modifiedTime = clientTime)
    every { clock.instant() } returns time2
    val updated = store.update(toUpdate)
    assertEquals(time1, updated.createdTime)
    assertEquals(time2, updated.modifiedTime)
  }

  @Test
  fun `update behavior doesn't change if feature id is null`() {
    val created = store.create(validCreateRequest)
    val response1 = store.update(created.copy(pests = "bugs"))
    val response2 = store.update(created.copy(pests = "bugs", featureId = null))
    // The two records will have different ids since id != feature id
    assertEquals(response1.copy(id = null), response2.copy(id = null))
  }

  @Test
  fun `update fails with IllegalArgumentException if plant observation id is null`() {
    assertThrows<IllegalArgumentException> { store.update(validCreateRequest.copy(id = null)) }
  }

  @Test
  fun `update fails with PlantObservationNotFoundException if user doesn't have update permission`() {
    val created = store.create(validCreateRequest)
    every { user.canUpdateFeature(any()) } returns false
    assertThrows<PlantObservationNotFoundException> { store.update(created) }
  }

  @Test
  fun `update fails with PlantObservationNotFoundException if observation doesn't exist`() {
    val created = store.create(validCreateRequest)
    assertThrows<PlantObservationNotFoundException> {
      store.update(created.copy(id = nonExistentPlantObservationId))
    }
  }

  @Test
  fun `update fails with IllegalArgumentException if user supplied feature id doesn't match database`() {
    val created = store.create(validCreateRequest)
    assertThrows<IllegalArgumentException> {
      store.update(created.copy(featureId = nonExistentFeatureId))
    }
  }

  @Test
  fun `update fails with DataIntegrityViolationException if 'pests' is an empty string`() {
    val created = store.create(validCreateRequest)
    assertThrows<DataIntegrityViolationException> { store.update(created.copy(pests = "")) }
  }
}
