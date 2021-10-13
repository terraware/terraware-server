package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.gis.model.LayerModel
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class LayerStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private lateinit var store: LayerStore
  private lateinit var layersDao: LayersDao

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private val siteId = SiteId(10)
  private val nonExistentSiteId = SiteId(200)
  private val nonExistentLayerId = LayerId(1234567)
  private val validCreateRequestModel =
      LayerModel(
          siteId = siteId,
          layerType = LayerType.Infrastructure,
          tileSetName = "test name",
          proposed = false,
          hidden = false)

  @BeforeEach
  fun init() {
    layersDao = LayersDao(dslContext.configuration())
    store = LayerStore(clock, dslContext)
    every { clock.instant() } returns time1
    every { user.canCreateLayer(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canUpdateLayer(any()) } returns true
    every { user.canDeleteLayer(any()) } returns true
    every { user.canReadSite(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `create adds new row to the database and returns populated LayerModel`() {
    val layer = store.createLayer(validCreateRequestModel)
    assertNotNull(layer.id)
    assertEquals(false, layer.deleted)
    assertEquals(time1, layer.createdTime)
    assertEquals(time1, layer.modifiedTime)
    assertNotNull(layersDao.fetchOneById(layer.id!!))
  }

  @Test
  fun `create fails with AccessDeniedException if user doesn't have permission`() {
    every { user.canCreateLayer(any()) } returns false
    assertThrows<AccessDeniedException> { store.createLayer(validCreateRequestModel) }
  }

  @Test
  fun `create fails with DataIntegrityViolationException if passed an empty string for tile_set_name`() {
    assertThrows<DataIntegrityViolationException> {
      store.createLayer(
          LayerModel(
              siteId = siteId,
              layerType = LayerType.Infrastructure,
              tileSetName = "",
              proposed = false,
              hidden = false))
    }
  }

  @Test
  fun `read returns LayerModel with all fields populated, including ones that won't be returned in the API`() {
    val layer = store.createLayer(validCreateRequestModel)
    val fetchedLayer: LayerModel? = store.fetchLayer(layer.id!!)
    assertNotNull(fetchedLayer)
    assertEquals(layer, fetchedLayer)
    assertNotNull(fetchedLayer!!.deleted)
    assertEquals(time1, fetchedLayer.createdTime)
    assertEquals(time1, fetchedLayer.modifiedTime)
  }

  @Test
  fun `read returns null if user doesn't have read permissions, even if they have write permissions`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { user.canReadLayer(any()) } returns false
    assertNull(store.fetchLayer(layer.id!!))
  }

  @Test
  fun `read returns null if layer doesn't exist`() {
    assertNull(store.fetchLayer(nonExistentLayerId))
  }

  @Test
  fun `read returns null if the layer is deleted`() {
    val layer = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertNull(store.fetchLayer(layer.id!!))
  }

  @Test
  fun `update returns LayerModel with updated modified_time and deleted = false`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { clock.instant() } returns time2
    val updatedLayer =
        store.updateLayer(
            LayerModel(
                id = layer.id,
                siteId = siteId,
                layerType = LayerType.Infrastructure,
                tileSetName = "new test name",
                proposed = false,
                hidden = false))
    assertEquals(false, updatedLayer.deleted)
    assertEquals(layer.createdTime, updatedLayer.createdTime)
    assertEquals(time2, updatedLayer.modifiedTime)
  }

  @Test
  fun `list returns all layers associated with a site id`() {
    val layerIds = (1..3).map { store.createLayer(validCreateRequestModel).id!! }

    val otherSite = SiteId(20)
    insertSite(id = otherSite.value, projectId = 2)
    store.createLayer(validCreateRequestModel.copy(siteId = otherSite))

    val listResult = store.listLayers(siteId)
    assertEquals(3, listResult.size)
    assertEquals(layerIds, listResult.map { it.id })
  }

  @Test
  fun `list returns empty list when there are no layers associated with the site`() {
    assertEquals(emptyList<LayerId>(), store.listLayers(siteId))
  }

  @Test
  fun `list does not return deleted layers`() {
    val layer = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertEquals(emptyList<LayerId>(), store.listLayers(siteId))
  }

  @Test
  fun `list returns empty list if user doesn't have read permission on site`() {
    every { user.canReadSite(any()) } returns false
    repeat(3) { store.createLayer(validCreateRequestModel) }
    assertEquals(emptyList<LayerId>(), store.listLayers(siteId))
  }

  @Test
  fun `list returns empty list if site id is invalid`() {
    store.createLayer(validCreateRequestModel)
    assertEquals(emptyList<LayerId>(), store.listLayers(nonExistentSiteId))
  }

  @Test
  fun `update modifies database`() {
    val layer = store.createLayer(validCreateRequestModel)
    val newTileSetName = "update modifies db test"
    store.updateLayer(layer.copy(tileSetName = newTileSetName))
    assertEquals(newTileSetName, layersDao.fetchOneById(layer.id!!)?.tileSetName)
  }

  @Test
  fun `update does not change modified time if updated layer is the same as current layer`() {
    val layer = store.createLayer(validCreateRequestModel)
    val updatedLayer = store.updateLayer(validCreateRequestModel.copy(id = layer.id))
    assertEquals(layer.modifiedTime, updatedLayer.modifiedTime)
  }

  @Test
  fun `update fails with LayerNotFoundException if user doesn't have update permissions`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { user.canUpdateLayer(any()) } returns false
    assertThrows<LayerNotFoundException> {
      store.updateLayer(validCreateRequestModel.copy(id = layer.id))
    }
  }

  @Test
  fun `update fails with LayerNotFoundException if layer id doesn't exist`() {
    store.createLayer(validCreateRequestModel)
    assertThrows<LayerNotFoundException> {
      store.updateLayer(
          LayerModel(
              id = nonExistentLayerId,
              siteId = siteId,
              layerType = LayerType.Infrastructure,
              tileSetName = "test name",
              proposed = false,
              hidden = false))
    }
  }

  @Test
  fun `update fails if the given layer id is valid, but the site id passed in does not match the site id in the database`() {
    val layer = store.createLayer(validCreateRequestModel)
    assertThrows<LayerNotFoundException> {
      store.updateLayer(
          LayerModel(
              id = layer.id,
              siteId = nonExistentSiteId,
              layerType = LayerType.Infrastructure,
              tileSetName = "Test name",
              proposed = false,
              hidden = false))
    }
  }

  @Test
  fun `update fails with LayerNotFoundException if layer is deleted`() {
    val layer = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertThrows<LayerNotFoundException> {
      store.updateLayer(validCreateRequestModel.copy(layer.id!!))
    }
  }

  @Test
  fun `delete returns deleted layer with updated deleted and modified_time fields`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { clock.instant() } returns time2
    val deletedLayer: LayerModel = store.deleteLayer(layer.id!!)
    assertNotEquals(layer.modifiedTime, deletedLayer.modifiedTime)
    assertEquals(layer.copy(deleted = true, modifiedTime = deletedLayer.modifiedTime), deletedLayer)
  }

  @Test
  fun `delete sets the 'modified_time' and 'deleted' fields on the resource, but does not delete the row in the database`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { clock.instant() } returns time2
    store.deleteLayer(layer.id!!)
    val deletedLayer: LayerModel = store.fetchLayer(layer.id!!, ignoreDeleted = false)!!
    assertNotEquals(layer.modifiedTime, deletedLayer.modifiedTime)
    assertTrue(deletedLayer.deleted!!)
    assertEquals(
        layer.copy(deleted = true, modifiedTime = deletedLayer.modifiedTime!!), deletedLayer)
  }

  @Test
  fun `delete fails with LayerNotFoundException if user does not have permission`() {
    val layer = store.createLayer(validCreateRequestModel)
    every { user.canDeleteLayer(any()) } returns false
    assertThrows<LayerNotFoundException> { store.deleteLayer(layer.id!!) }
    // record exists, user just didn't have permission to delete it
    assertEquals(store.fetchLayer(layer.id!!), layer)
  }

  @Test
  fun `delete fails with LayerNotFoundException if layer is already deleted`() {
    val layer = store.createLayer(validCreateRequestModel)
    store.deleteLayer(layer.id!!)
    assertThrows<LayerNotFoundException> { store.deleteLayer(layer.id!!) }
  }
}
