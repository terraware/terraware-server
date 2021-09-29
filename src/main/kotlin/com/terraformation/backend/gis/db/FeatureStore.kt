package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tables.daos.FeaturePhotosDao
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.ThumbnailsDao
import com.terraformation.backend.db.tables.pojos.FeaturePhotosRow
import com.terraformation.backend.db.tables.pojos.PhotosRow
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.FEATURE_PHOTOS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.PLANT_OBSERVATIONS
import com.terraformation.backend.db.transformSrid
import com.terraformation.backend.file.FileStore
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailStore
import com.terraformation.backend.gis.model.FeatureModel
import com.terraformation.backend.log.perClassLogger
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class FeatureStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val featurePhotosDao: FeaturePhotosDao,
    private val fileStore: FileStore,
    private val photosDao: PhotosDao,
    private val thumbnailStore: ThumbnailStore,
    private val thumbnailsDao: ThumbnailsDao,
) {
  private val log = perClassLogger()

  fun createFeature(model: FeatureModel): FeatureModel {
    if (!currentUser().canCreateLayerData(model.layerId)) {
      throw AccessDeniedException("No permission to create feature within layer ${model.layerId}")
    }

    val currTime = clock.instant()
    val insertedRecord =
        with(FEATURES) {
          dslContext
              .insertInto(FEATURES)
              .set(LAYER_ID, model.layerId)
              .set(GEOM, model.geom)
              .set(GPS_HORIZ_ACCURACY, model.gpsHorizAccuracy)
              .set(GPS_VERT_ACCURACY, model.gpsVertAccuracy)
              .set(ATTRIB, model.attrib)
              .set(NOTES, model.notes)
              .set(ENTERED_TIME, model.enteredTime)
              .set(CREATED_TIME, currTime)
              .set(MODIFIED_TIME, currTime)
              .returning(ID, GEOM.transformSrid(SRID.LONG_LAT).`as`(GEOM))
              .fetchOne()
              ?: throw DataAccessException("Database did not return ID")
        }

    return model.copy(
        id = insertedRecord.get(FEATURES.ID),
        geom = insertedRecord.get(FEATURES.GEOM),
        createdTime = currTime,
        modifiedTime = currTime)
  }

  private fun noPermissionsCheckFetch(id: FeatureId): FeatureModel? {
    val record =
        dslContext
            .select(
                FEATURES.ID,
                FEATURES.LAYER_ID,
                FEATURES.GEOM.transformSrid(SRID.LONG_LAT).`as`(FEATURES.GEOM),
                FEATURES.GPS_HORIZ_ACCURACY,
                FEATURES.GPS_VERT_ACCURACY,
                FEATURES.ATTRIB,
                FEATURES.NOTES,
                FEATURES.ENTERED_TIME,
                FEATURES.CREATED_TIME,
                FEATURES.MODIFIED_TIME)
            .from(FEATURES)
            .where(FEATURES.ID.eq(id))
            .fetchOne()
            ?: return null

    return FeatureModel(
        id = record[FEATURES.ID],
        layerId = record[FEATURES.LAYER_ID]!!,
        geom = record[FEATURES.GEOM],
        gpsHorizAccuracy = record[FEATURES.GPS_HORIZ_ACCURACY],
        gpsVertAccuracy = record[FEATURES.GPS_VERT_ACCURACY],
        attrib = record[FEATURES.ATTRIB],
        notes = record[FEATURES.NOTES],
        enteredTime = record[FEATURES.ENTERED_TIME],
        createdTime = record[FEATURES.CREATED_TIME],
        modifiedTime = record[FEATURES.MODIFIED_TIME])
  }

  fun fetchFeature(id: FeatureId): FeatureModel? {
    if (!currentUser().canReadLayerData(id)) {
      return null
    }
    return noPermissionsCheckFetch(id)
  }

  fun listFeatures(id: LayerId, skip: Int? = null, limit: Int? = null): List<FeatureModel> {
    if (!currentUser().canReadLayerData(id)) {
      return emptyList()
    }

    with(FEATURES) {
      val records =
          dslContext
              .select(
                  ID,
                  LAYER_ID,
                  GEOM.transformSrid(SRID.LONG_LAT).`as`(GEOM),
                  GPS_HORIZ_ACCURACY,
                  GPS_VERT_ACCURACY,
                  ATTRIB,
                  NOTES,
                  ENTERED_TIME,
                  CREATED_TIME,
                  MODIFIED_TIME)
              .from(FEATURES)
              .where(LAYER_ID.eq(id))
              .orderBy(ID)
              .limit(limit)
              .offset(skip)
              .fetch()

      return records.map { record ->
        FeatureModel(
            id = record[ID],
            layerId = record[LAYER_ID]!!,
            geom = record[GEOM],
            gpsHorizAccuracy = record[GPS_HORIZ_ACCURACY],
            gpsVertAccuracy = record[GPS_VERT_ACCURACY],
            attrib = record[ATTRIB],
            notes = record[NOTES],
            enteredTime = record[ENTERED_TIME],
            createdTime = record[CREATED_TIME],
            modifiedTime = record[MODIFIED_TIME])
      }
    }
  }

  fun updateFeature(newModel: FeatureModel): FeatureModel {
    val oldModel = noPermissionsCheckFetch(newModel.id!!)

    if (oldModel == null ||
        newModel.layerId != oldModel.layerId ||
        !currentUser().canUpdateLayerData(newModel.layerId)) {
      // Caller cannot change the layerId. They must delete this Feature and recreate a new one
      // if they want to "move" the feature into a new layer.
      throw FeatureNotFoundException(newModel.id)
    }

    if (newModel == oldModel) {
      return newModel
    }

    val currTime = clock.instant()

    val longLatGeom =
        with(FEATURES) {
          dslContext
              .update(FEATURES)
              .set(GEOM, newModel.geom)
              .set(GPS_HORIZ_ACCURACY, newModel.gpsHorizAccuracy)
              .set(GPS_VERT_ACCURACY, newModel.gpsVertAccuracy)
              .set(ATTRIB, newModel.attrib)
              .set(NOTES, newModel.notes)
              .set(ENTERED_TIME, newModel.enteredTime)
              .set(MODIFIED_TIME, currTime)
              .where(ID.eq(newModel.id))
              .returningResult(GEOM.transformSrid(SRID.LONG_LAT).`as`(GEOM))
              .fetchOne()
              ?.getValue(GEOM)
              ?: throw DataAccessException("Database did not return Geom")
        }

    return newModel.copy(modifiedTime = currTime, geom = longLatGeom)
  }

  fun deleteFeature(id: FeatureId): FeatureId {
    if (!currentUser().canDeleteLayerData(id) || noPermissionsCheckFetch(id) == null) {
      throw FeatureNotFoundException(id)
    }

    val featurePhotos = featurePhotosDao.fetchByFeatureId(id)
    val photos = photosDao.fetchById(*featurePhotos.mapNotNull { it.photoId }.toTypedArray())

    photos.forEach { photo -> deletePhoto(id, photo.id!!) }

    dslContext.transaction { _ ->
      dslContext.delete(PLANT_OBSERVATIONS).where(PLANT_OBSERVATIONS.FEATURE_ID.eq(id)).execute()
      dslContext.delete(PLANTS).where(PLANTS.FEATURE_ID.eq(id)).execute()
      dslContext.delete(FEATURES).where(FEATURES.ID.eq(id)).execute()
    }

    return id
  }

  fun createPhoto(
      featureId: FeatureId,
      photosRow: PhotosRow,
      data: InputStream,
      plantObservationId: PlantObservationId? = null
  ): PhotoId {
    val contentType =
        photosRow.contentType ?: throw IllegalArgumentException("No content type specified")
    val size = photosRow.size ?: throw IllegalArgumentException("No file size specified")

    if (!currentUser().canCreateLayerData(featureId) ||
        noPermissionsCheckFetch(featureId) == null) {
      throw FeatureNotFoundException(featureId)
    }

    val createdTime = clock.instant()
    val photoUrl = fileStore.newUrl(createdTime, "feature", contentType)

    try {
      fileStore.write(photoUrl, data, size)

      return dslContext.transactionResult { _ ->
        val sanitizedRow =
            photosRow.copy(
                createdTime = createdTime,
                id = null,
                modifiedTime = createdTime,
                storageUrl = photoUrl)

        photosDao.insert(sanitizedRow)

        featurePhotosDao.insert(
            FeaturePhotosRow(
                featureId = featureId,
                photoId = sanitizedRow.id,
                plantObservationId = plantObservationId))

        log.info("Stored $photoUrl for feature $featureId")

        sanitizedRow.id!!
      }
    } catch (e: FileAlreadyExistsException) {
      log.error("File $photoUrl already exists but should be unique")
      throw e
    } catch (e: Exception) {
      try {
        fileStore.delete(photoUrl)
      } catch (ignore: NoSuchFileException) {
        // Swallow this; file is already deleted
      }
      throw e
    }
  }

  fun listPhotos(featureId: FeatureId): List<PhotosRow> {
    if (!currentUser().canReadLayerData(featureId) || noPermissionsCheckFetch(featureId) == null) {
      throw FeatureNotFoundException(featureId)
    }

    return dslContext
        .select(PHOTOS.asterisk())
        .from(PHOTOS)
        .join(FEATURE_PHOTOS)
        .on(PHOTOS.ID.eq(FEATURE_PHOTOS.PHOTO_ID))
        .where(FEATURE_PHOTOS.FEATURE_ID.eq(featureId))
        .and(FEATURE_PHOTOS.PLANT_OBSERVATION_ID.isNull)
        .fetchInto(PhotosRow::class.java)
  }

  fun getPhotoMetadata(featureId: FeatureId, photoId: PhotoId): PhotosRow {
    val featurePhoto =
        featurePhotosDao.fetchByPhotoId(photoId).firstOrNull()
            ?: throw PhotoNotFoundException(photoId)

    if (featurePhoto.featureId != featureId || !currentUser().canReadLayerData(featureId)) {
      throw PhotoNotFoundException(photoId)
    }

    return photosDao.fetchOneById(photoId) ?: throw PhotoNotFoundException(photoId)
  }

  fun getPhotoData(
      featureId: FeatureId,
      photoId: PhotoId,
      maxWidth: Int? = null,
      maxHeight: Int? = null
  ): SizedInputStream {
    val photosRow = getPhotoMetadata(featureId, photoId)

    return if (maxWidth != null || maxHeight != null) {
      thumbnailStore.getThumbnailData(photoId, maxWidth, maxHeight)
    } else {
      fileStore.read(photosRow.storageUrl!!)
    }
  }

  fun deletePhoto(featureId: FeatureId, photoId: PhotoId) {
    val photosRow = getPhotoMetadata(featureId, photoId)

    if (!currentUser().canDeleteLayerData(featureId)) {
      throw AccessDeniedException("No permission to delete feature photos.")
    }

    val thumbnails = thumbnailsDao.fetchByPhotoId(photoId)
    val url = photosRow.storageUrl!!

    dslContext.transaction { _ ->
      if (thumbnails.isNotEmpty()) {
        thumbnailsDao.delete(thumbnails)
      }

      featurePhotosDao.deleteById(photoId)
      photosDao.deleteById(photoId)
    }

    try {
      fileStore.delete(url)
    } catch (e: Exception) {
      log.error("Unable to delete photo $url from file storage", e)
    }

    // TODO: Delete thumbnails from file storage
  }
}
