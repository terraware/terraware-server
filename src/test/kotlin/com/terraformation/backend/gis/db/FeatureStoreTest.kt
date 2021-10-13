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
import com.terraformation.backend.db.assertPointsEqual
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
  // Spherical Mercator coordinates X=261845.71, Y=6250564.35, Z=35.0
  // map to longitude = 2.3522, latitude = 48.8566, elevation = 35.0
  private val sphericalMercatorPoint = mercatorPoint(261845.71, 6250564.35, 35.0)
  private val longLatPoint = newPoint(2.3522, 48.8566, 35.0, SRID.LONG_LAT)
  private val photoId = PhotoId(30)
  private val plantObservationId = PlantObservationId(20)
  private val thumbnailId = ThumbnailId(40)
  private val storageUrl = URI("file:///x")
  private val validCreateRequest =
      FeatureModel(
          layerId = layerId,
          geom = newPoint(60.8, -45.6, 0.0, SRID.LONG_LAT),
          notes = "Great view up here")

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
    every { user.canCreateFeatureData(any()) } returns true
    every { user.canCreateLayerData(any()) } returns true
    every { user.canReadFeature(any()) } returns true
    every { user.canReadFeaturePhoto(any()) } returns true
    every { user.canReadLayerData(any()) } returns true
    every { user.canUpdateFeature(any()) } returns true
    every { user.canUpdateFeatureData(any()) } returns true
    every { user.canUpdateLayerData(any()) } returns true
    every { user.canDeleteFeature(any()) } returns true
    every { user.canDeleteFeatureData(any()) } returns true

    insertSiteData()
    insertLayer(layerId.value, siteId.value, LayerType.Infrastructure)
  }

  @Test
  fun `create adds new row to database and returns populated FeatureModel`() {
    val featureModel = store.createFeature(validCreateRequest)
    assertNotNull(featureModel.id)
    assertPointsEqual(validCreateRequest.geom, featureModel.geom)
    assertEquals(
        validCreateRequest.copy(createdTime = time1, modifiedTime = time1),
        featureModel.copy(id = null, geom = validCreateRequest.geom))
    assertNotNull(featuresDao.fetchOneById(featureModel.id!!))
  }

  @Test
  fun `create always returns coordinates in LongLat (srid = 4326) even if they are provided in another coordinate system`() {
    val featureModel = store.createFeature(validCreateRequest.copy(geom = sphericalMercatorPoint))
    assertPointsEqual(longLatPoint, featureModel.geom)
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
  fun `read returns coordinates in LongLat (srid = 4326)`() {
    val newFeature = store.createFeature(validCreateRequest.copy(geom = sphericalMercatorPoint))
    val fetchedFeature = store.fetchFeature(newFeature.id!!)
    assertPointsEqual(longLatPoint, fetchedFeature!!.geom!!)
  }

  @Test
  fun `read returns null if user doesn't have read permission, even if they have create permission`() {
    val newFeature = store.createFeature(validCreateRequest)
    every { user.canReadFeature(any()) } returns false
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
  fun `count returns 0 if layer id is invalid`() {
    assertEquals(0, store.countFeatures(nonExistentLayerId))
  }

  @Test
  fun `count returns 0 if user does not have read permissions`() {
    store.createFeature(validCreateRequest)
    every { user.canReadLayerData(layerId = any()) } returns false
    assertEquals(0, store.countFeatures(layerId))
  }

  @Test
  fun `count returns total number of features in layer`() {
    repeat(5) { store.createFeature(validCreateRequest) }

    val otherLayerId = LayerId(200)
    insertLayer(otherLayerId.value, siteId.value, LayerType.Infrastructure)
    store.createFeature(validCreateRequest.copy(layerId = otherLayerId))

    assertEquals(5, store.countFeatures(layerId))
  }

  @Test
  fun `update modifies database, returns FeatureModel with updated modified time`() {
    val feature = store.createFeature(validCreateRequest)
    val newAttrib = "Brand new attrib"
    every { clock.instant() } returns time2
    val updatedFeature = store.updateFeature(feature.copy(attrib = newAttrib))
    assertEquals(feature.copy(attrib = newAttrib, modifiedTime = time2), updatedFeature)
  }

  @Test
  fun `update returns coordinates in LongLat (srid = 4326) even if they are provided in another coordinate system`() {
    val feature = store.createFeature(validCreateRequest)
    val updatedFeature = store.updateFeature(feature.copy(geom = sphericalMercatorPoint))
    assertPointsEqual(longLatPoint, updatedFeature.geom)
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
    every { user.canUpdateFeatureData(any()) } returns false
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
  fun `delete throws AccessDeniedException if the user doesn't have delete permission`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canDeleteFeature(any()) } returns false
    assertThrows<AccessDeniedException> { store.deleteFeature(feature.id!!) }
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

    // We can't do approximate equality on the floating-point values in the GeoJSON, but there may
    // be floating-point inaccuracies in the coordinate conversion. Match the coordinate values
    // using regexes as a rough equivalent.
    val pattern =
        """\{"type":"Point","crs":\{"type":"name","properties":\{"name":"EPSG:3857"}}""" +
            ""","coordinates":\[6768225\.04023\d+,-5716479\.01532\d+,0]}"""
    val expected = Regex(pattern)
    val actual = dslContext.select(FEATURES.GEOM.asGeoJson()).from(FEATURES).fetchOne()?.value1()!!

    if (!expected.matches(actual)) {
      // This will give us a useful assertion message if the regex doesn't match.
      assertEquals(pattern, actual, "GeoJSON value did not match regular expression")
    }
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

    every { user.canCreateFeatureData(featureId) } returns false

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

    every { user.canDeleteFeatureData(featureId) } returns false

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
  fun `getPhotoData throws exception if user has no permission to read photo`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)
    val photoId = photosRow.id!!

    every { user.canReadFeaturePhoto(photoId) } returns false

    assertThrows<PhotoNotFoundException> { store.getPhotoData(featureId, photoId) }
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
  fun `getPhotoMetadata throws exception if user has no permission to read photo`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)
    val photoId = photosRow.id!!

    every { user.canReadFeaturePhoto(photoId) } returns false

    assertThrows<PhotoNotFoundException> { store.getPhotoMetadata(featureId, photoId) }
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
  fun `listPhotos throws exception if user has no permission to read feature`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!

    every { user.canReadFeature(featureId) } returns false

    assertThrows<FeatureNotFoundException> { store.listPhotos(featureId) }
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
