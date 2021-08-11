package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.gis.model.LayerModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class LayerStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private lateinit var store: LayerStore

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private val siteId = SiteId(10)
  private val nonExistentLayerId = LayerId(404)
  private val validCreateRequestModel =
      LayerModel(
          siteId = siteId,
          layerTypeId = LayerType.Testing,
          tileSetName = "test name",
          proposed = false,
          hidden = false)

  @BeforeEach
  fun init() {
    store = LayerStore(dslContext, clock)
    every { clock.instant() } returnsMany listOf(time1, time2)
    every { user.canCreateLayer(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canUpdateLayer(any()) } returns true
    every { user.canDeleteLayer(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `create returns model with populated layer_id, deleted, created_time,& modified_time fields`() {
    val responseModel: LayerModel = store.createLayer(validCreateRequestModel)
    assertNotNull(responseModel.layerTypeId)
    assertEquals(false, responseModel.deleted)
    assertEquals(time1.toString(), responseModel.createdTime)
    assertEquals(time1.toString(), responseModel.modifiedTime)
  }

  @Test
  fun `create does permissions check`() {
    store.createLayer(validCreateRequestModel)
    verify { user.canCreateLayer(siteId) }
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't have permission`() {
    every { user.canCreateLayer(any()) } returns false
    assertThrows(AccessDeniedException::class.java) { store.createLayer(validCreateRequestModel) }
  }

  @Test
  fun `create fails with IllegalArgumentException if user passes empty string for tile_set_name`() {
    assertThrows(DataIntegrityViolationException::class.java) {
      store.createLayer(
          LayerModel(
              siteId = siteId,
              layerTypeId = LayerType.Testing,
              tileSetName = "",
              proposed = false,
              hidden = false))
    }
  }

  @Test
  fun `read returns layerModel with all fields populated, including ones that won't be passed in the API`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    val fetchedLayer: LayerModel? = store.fetchLayer(layer.id!!)
    assertNotNull(fetchedLayer)
    assertEquals(layer, fetchedLayer)
    assertNotNull(fetchedLayer!!.deleted)
    assertEquals(time1.toString(), fetchedLayer.createdTime)
    assertEquals(time1.toString(), fetchedLayer.modifiedTime)
  }

  @Test
  fun `read does permission checks`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.fetchLayer(layer.id!!)
    verify { user.canReadLayer(layer.siteId!!) }
  }

  @Test
  fun `read returns null if user doesn't have access to the site`() {
    every { user.canReadLayer(any()) } returns false
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    assertNull(store.fetchLayer(layer.id!!))
  }

  @Test
  fun `read returns null if layer doesn't exist`() {
    assertNull(store.fetchLayer(nonExistentLayerId))
  }

  @Test
  fun `read returns null if the layer is deleted`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertNull(store.fetchLayer(layer.id!!))
  }

  @Test
  fun `update returns model with deleted == false and updated created_time, modified_time`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    val updatedLayer: LayerModel =
        store.updateLayer(
            LayerModel(
                id = layer.id,
                siteId = siteId,
                layerTypeId = LayerType.Testing,
                tileSetName = "new test name",
                proposed = false,
                hidden = false))
    assertEquals(false, updatedLayer.deleted)
    assertEquals(time1.toString(), updatedLayer.createdTime)
    assertEquals(time2.toString(), updatedLayer.modifiedTime)
  }

  @Test
  fun `update does permissions checks`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.updateLayer(validCreateRequestModel.copy(id = layer.id))
    verify { user.canUpdateLayer(layer.siteId!!) }
  }

  @Test
  fun `update fails with LayerNotFoundException if user doesn't have access to the site`() {
    every { user.canUpdateLayer(any()) } returns false
    val layer: LayerModel = store.createLayer(validCreateRequestModel)

    assertThrows(LayerNotFoundException::class.java) {
      store.updateLayer(validCreateRequestModel.copy(id = layer.id))
    }
    assertEquals(layer, store.fetchLayer(layer.id!!))
  }

  @Test
  fun `update does not change modified time if updated layer is the same as current layer`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    val updatedLayer: LayerModel = store.updateLayer(validCreateRequestModel.copy(id = layer.id))
    assertEquals(layer.modifiedTime, updatedLayer.modifiedTime)
  }

  @Test
  fun `update fails with LayerNotFoundException if layer doesn't exist`() {
    assertThrows(LayerNotFoundException::class.java) {
      store.createLayer(validCreateRequestModel)
      store.updateLayer(
          LayerModel(
              id = nonExistentLayerId,
              siteId = siteId,
              layerTypeId = LayerType.Testing,
              tileSetName = "test name",
              proposed = false,
              hidden = false))
    }
  }

  @Test
  fun `update fails with LayerNotFoundException if layer is deleted`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertThrows(LayerNotFoundException::class.java) { store.updateLayer(layer) }
  }

  @Test
  fun `delete returns deleted layer with updated deleted and modified_time fields`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    val deletedLayer: LayerModel = store.deleteLayer(layer.id!!)
    assertNotEquals(layer.modifiedTime, deletedLayer.modifiedTime)
    assertEquals(layer.copy(deleted = true, modifiedTime = deletedLayer.modifiedTime), deletedLayer)
  }

  @Test
  fun `delete sets the "delete" field on the resource, but does not delete the row`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    val deletedLayer: LayerModel = store.fetchLayer(layer.id!!, skipDeleted = false)!!
    assertNotEquals(layer.modifiedTime, deletedLayer.modifiedTime)
    assertEquals(
        layer.copy(deleted = true, modifiedTime = deletedLayer.modifiedTime!!), deletedLayer)
  }

  @Test
  fun `delete does permission checks`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    verify { user.canDeleteLayer(layer.siteId!!) }
  }

  @Test
  fun `delete fails with LayerNotFoundException if user does not have permission`() {
    every { user.canDeleteLayer(any()) } returns false
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    assertThrows(LayerNotFoundException::class.java) { store.deleteLayer(layer.id!!) }
    assertEquals(store.fetchLayer(layer.id!!), layer)
  }

  @Test
  fun `delete fails with LayerNotFoundException if layer is already deleted`() {
    val layer: LayerModel = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertThrows(LayerNotFoundException::class.java) { store.deleteLayer(layer.id!!) }
  }
}
