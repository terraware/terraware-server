package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.gis.model.FeatureModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class FeatureStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
) {
  fun createFeature(model: FeatureModel): FeatureModel {
    val siteId =
        dslContext
            .select(LAYERS.SITE_ID)
            .from(LAYERS)
            .where(LAYERS.ID.eq(model.layerId))
            .fetchOne()
            ?.get(LAYERS.SITE_ID)

    if (siteId == null || !currentUser().canCreateLayerData(siteId)) {
      throw AccessDeniedException("No permission to create feature within layer ${model.layerId}")
    }

    val currTime = clock.instant()
    val featureId: FeatureId?
    with(FEATURES) {
      featureId =
          dslContext
              .insertInto(FEATURES)
              .set(LAYER_ID, model.layerId)
              .set(SHAPE_TYPE_ID, model.shapeType)
              .set(ALTITUDE, model.altitude)
              .set(GPS_HORIZ_ACCURACY, model.gpsHorizAccuracy)
              .set(GPS_VERT_ACCURACY, model.gpsVertAccuracy)
              .set(ATTRIB, model.attrib)
              .set(NOTES, model.notes)
              .set(ENTERED_TIME, model.enteredTime)
              .set(CREATED_TIME, currTime)
              .set(MODIFIED_TIME, currTime)
              .returning(ID)
              .fetchOne()
              ?.get(ID)
    }
    return model.copy(id = featureId, createdTime = currTime, modifiedTime = currTime)
  }

  private fun noPermissionsCheckFetch(id: FeatureId): Pair<FeatureModel?, SiteId?> {
    val record =
        dslContext
            .select(
                FEATURES.ID,
                FEATURES.LAYER_ID,
                FEATURES.SHAPE_TYPE_ID,
                FEATURES.ALTITUDE,
                FEATURES.GPS_HORIZ_ACCURACY,
                FEATURES.GPS_VERT_ACCURACY,
                FEATURES.ATTRIB,
                FEATURES.NOTES,
                FEATURES.ENTERED_TIME,
                FEATURES.CREATED_TIME,
                FEATURES.MODIFIED_TIME,
                LAYERS.SITE_ID)
            .from(FEATURES)
            .join(LAYERS)
            .on(FEATURES.LAYER_ID.eq(LAYERS.ID))
            .where(FEATURES.ID.eq(id))
            .fetchOne()
            ?: return Pair(null, null)

    val model =
        FeatureModel(
            id = record[FEATURES.ID],
            layerId = record[FEATURES.LAYER_ID]!!,
            shapeType = record[FEATURES.SHAPE_TYPE_ID]!!,
            altitude = record[FEATURES.ALTITUDE],
            gpsHorizAccuracy = record[FEATURES.GPS_HORIZ_ACCURACY],
            gpsVertAccuracy = record[FEATURES.GPS_VERT_ACCURACY],
            attrib = record[FEATURES.ATTRIB],
            notes = record[FEATURES.NOTES],
            enteredTime = record[FEATURES.ENTERED_TIME],
            createdTime = record[FEATURES.CREATED_TIME],
            modifiedTime = record[FEATURES.MODIFIED_TIME])

    return Pair(model, record[LAYERS.SITE_ID])
  }

  fun fetchFeature(id: FeatureId): FeatureModel? {
    val (model, siteId) = noPermissionsCheckFetch(id)

    if (siteId == null || !currentUser().canReadLayerData(siteId)) {
      return null
    }

    return model
  }

  fun updateFeature(newModel: FeatureModel): FeatureModel {
    val (oldModel, siteId) = noPermissionsCheckFetch(newModel.id!!)

    if (siteId == null ||
        oldModel == null ||
        !currentUser().canUpdateLayerData(siteId) ||
        newModel.layerId != oldModel.layerId) {
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
          .set(ALTITUDE, newModel.altitude)
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
    val (model, siteId) = noPermissionsCheckFetch(id)

    if (siteId == null || model == null || !currentUser().canDeleteLayerData(siteId)) {
      throw FeatureNotFoundException(id)
    }

    dslContext.delete(FEATURES).where(FEATURES.ID.eq(id)).execute()

    return id
  }
}
