package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.tables.daos.PhotosDao
import com.terraformation.backend.db.tables.daos.ThumbnailDao
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.PLANTS
import com.terraformation.backend.db.tables.references.PLANT_OBSERVATIONS
import com.terraformation.backend.gis.model.FeatureModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class FeatureStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val photosDao: PhotosDao,
    private val thumbnailDao: ThumbnailDao,
) {
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
              .set(SHAPE_TYPE_ID, model.shapeType)
              .set(GEOM, model.geom)
              .set(GPS_HORIZ_ACCURACY, model.gpsHorizAccuracy)
              .set(GPS_VERT_ACCURACY, model.gpsVertAccuracy)
              .set(ATTRIB, model.attrib)
              .set(NOTES, model.notes)
              .set(ENTERED_TIME, model.enteredTime)
              .set(CREATED_TIME, currTime)
              .set(MODIFIED_TIME, currTime)
              .returning(ID, GEOM)
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
                FEATURES.SHAPE_TYPE_ID,
                FEATURES.GEOM,
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
        shapeType = record[FEATURES.SHAPE_TYPE_ID]!!,
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
                  SHAPE_TYPE_ID,
                  GEOM,
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
            shapeType = record[SHAPE_TYPE_ID]!!,
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

    with(FEATURES) {
      dslContext
          .update(FEATURES)
          .set(SHAPE_TYPE_ID, newModel.shapeType)
          .set(GEOM, newModel.geom)
          .set(GPS_HORIZ_ACCURACY, newModel.gpsHorizAccuracy)
          .set(GPS_VERT_ACCURACY, newModel.gpsVertAccuracy)
          .set(ATTRIB, newModel.attrib)
          .set(NOTES, newModel.notes)
          .set(ENTERED_TIME, newModel.enteredTime)
          .set(MODIFIED_TIME, currTime)
          .where(ID.eq(newModel.id))
          .execute()
    }

    return newModel.copy(modifiedTime = currTime)
  }

  fun deleteFeature(id: FeatureId): FeatureId {
    if (!currentUser().canDeleteLayerData(id) || noPermissionsCheckFetch(id) == null) {
      throw FeatureNotFoundException(id)
    }

    val photos = photosDao.fetchByFeatureId(id)
    photos.forEach { photo ->
      val thumbnails = thumbnailDao.fetchByPhotoId(photo.id!!)
      thumbnailDao.delete(thumbnails)
    }
    photosDao.delete(photos)

    dslContext.delete(PLANT_OBSERVATIONS).where(PLANT_OBSERVATIONS.FEATURE_ID.eq(id)).execute()
    dslContext.delete(PLANTS).where(PLANTS.FEATURE_ID.eq(id)).execute()
    dslContext.delete(FEATURES).where(FEATURES.ID.eq(id)).execute()

    return id
  }
}
