package com.terraformation.backend.gis.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.PostgresFuzzySearchOperators
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.ThumbnailId
import com.terraformation.backend.db.asGeoJson
import com.terraformation.backend.db.assertPointsEqual
import com.terraformation.backend.db.mercatorPoint
import com.terraformation.backend.db.newPoint
import com.terraformation.backend.db.tables.daos.FeaturePhotosDao
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.pojos.FeaturePhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.pojos.ThumbnailsRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.gis.model.FeatureModel
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

internal class FeatureStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
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
            PostgresFuzzySearchOperators(),
            photosDao,
            plantsDao,
            thumbnailStore,
            thumbnailsDao)

    every { clock.instant() } returns time1
    every { fileStore.newUrl(any(), any(), any()) } returns storageUrl
    every { user.canCreateFeature(any()) } returns true
    every { user.canReadFeature(any()) } returns true
    every { user.canReadFeaturePhoto(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadSite(any()) } returns true
    every { user.canUpdateFeature(any()) } returns true
    every { user.canUpdateLayer(any()) } returns true
    every { user.canDeleteFeature(any()) } returns true
    every { user.canDeleteFeaturePhoto(any()) } returns true

    insertSiteData()
    insertLayer(layerId, siteId, LayerType.PlantsPlanted)
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
    every { user.canCreateFeature(any()) } returns false
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
    insertLayer(otherLayerId, siteId, LayerType.Infrastructure)
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
    every { user.canReadLayer(any()) } returns false
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
    every { user.canReadLayer(any()) } returns false
    assertEquals(0, store.countFeatures(layerId))
  }

  @Test
  fun `count returns total number of features in layer`() {
    repeat(5) { store.createFeature(validCreateRequest) }

    val otherLayerId = LayerId(200)
    insertLayer(otherLayerId, siteId, LayerType.Infrastructure)
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
  fun `update fails with FeatureNotFoundException if user doesn't have read access`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canUpdateFeature(any()) } returns false
    every { user.canReadFeature(any()) } returns false
    assertThrows<FeatureNotFoundException> {
      store.updateFeature(feature.copy(gpsHorizAccuracy = 18.0))
    }
  }

  @Test
  fun `update fails with AccessDeniedException if user doesn't have update access`() {
    val feature = store.createFeature(validCreateRequest)
    every { user.canUpdateFeature(any()) } returns false
    assertThrows<AccessDeniedException> {
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
  fun `update ignores layer id`() {
    val feature = store.createFeature(validCreateRequest)
    store.updateFeature(feature.copy(layerId = nonExistentLayerId))
    val updatedLayerId = store.fetchFeature(feature.id!!)?.layerId
    assertEquals(layerId, updatedLayerId)
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

    plantsDao.insert(PlantsRow(featureId = feature.id))
    plantObservationsDao.insert(
        PlantObservationsRow(
            id = plantObservationId,
            featureId = feature.id,
            timestamp = time1,
            createdBy = user.userId,
            createdTime = time1,
            modifiedBy = user.userId,
            modifiedTime = time1))
    photosDao.insert(
        PhotosRow(
            id = photoId,
            capturedTime = time1,
            fileName = "myphoto",
            storageUrl = storageUrl,
            contentType = "jpeg",
            size = 1000,
            createdBy = user.userId,
            createdTime = time1,
            modifiedBy = user.userId,
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

    plantsDao.insert(PlantsRow(featureId = feature.id))
    plantObservationsDao.insert(
        PlantObservationsRow(
            id = plantObservationId,
            featureId = feature.id,
            timestamp = time1,
            createdBy = currentUser().userId,
            createdTime = time1,
            modifiedBy = currentUser().userId,
            modifiedTime = time1))
    photosDao.insert(
        PhotosRow(
            id = photoId,
            capturedTime = time1,
            fileName = "myphoto",
            storageUrl = storageUrl,
            contentType = "jpeg",
            size = 1000,
            createdBy = currentUser().userId,
            createdTime = time1,
            modifiedBy = currentUser().userId,
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
            id = photoId,
            createdBy = user.userId,
            createdTime = clock.instant(),
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            storageUrl = storageUrl),
        actualPhotosRow)
  }

  @Test
  fun `createPhoto throws exception if user has no permission to create layer data`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!

    every { user.canUpdateFeature(featureId) } returns false

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
    val photoId = photosRow.id!!

    every { user.canDeleteFeaturePhoto(photoId) } returns false

    assertThrows<AccessDeniedException> { store.deletePhoto(featureId, photoId) }
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
  fun `getPhotoData throws exception if photo file does not exist`() {
    val feature = store.createFeature(validCreateRequest)
    val featureId = feature.id!!
    val photosRow = insertFeaturePhoto(featureId)

    every { fileStore.read(photosRow.storageUrl!!) } throws NoSuchFileException("error")

    assertThrows<PhotoNotFoundException> { store.getPhotoData(featureId, photosRow.id!!) }
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
            createdBy = currentUser().userId,
            createdTime = time1,
            modifiedBy = currentUser().userId,
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

  @Nested
  inner class PlantFeatureTest {
    private val featureId = FeatureId(1000)

    private val validCreateRequest = PlantsRow(featureId = featureId, label = "Some plant label")
    private val speciesIdsToCount = mapOf(SpeciesId(1) to 4, SpeciesId(2) to 1, SpeciesId(3) to 3)
    private val nonExistentSpeciesId = SpeciesId(402)

    private lateinit var layersDao: LayersDao
    private lateinit var speciesDao: SpeciesDao

    @BeforeEach
    fun init() {
      val jooqConfig = dslContext.configuration()

      featuresDao = FeaturesDao(jooqConfig)
      layersDao = LayersDao(jooqConfig)
      speciesDao = SpeciesDao(jooqConfig)

      insertFeature(id = featureId, layerId = layerId)
    }

    private fun insertSeveralPlants(
        speciesIdsToCount: Map<SpeciesId, Int>,
        enteredTime: Instant = time1,
        layerIdToInsert: LayerId = layerId,
    ): Map<SpeciesId, List<FeatureId>> {
      val speciesIdToFeatureIds = mutableMapOf<SpeciesId, List<FeatureId>>()
      speciesIdsToCount.forEach { (currSpeciesId, count) ->
        if (!speciesDao.existsById(currSpeciesId)) {
          speciesDao.insert(
              SpeciesRow(
                  id = currSpeciesId,
                  createdTime = time1,
                  modifiedTime = time1,
                  // Make the speciesName the same as the species id to simplify testing
                  name = currSpeciesId.toString()))
        }

        val featureIds = mutableListOf<FeatureId>()

        repeat(count) {
          val randomFeatureId = FeatureId(Random.nextLong())
          featureIds.add(randomFeatureId)

          insertFeature(id = randomFeatureId, layerId = layerIdToInsert, enteredTime = enteredTime)

          plantsDao.insert(PlantsRow(featureId = randomFeatureId, speciesId = currSpeciesId))
        }

        speciesIdToFeatureIds[currSpeciesId] = featureIds.sortedBy { it.value }
      }

      return speciesIdToFeatureIds
    }

    @Test
    fun `create adds a new row to Plants table, returns PlantsRow`() {
      val plant = store.createPlant(validCreateRequest)
      assertEquals(validCreateRequest, plant)
      assertEquals(plant, plantsDao.fetchOneByFeatureId(validCreateRequest.featureId!!))
    }

    @Test
    fun `create does not modify row passed in, returns a different row instance`() {
      val requestCopy = validCreateRequest.copy()
      val plant = store.createPlant(validCreateRequest)
      Assertions.assertTrue(
          requestCopy == validCreateRequest,
          "validCreateRequest is not being modified by the plant store")
      Assertions.assertFalse(
          validCreateRequest === plant,
          "the plant store does not return back the same instance it received")
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
    fun `fetchFeature returns PlantsRow`() {
      val plant = store.createPlant(validCreateRequest)
      val readResult = store.fetchFeature(plant.featureId!!)?.plant
      assertNotNull(readResult)
      assertEquals(plant, readResult)
    }

    @Test
    fun `listFeatures elements contain all feature and plant data`() {
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
          feature.id!!,
          feature.layerId!!,
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
          FeatureModel(
              id = plant.featureId!!,
              layerId = feature.layerId,
              gpsHorizAccuracy = feature.gpsHorizAccuracy,
              gpsVertAccuracy = feature.gpsVertAccuracy,
              attrib = feature.attrib,
              notes = feature.notes,
              enteredTime = feature.enteredTime,
              geom = feature.geom,
              createdTime = time1,
              modifiedTime = time1,
              plant =
                  PlantsRow(
                      featureId = plant.featureId,
                      label = plant.label,
                      speciesId = species.id,
                      naturalRegen = plant.naturalRegen,
                      datePlanted = plant.datePlanted),
          )
      val actualPlantFeatureData = store.listFeatures(layerId = layerId, plantsOnly = true)[0]

      assertPointsEqual(expectedPlantFeatureData.geom, actualPlantFeatureData.geom)
      assertEquals(
          expectedPlantFeatureData,
          actualPlantFeatureData.copy(geom = expectedPlantFeatureData.geom))
    }

    @Test
    fun `listFeatures returns all plants in the layer when no filters are applied`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
      val plantsFetched = store.listFeatures(layerId = layerId, plantsOnly = true)
      val expectedFeatureIds =
          speciesIdToFeatureIds.map { it -> it.value.map { it } }.flatten().sortedBy { it.value }
      val actualFeatureIds = plantsFetched.map { it.id }
      assertEquals(expectedFeatureIds, actualFeatureIds)
    }

    @Test
    fun `listFeatures filters on species id when it is provided`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
      val speciesIdFilter = speciesIdToFeatureIds.keys.elementAt(0)
      val plantsFetched =
          store.listFeatures(layerId = layerId, speciesId = speciesIdFilter, plantsOnly = true)
      val expectedFeatureIds = speciesIdToFeatureIds[speciesIdFilter]!!.sortedBy { it.value }
      val actualFeatureIds = plantsFetched.map { it.id }
      assertEquals(expectedFeatureIds, actualFeatureIds)

      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(
              layerId = layerId, speciesId = nonExistentSpeciesId, plantsOnly = true))
    }

    @Test
    fun `listFeatures filters on species name when it is provided`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
      val speciesIdFilter = speciesIdToFeatureIds.keys.elementAt(0)
      // For testing simplicity, insertSeveralPlants makes species name and id the same
      val plantsFetched =
          store.listFeatures(
              layerId = layerId, speciesName = speciesIdFilter.toString(), plantsOnly = true)
      val expectedFeatureIds = speciesIdToFeatureIds[speciesIdFilter]!!.sortedBy { it.value }
      val actualFeatureIds = plantsFetched.map { it.id }
      assertEquals(expectedFeatureIds, actualFeatureIds)

      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(
              layerId = layerId, speciesName = nonExistentSpeciesId.toString(), plantsOnly = true))
    }

    @Test
    fun `listFeatures filters on min and max entered times when they are provided`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount, enteredTime = time1)
      val plantsFetched =
          store.listFeatures(layerId = layerId, minEnteredTime = time1, plantsOnly = true)
      val maxPlantsFetched =
          store.listFeatures(layerId = layerId, maxEnteredTime = time1, plantsOnly = true)
      assertEquals(plantsFetched, maxPlantsFetched)
      val expectedFeatureIds =
          speciesIdToFeatureIds.map { it -> it.value.map { it } }.flatten().sortedBy { it.value }
      val actualFeatureIds = plantsFetched.map { it.id }
      assertEquals(expectedFeatureIds, actualFeatureIds)

      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(layerId = layerId, minEnteredTime = time2, plantsOnly = true))
      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(
              layerId = layerId, maxEnteredTime = time1.minusSeconds(1), plantsOnly = true))
    }

    @Test
    fun `listFeatures filters on notes when provided`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
      val featureId = speciesIdToFeatureIds.values.flatten().first()
      val feature = featuresDao.fetchOneById(featureId)!!
      val note = "special note to filter by"
      feature.notes = note
      featuresDao.update(feature)

      val plantsFetched = store.listFeatures(layerId = layerId, notes = note, plantsOnly = true)
      assertEquals(1, plantsFetched.size)
      assertEquals(featureId, plantsFetched.last().id)

      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(
              layerId = layerId, notes = "note that doesn't exist", plantsOnly = true))
    }

    @Test
    fun `listFeatures filters on notes using fuzzy (approximate) matching`() {
      val speciesIdToFeatureIds = insertSeveralPlants(speciesIdsToCount)
      val featureId = speciesIdToFeatureIds.values.flatten().first()
      val feature = featuresDao.fetchOneById(featureId)!!
      feature.notes = "special note to filter by"
      featuresDao.update(feature)

      // Deliberately misspell "special"
      val plantsFetched = store.listFeatures(layerId = layerId, notes = "specal", plantsOnly = true)
      assertEquals(1, plantsFetched.size)
      assertEquals(featureId, plantsFetched.last().id)
    }

    @Test
    fun `listFeatures returns empty list when user doesn't have read permission`() {
      insertSeveralPlants(speciesIdsToCount)
      every { user.canReadLayer(any()) } returns false
      assertEquals(
          emptyList<FeatureModel>(), store.listFeatures(layerId = layerId, plantsOnly = true))
    }

    @Test
    fun `listFeatures returns empty list when layer doesn't exist`() {
      assertEquals(
          emptyList<FeatureModel>(),
          store.listFeatures(layerId = nonExistentLayerId, plantsOnly = true))
    }

    @Test
    fun `listFeatures returns empty list when there are no plants in the layer`() {
      assertEquals(
          emptyList<FeatureModel>(), store.listFeatures(layerId = layerId, plantsOnly = true))
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
        insertFeature(id = randomFeatureId, layerId = layerId, enteredTime = time1)
        plantsDao.insert(PlantsRow(featureId = randomFeatureId))
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
    fun `fetchPlantSummary by site ID aggregates results across accessible layers`() {
      val secondLayerId = LayerId(200)
      val inaccessibleLayerId = LayerId(201)

      insertLayer(secondLayerId, siteId, LayerType.PlantsPlanted)
      insertLayer(inaccessibleLayerId, siteId, LayerType.PlantsPlanted)

      insertSeveralPlants(mapOf(SpeciesId(1) to 4, SpeciesId(2) to 4))
      insertSeveralPlants(
          mapOf(SpeciesId(1) to 2, SpeciesId(3) to 2), layerIdToInsert = secondLayerId)
      insertSeveralPlants(
          mapOf(SpeciesId(1) to 1, SpeciesId(4) to 1), layerIdToInsert = inaccessibleLayerId)

      every { user.canReadLayer(inaccessibleLayerId) } returns false

      val expected = mapOf(SpeciesId(1) to 6, SpeciesId(2) to 4, SpeciesId(3) to 2)
      val actual = store.fetchPlantSummary(siteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `fetchPlantSummary by project ID aggregates results across accessible layers`() {
      val projectId = ProjectId(2)
      val secondSiteId = SiteId(2)
      val secondLayerId = LayerId(200)
      val inaccessibleLayerId = LayerId(201)

      insertSite(secondSiteId, projectId)
      insertLayer(secondLayerId, secondSiteId, LayerType.PlantsPlanted)
      insertLayer(inaccessibleLayerId, siteId, LayerType.PlantsPlanted)

      insertSeveralPlants(mapOf(SpeciesId(1) to 4, SpeciesId(2) to 4))
      insertSeveralPlants(
          mapOf(SpeciesId(1) to 2, SpeciesId(3) to 2), layerIdToInsert = secondLayerId)
      insertSeveralPlants(mapOf(SpeciesId(1) to 2), layerIdToInsert = inaccessibleLayerId)

      every { user.canReadLayer(inaccessibleLayerId) } returns false

      val expected = mapOf(SpeciesId(1) to 6, SpeciesId(2) to 4, SpeciesId(3) to 2)
      val actual = store.fetchPlantSummary(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `fetchPlantSummary by project ID returns empty result if project is inaccessible`() {
      val projectId = ProjectId(2)

      insertSeveralPlants(mapOf(SpeciesId(1) to 4, SpeciesId(2) to 4))

      every { user.canReadProject(projectId) } returns false

      assertEquals(emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(projectId))
    }

    @Test
    fun `fetchPlantSummary by organization ID aggregates results across accessible layers`() {
      val organizationId = OrganizationId(1)
      val secondProjectId = ProjectId(3)
      val secondSiteId = SiteId(2)
      val secondLayerId = LayerId(200)
      val inaccessibleLayerId = LayerId(201)

      insertProject(secondProjectId, organizationId)
      insertSite(secondSiteId, secondProjectId)
      insertLayer(secondLayerId, secondSiteId, LayerType.PlantsPlanted)
      insertLayer(inaccessibleLayerId, secondSiteId, LayerType.PlantsPlanted)

      insertSeveralPlants(mapOf(SpeciesId(1) to 4, SpeciesId(2) to 4))
      insertSeveralPlants(
          mapOf(SpeciesId(1) to 2, SpeciesId(3) to 2), layerIdToInsert = secondLayerId)
      insertSeveralPlants(mapOf(SpeciesId(1) to 2), layerIdToInsert = inaccessibleLayerId)

      every { user.canReadLayer(inaccessibleLayerId) } returns false

      val expected = mapOf(SpeciesId(1) to 6, SpeciesId(2) to 4, SpeciesId(3) to 2)
      val actual = store.fetchPlantSummary(organizationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `fetchPlantSummary by organization ID returns empty list if organization is inaccessible`() {
      val organizationId = OrganizationId(1)

      every { user.canReadOrganization(organizationId) } returns false

      assertEquals(emptyMap<SpeciesId, Int>(), store.fetchPlantSummary(organizationId))
    }

    @Test
    fun `update changes the row in the database, returns row`() {
      val plant = store.createPlant(validCreateRequest)
      val plannedUpdates = plant.copy(label = "test update writes to db")
      val updatedResult = store.updatePlant(plannedUpdates)

      assertEquals(plannedUpdates, updatedResult)
      assertEquals(updatedResult, plantsDao.fetchOneByFeatureId(featureId))
    }

    @Test
    fun `update does not modify row passed in, returns a different row instance`() {
      val plant = store.createPlant(validCreateRequest)
      val plannedUpdates = plant.copy(label = "referential equality test")
      val plannedUpdatesCopy = plannedUpdates.copy()
      val updated = store.updatePlant(plannedUpdates)
      Assertions.assertTrue(
          plannedUpdatesCopy == plannedUpdates,
          "instance we passed as an argument is not being modified by the plant store")
      Assertions.assertFalse(
          updated === plannedUpdates,
          "the plant store does not return back the same instance it received")
    }

    @Test
    fun `update fails with IllegalArgumentException if feature id is null`() {
      assertThrows<IllegalArgumentException> {
        store.updatePlant(validCreateRequest.copy(featureId = null))
      }
    }

    @Test
    fun `update fails with AccessDeniedException if user doesn't have update permission`() {
      val plant = store.createPlant(validCreateRequest)
      every { user.canUpdateFeature(any()) } returns false
      assertThrows<AccessDeniedException> { store.updatePlant(plant) }
    }

    @Test
    fun `update fails with FeatureNotFoundException if feature doesn't exist`() {
      val plant = store.createPlant(validCreateRequest)
      assertThrows<FeatureNotFoundException> {
        store.updatePlant(plant.copy(featureId = nonExistentFeatureId))
      }
    }

    @Test
    fun `update fails with DataIntegrityViolationException if the label is an empty string`() {
      val plant = store.createPlant(validCreateRequest)
      assertThrows<DataIntegrityViolationException> { store.updatePlant(plant.copy(label = "")) }
    }
  }
}
