package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.ThumbnailId
import com.terraformation.backend.db.asGeoJson
import com.terraformation.backend.db.mercatorPoint
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.FeaturePhotosDao
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.pojos.FeaturePhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.gis.model.FeatureModel
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.Instant
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

internal class FeatureStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockk<UserModel>()
  private val siteId = SiteId(10)
  private val layerId = LayerId(100)
  private val nonExistentLayerId = LayerId(401)
  private val nonExistentFeatureId = FeatureId(400)
  private val photoId = PhotoId(30)
  private val plantObservationId = PlantObservationId(20)
  private val thumbnailId = ThumbnailId(40)
  private val storageUrl = URI("file:///x")
  private val validCreateRequest =
      FeatureModel(
          layerId = layerId, geom = mercatorPoint(1.0, 2.0, 120000.0), notes = "Great view up here")

  private val clock = mockk<Clock>()
  private val time1 = Instant.EPOCH
  private val time2 = time1.plusSeconds(1)

  private val fileStore: FileStore = mockk()
  private val thumbnailStore: ThumbnailStore = mockk()

  private lateinit var store: FeatureStore
  private lateinit var featurePhotosDao: FeaturePhotosDao
  private lateinit var featuresDao: FeaturesDao
  private lateinit var plantsDao: PlantsDao
  private lateinit var plantObservationsDao: PlantObservationsDao
  private lateinit var photosDao: PhotosDao
  private lateinit var thumbnailsDao: ThumbnailsDao

  @BeforeEach
  fun init() {
    val jooqConfig = dslContext.configuration()
    featurePhotosDao = FeaturePhotosDao(jooqConfig)
    featuresDao = FeaturesDao(jooqConfig)
    plantsDao = PlantsDao(jooqConfig)
    plantObservationsDao = PlantObservationsDao(jooqConfig)
    photosDao = PhotosDao(jooqConfig)
    thumbnailsDao = ThumbnailsDao(jooqConfig)

    store =
        FeatureStore(
            clock,
            dslContext,
            featurePhotosDao,
            fileStore,
            photosDao,
            thumbnailStore,
            thumbnailsDao)

    every { clock.instant() } returns time1
    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { user.canCreateLayerData(featureId = any()) } returns true
    every { user.canCreateLayerData(layerId = any()) } returns true
    every { user.canReadLayerData(featureId = any()) } returns true
    every { user.canReadLayerData(layerId = any()) } returns true
    every { user.canUpdateLayerData(featureId = any()) } returns true
    every { user.canUpdateLayerData(layerId = any()) } returns true
    every { user.canDeleteLayerData(any()) } returns true

    insertSiteData()
    insertLayer(layerId.value, siteId.value, LayerType.Infrastructure)
  }

  @Test
  fun `create adds new row to database and returns populated FeatureModel`() {
    val featureModel = store.createFeature(validCreateRequest)
    assertNotNull(featureModel.id)
    assertEquals(
        validCreateRequest.copy(createdTime = time1, modifiedTime = time1),
        featureModel.copy(id = null))
    assertNotNull(featuresDao.fetchOneById(featureModel.id!!))
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
  fun `create fails with AccessDeniedException if user doesn't have create permission`() {
    every { user.canCreateLayerData(layerId = any()) } returns false
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
    every { user.canReadLayerData(featureId = any()) } returns false
    assertNull(store.fetchFeature(newFeature.id!!))
  }

  @Test
  fun `read returns null if feature doesn't exist`() {
    store.createFeature(validCreateRequest)
    assertNull(store.fetchFeature(nonExistentFeatureId))
  }

  @Test
  fun `list returns all features (sorted by id) that are associated with a layer`() {
    val featureIds = (1..5).map { store.createFeature(validCreateRequest).id!! }

    val otherLayerId = LayerId(200)
    insertLayer(otherLayerId.value, siteId.value, LayerType.Infrastructure)
    store.createFeature(validCreateRequest.copy(layerId = otherLayerId))

    val listResult = store.listFeatures(layerId)
    assertEquals(5, listResult.size)
    assertEquals(featureIds.sortedBy { it.value }, listResult.map { it.id })
  }

  @Test
  fun `list returns empty list if there are no features in the layer`() {
    assertEquals(emptyList<FeatureModel>(), store.listFeatures(layerId))
  }

  @Test
  fun `list limits number of results when limit is provided`() {
    val featureIds = (1..10).map { store.createFeature(validCreateRequest).id!! }
    val listResult = store.listFeatures(layerId, limit = 4)
    assertEquals(featureIds.sortedBy { it.value }.subList(0, 4), listResult.map { it.id })
  }

  @Test
  fun `list skips results when skip is provided`() {
    val featureIds = (1..15).map { store.createFeature(validCreateRequest).id!! }

    val listResult = store.listFeatures(layerId, skip = 5)
    assertEquals(featureIds.sortedBy { it.value }.subList(5, 15), listResult.map { it.id })
  }

  @Test
  fun `list applies limit and skip when they are provided`() {
    val featureIds = (1..15).map { store.createFeature(validCreateRequest).id!! }

    val listResult = store.listFeatures(layerId, limit = 5, skip = 5)
    assertEquals(featureIds.sortedBy { it.value }.subList(5, 10), listResult.map { it.id })
  }

  @Test
  fun `list returns empty list if user does not have read permissions`() {
    store.createFeature(validCreateRequest)
    every { user.canReadLayerData(layerId = any()) } returns false
    assertEquals(emptyList<FeatureModel>(), store.listFeatures(layerId))
  }

  @Test
  fun `list returns empty list if layer id is invalid`() {
    assertEquals(emptyList<FeatureModel>(), store.listFeatures(nonExistentLayerId))
  }

  @Test
  fun `update modifies database, returns FeatureModel with updated modified time`() {
    val feature = store.createFeature(validCreateRequest)
    val newGeom = mercatorPoint(101.2345, -500.1, 10123.45)
    every { clock.instant() } returns time2
    val updatedFeature = store.updateFeature(feature.copy(geom = newGeom))
    assertEquals(feature.copy(geom = newGeom, modifiedTime = time2), updatedFeature)
    assertEquals(newGeom, featuresDao.fetchOneById(feature.id!!)?.geom)
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
    every { user.canUpdateLayerData(layerId = any()) } returns false
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
    val storageUrl = URI("file:///foo")

    plantsDao.insert(PlantsRow(featureId = feature.id, createdTime = time1, modifiedTime = time1))
    plantObservationsDao.insert(
        PlantObservationsRow(
            id = plantObservationId,
            featureId = feature.id,
            timestamp = time1,
            createdTime = time1,
            modifiedTime = time1))
    photosDao.insert(
        PhotosRow(
            id = photoId,
            capturedTime = time1,
            fileName = "myphoto",
            storageUrl = storageUrl,
            contentType = "jpeg",
            size = 1000,
            createdTime = time1,
            modifiedTime = time1))
    featurePhotosDao.insert(
        FeaturePhotosRow(
            featureId = feature.id, photoId = photoId, plantObservationId = plantObservationId))
    thumbnailsDao.insert(
        ThumbnailsRow(
            contentType = MediaType.IMAGE_JPEG_VALUE,
            createdTime = time1,
            height = 20,
            id = thumbnailId,
            photoId = photoId,
            size = 1,
            storageUrl = URI("file:///thumb"),
            width = 20,
        ))

    justRun { fileStore.delete(storageUrl) }

    val deletedId = store.deleteFeature(feature.id!!)

    assertEquals(feature.id, deletedId)
    assertNull(store.fetchFeature(deletedId))
    assert(plantsDao.fetchByFeatureId(deletedId).isEmpty())
    assert(plantObservationsDao.fetchByFeatureId(deletedId).isEmpty())
    assert(featurePhotosDao.fetchByFeatureId(deletedId).isEmpty())
    assert(photosDao.fetchById(photoId).isEmpty())
    assert(thumbnailsDao.fetchById(thumbnailId).isEmpty())

    verify { fileStore.delete(storageUrl) }
  }

  @Test
  fun `delete returns success if photo file could not be removed from file store`() {
    val feature = store.createFeature(validCreateRequest)
    val storageUrl = URI("file:///foo")

    plantsDao.insert(PlantsRow(featureId = feature.id, createdTime = time1, modifiedTime = time1))
    plantObservationsDao.insert(
        PlantObservationsRow(
            id = plantObservationId,
            featureId = feature.id,
            timestamp = time1,
            createdTime = time1,
            modifiedTime = time1))
    photosDao.insert(
        PhotosRow(
            id = photoId,
            capturedTime = time1,
            fileName = "myphoto",
            storageUrl = storageUrl,
            contentType = "jpeg",
            size = 1000,
            createdTime = time1,
            modifiedTime = time1))
    featurePhotosDao.insert(
        FeaturePhotosRow(
            featureId = feature.id, photoId = photoId, plantObservationId = plantObservationId))

    every { fileStore.delete(storageUrl) } throws NoSuchFileException("Nope")

    val deletedId = store.deleteFeature(feature.id!!)

    assertEquals(feature.id, deletedId)
  }

  @Test
  fun `delete throws a FeatureNotFoundException if the user doesn't have delete permissions`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canDeleteLayerData(any()) } returns false
    assertThrows<FeatureNotFoundException> { store.deleteFeature(feature.id!!) }
  }

  @Test
  fun `delete throws a FeatureNotFoundException if the feature doesn't exist`() {
    store.createFeature(validCreateRequest)
    assertThrows<FeatureNotFoundException> { store.deleteFeature(nonExistentFeatureId) }
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

  @Test
  fun `feature geometry can be retrieved as server-rendered GeoJSON`() {
    store.createFeature(validCreateRequest)

    val expected =
        """{"type":"Point","crs":{"type":"name","properties":{"name":"EPSG:3857"}},"coordinates":[1,2,120000]}"""
    val actual = dslContext.select(FEATURES.GEOM.asGeoJson()).from(FEATURES).fetchOne()?.value1()

    assertEquals(expected, actual)
  }

  @Test
  fun `createPhoto writes file and database rows`() {
    val photoData = byteArrayOf(1, 2, 3, 4, 5)
    val inputStream = photoData.inputStream()
    val photosRow =
        PhotosRow(
            capturedTime = Instant.EPOCH,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            fileName = "foo.jpg",
            heading = 1.0,
            gpsHorizAccuracy = 3.0,
            gpsVertAccuracy = 4.0,
            location = mercatorPoint(-1.0, -2.0, -3.0),
            orientation = 2.1,
            size = photoData.size.toLong(),
        )

    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!

    justRun { fileStore.write(any(), any(), any()) }

    val photoId = store.createPhoto(featureId, photosRow, inputStream)

    verify { fileStore.write(storageUrl, inputStream, photoData.size.toLong()) }

    val featurePhotosRow = featurePhotosDao.fetchByFeatureId(featureId)[0]
    val actualPhotosRow = photosDao.fetchOneById(featurePhotosRow.photoId!!)

    assertEquals(
        photosRow.copy(
            id = photoId, createdTime = null, modifiedTime = null, storageUrl = storageUrl),
        actualPhotosRow?.copy(createdTime = null, modifiedTime = null))
  }

  @Test
  fun `createPhoto throws exception if user has no permission to create layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!

    every { user.canCreateLayerData(featureId) } returns false

    assertThrows<FeatureNotFoundException> {
      store.createPhoto(
          featureId,
          PhotosRow(contentType = MediaType.IMAGE_JPEG_VALUE, size = 1),
          byteArrayOf(1).inputStream())
    }
  }

  @Test
  fun `deletePhoto deletes file and database rows`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)
    val storageUrl = photosRow.storageUrl!!

    justRun { fileStore.delete(any()) }

    store.deletePhoto(featureId, photosRow.id!!)

    assertEquals(emptyList<PhotosRow>(), photosDao.findAll())
    assertEquals(emptyList<FeaturePhotosRow>(), featurePhotosDao.findAll())

    verify { fileStore.delete(storageUrl) }
  }

  @Test
  fun `deletePhoto throws exception if user has no permission to delete layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    every { user.canDeleteLayerData(featureId) } returns false

    assertThrows<AccessDeniedException> { store.deletePhoto(featureId, photosRow.id!!) }
  }

  @Test
  fun `getPhotoData returns photo data`() {
    val data = byteArrayOf(1, 2, 3)
    val inputStream = data.inputStream()
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    val sizedInputStream = SizedInputStream(inputStream, data.size.toLong())
    every { fileStore.read(photosRow.storageUrl!!) } returns sizedInputStream

    val actualStream = store.getPhotoData(featureId, photosRow.id!!)

    assertSame(sizedInputStream, actualStream)
  }

  @Test
  fun `getPhotoData returns thumbnail if photo dimensions are specified`() {
    val data = byteArrayOf(1, 2, 3)
    val inputStream = data.inputStream()
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)
    val width = 40
    val height = 30

    val sizedInputStream = SizedInputStream(inputStream, data.size.toLong())
    every { thumbnailStore.getThumbnailData(photosRow.id!!, width, height) } returns
        sizedInputStream

    val actualStream = store.getPhotoData(featureId, photosRow.id!!, width, height)

    assertSame(sizedInputStream, actualStream)
  }

  @Test
  fun `getPhotoData throws exception if user has no permission to read layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    every { user.canReadLayerData(featureId) } returns false

    assertThrows<PhotoNotFoundException> { store.getPhotoData(featureId, photosRow.id!!) }
  }

  @Test
  fun `getPhotoData throws exception if photo does not exist on feature`() {
    val feature1 = store.createFeature(validCreateRequest)
    val feature2 = store.createFeature(validCreateRequest)
    val photosRow = insertFeaturePhoto(feature1.id!!)

    assertThrows<PhotoNotFoundException> { store.getPhotoData(feature2.id!!, photosRow.id!!) }
  }

  @Test
  fun `getPhotoMetadata returns photo metadata`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    val actualRow = store.getPhotoMetadata(featureId, photosRow.id!!)

    assertEquals(photosRow, actualRow)
  }

  @Test
  fun `getPhotoMetadata throws exception if user has no permission to read layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    every { user.canReadLayerData(featureId) } returns false

    assertThrows<PhotoNotFoundException> { store.getPhotoMetadata(featureId, photosRow.id!!) }
  }

  @Test
  fun `getPhotoMetadata throws exception if photo does not exist on feature`() {
    val feature1 = store.createFeature(validCreateRequest)
    val feature2 = store.createFeature(validCreateRequest)
    val photosRow = insertFeaturePhoto(feature1.id!!)

    assertThrows<PhotoNotFoundException> { store.getPhotoMetadata(feature2.id!!, photosRow.id!!) }
  }

  @Test
  fun `listPhotos returns list of feature photos`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow1 = insertFeaturePhoto(featureId)
    val photosRow2 = insertFeaturePhoto(featureId)

    val actual = store.listPhotos(featureId)

    assertEquals(setOf(photosRow1, photosRow2), actual.toSet())
  }

  @Test
  fun `listPhotos throws exception if user has no permission to read layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!

    every { user.canReadLayerData(featureId) } returns false

    assertThrows<FeatureNotFoundException> { store.listPhotos(featureId) }
  }

  @Test
  fun `listPhotos throws exception if feature does not exist`() {
    assertThrows<FeatureNotFoundException> { store.listPhotos(FeatureId(1)) }
  }

  private fun insertFeaturePhoto(featureId: FeatureId): PhotosRow {
    val photosRow =
        PhotosRow(
            capturedTime = Instant.EPOCH,
            createdTime = Instant.EPOCH,
            contentType = MediaType.IMAGE_JPEG_VALUE,
            fileName = "foo",
            modifiedTime = Instant.EPOCH,
            size = 1,
            storageUrl = URI("file:///${Random.nextLong()}"),
        )

    photosDao.insert(photosRow)
    featurePhotosDao.insert(FeaturePhotosRow(featureId = featureId, photoId = photosRow.id))
    return photosRow
  }
}
