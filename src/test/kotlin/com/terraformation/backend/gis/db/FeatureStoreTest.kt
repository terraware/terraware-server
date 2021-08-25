package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.ShapeType
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.ThumbnailId
import com.terraformation.backend.db.mercatorPoint
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.ThumbnailDao
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.pojos.ThumbnailRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.transformSrid
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
  private val nonExistentFeatureId = FeatureId(400)
  private val layerId = LayerId(100)
  private val photoId = PhotoId(30)
  private val nonExistentLayerId = LayerId(401)
  private val siteId = SiteId(10)
  private val thumbnailId = ThumbnailId(40)
  private val validCreateRequest =
      FeatureModel(
          layerId = layerId,
          shapeType = ShapeType.Point,
          geom = mercatorPoint(1.0, 2.0, 120000.0),
          notes = "Great view up here")

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private lateinit var store: FeatureStore
  private lateinit var plantsDao: PlantsDao
  private lateinit var plantObservationsDao: PlantObservationsDao
  private lateinit var photosDao: PhotosDao
  private lateinit var thumbnailDao: ThumbnailDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    plantsDao = PlantsDao(jooqConfig)
    plantObservationsDao = PlantObservationsDao(jooqConfig)
    photosDao = PhotosDao(jooqConfig)
    thumbnailDao = ThumbnailDao(jooqConfig)

    store = FeatureStore(clock, dslContext, photosDao, thumbnailDao)
    every { clock.instant() } returns time1
    every { user.canCreateLayerData(any()) } returns true
    every { user.canReadLayerData(any()) } returns true
    every { user.canUpdateLayerData(any()) } returns true
    every { user.canDeleteLayerData(any()) } returns true

    insertSiteData()
    insertLayer(layerId.value, siteId.value, LayerType.Infrastructure)
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
  fun `create converts coordinates from other coordinate systems`() {
    // GPS coordinates longitude=10, latitude=20, elevation=30 map to spherical Mercator coordinates
    // X=1113194.9079327357, Y=2273030.926987688, Z=30
    val gpsCoordinates = newPoint(10.0, 20.0, 30.0, SRID.LONG_LAT)

    val featureModel = store.createFeature(validCreateRequest.copy(geom = gpsCoordinates))
    val actualCoordinates = featureModel.geom!!.firstPoint

    // Allow a small fuzz factor for the X and Y coordinates because they're computed using
    // floating-point math.
    assertEquals(1113194.9079327357, actualCoordinates.x, 0.0001, "X coordinate")
    assertEquals(2273030.926987688, actualCoordinates.y, 0.0001, "Y coordinate")
    assertEquals(30.0, actualCoordinates.z, "Z coordinate")
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
  fun `read returns null if user doesn't have read permission, even if they have create permission`() {
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
    val newGeom = mercatorPoint(101.2345, -500.1, 10123.45)
    every { clock.instant() } returns time2
    val updatedFeature = store.updateFeature(feature.copy(geom = newGeom))
    assertEquals(feature.copy(geom = newGeom, modifiedTime = time2), updatedFeature)
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
      store.updateFeature(feature.copy(gpsHorizAccuracy = 18.0))
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
  fun `delete removes the record and all associated feature data, returns the deleted FeatureId`() {
    val feature = store.createFeature(validCreateRequest)
    plantsDao.insert(PlantsRow(featureId = feature.id, createdTime = time1, modifiedTime = time1))
    plantObservationsDao.insert(
        PlantObservationsRow(
            id = 20,
            featureId = feature.id,
            timestamp = time1,
            createdTime = time1,
            modifiedTime = time1))
    photosDao.insert(
        PhotosRow(
            id = photoId,
            featureId = feature.id,
            plantObservationId = 20,
            capturedTime = time1,
            fileName = "myphoto",
            contentType = "jpeg",
            size = 1000,
            createdTime = time1,
            modifiedTime = time1))
    thumbnailDao.insert(
        ThumbnailRow(
            id = thumbnailId, photoId = photoId, fileName = "myphoto", width = 20, height = 20))

    val deletedId = store.deleteFeature(feature.id!!)

    assertEquals(feature.id, deletedId)
    assertNull(store.fetchFeature(deletedId))
    assert(plantsDao.fetchByFeatureId(deletedId).isEmpty())
    assert(plantObservationsDao.fetchByFeatureId(deletedId).isEmpty())
    assert(photosDao.fetchByFeatureId(deletedId).isEmpty())
    assert(thumbnailDao.fetchById(thumbnailId).isEmpty())
  }

  @Test
  fun `delete throws a FeatureNotFoundException if the user doesn't have delete permissions`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canDeleteLayerData(any()) } returns false
    assertThrows<FeatureNotFoundException> { store.deleteFeature(feature.id!!) }
  }

  @Test
  fun `feature geometry can be retrieved in alternate coordinate system`() {
    store.createFeature(
        validCreateRequest.copy(geom = newPoint(100000.0, 200000.0, 30.0, SRID.SPHERICAL_MERCATOR)))

    val actual =
        dslContext
            .select(FEATURES.GEOM.transformSrid(SRID.LONG_LAT))
            .from(FEATURES)
            .fetchOne()
            ?.value1()
            ?.firstPoint!!

    // select st_asewkt(st_transform('SRID=3857;POINT(100000 200000 30)', 4326));
    // SRID=4326;POINT(0.898315284119521 1.796336212099301 30)

    assertEquals(0.898315284119521, actual.x, 0.00000000001, "X coordinate")
    assertEquals(1.796336212099301, actual.y, 0.00000000001, "Y coordinate")
    assertEquals(30.0, actual.z, "Z coordinate")
    assertEquals(SRID.LONG_LAT, actual.srid, "SRID")
  }
}
