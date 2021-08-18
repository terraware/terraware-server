package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.ShapeType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.gis.model.FeatureModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class FeatureStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private val nonExistentFeatureId = FeatureId(444)
  private val layerId = LayerId(333)
  private val nonExistentLayerId = LayerId(555)
  private val siteId = SiteId(10)
  private val validCreateRequest =
      FeatureModel(
          layerId = layerId,
          shapeType = ShapeType.Point,
          altitude = 120000.toDouble(),
          notes = "Great dirt up here")

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private lateinit var store: FeatureStore

  @BeforeEach
  fun init() {
    store = FeatureStore(clock, dslContext)
    every { clock.instant() } returns time1
    every { user.canCreateLayerData(any()) } returns true
    every { user.canReadLayerData(any()) } returns true
    every { user.canUpdateLayerData(any()) } returns true
    every { user.canDeleteLayerData(any()) } returns true

    insertSiteData()
    insertLayer(layerId, siteId, LayerType.Infrastructure, "test")
  }

  @Test
  fun `create returns FeatureModel with populated id, createdTime and modifiedTime`() {
    val featureModel = store.createFeature(validCreateRequest)
    assertNotNull(featureModel.id)
    assertEquals(time1, featureModel.createdTime)
    assertEquals(time1, featureModel.modifiedTime)
    assertEquals(
        validCreateRequest, featureModel.copy(id = null, createdTime = null, modifiedTime = null))
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't create permission`() {
    every { user.canCreateLayerData(any()) } returns false
    assertThrows<AccessDeniedException> { store.createFeature(validCreateRequest) }
  }

  @Test
  fun `create fails with DataIntegrityViolationException if empty strings are passed in`() {
    assertThrows<DataIntegrityViolationException> {
      store.createFeature(validCreateRequest.copy(attrib = "", notes = ""))
    }
  }

  @Test
  fun `read returns FeatureModel with all fields populated`() {
    val newFeature = store.createFeature(validCreateRequest)
    val fetchedFeature = store.fetchFeature(newFeature.id!!)
    assertNotNull(fetchedFeature)
    assertEquals(newFeature, fetchedFeature)
  }

  @Test
  fun `read returns null if user doesn't read permission, even if they have create permission`() {
    val newFeature = store.createFeature(validCreateRequest)
    every { user.canReadLayerData(any()) } returns false
    assertNull(store.fetchFeature(newFeature.id!!))
  }

  @Test
  fun `read returns null if feature doesn't exist`() {
    store.createFeature(validCreateRequest)
    assertNull(store.fetchFeature(nonExistentFeatureId))
  }

  @Test
  fun `update returns FeatureModel with updated modified time`() {
    val feature = store.createFeature(validCreateRequest)
    every { clock.instant() } returns time2
    val updatedFeature = store.updateFeature(feature.copy(altitude = 789.toDouble()))
    assertEquals(time2, updatedFeature.modifiedTime)
    assertEquals(
        feature.copy(altitude = 789.toDouble(), modifiedTime = updatedFeature.modifiedTime),
        updatedFeature)
  }

  @Test
  fun `update does not change modified time if updated feature is same as current feature`() {
    val feature = store.createFeature(validCreateRequest)
    val updatedFeature = store.updateFeature(feature)
    assertEquals(feature, updatedFeature)
  }

  @Test
  fun `update fails with FeatureNotFoundException if user doesn't have update access`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canUpdateLayerData(any()) } returns false
    assertThrows<FeatureNotFoundException> {
      store.updateFeature(feature.copy(altitude = 789.toDouble()))
    }
  }

  @Test
  fun `update fails with FeatureNotFoundException if feature id doesn't exist`() {
    val feature = store.createFeature(validCreateRequest)
    assertThrows<FeatureNotFoundException> {
      store.updateFeature(feature.copy(id = nonExistentFeatureId))
    }
  }

  @Test
  fun `update fails if feature id is valid but caller's layer id does not match the layer id in the database`() {
    val feature = store.createFeature(validCreateRequest)
    assertThrows<FeatureNotFoundException> {
      store.updateFeature(feature.copy(layerId = nonExistentLayerId))
    }
  }

  @Test
  fun `update fails with DataIntegrityViolationException if empty strings are passed in`() {
    val feature = store.createFeature(validCreateRequest)
    assertThrows<DataIntegrityViolationException> {
      store.updateFeature(feature.copy(attrib = "", notes = ""))
    }
  }

  @Test
  fun `delete removes the record and returns the delete FeatureId`() {
    val feature = store.createFeature(validCreateRequest)
    val deletedId = store.deleteFeature(feature.id!!)
    assertEquals(feature.id, deletedId)
    assertNull(store.fetchFeature(deletedId))
  }

  @Test
  fun `delete throws a FeatureNotFoundException if the user doesn't have delete permissions`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canDeleteLayerData(any()) } returns false
    assertThrows<FeatureNotFoundException> { store.deleteFeature(feature.id!!) }
  }
}
