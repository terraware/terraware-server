package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.gis.model.LayerModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class LayerStore(
    private val dslContext: DSLContext,
    private val clock: Clock,
) {
  fun createLayer(layerModel: LayerModel): LayerModel {
    if (!currentUser().canCreateLayer(layerModel.siteId!!)) {
      throw AccessDeniedException("No permission to create layer at site ${layerModel.siteId}")
    }
    val currTime = clock.instant()
    val layerID =
        dslContext
            .insertInto(LAYERS)
            .set(LAYERS.SITE_ID, layerModel.siteId)
            .set(LAYERS.LAYER_TYPE_ID, layerModel.layerTypeId)
            .set(LAYERS.TILE_SET_NAME, layerModel.tileSetName)
            .set(LAYERS.PROPOSED, layerModel.proposed)
            .set(LAYERS.HIDDEN, layerModel.hidden)
            .set(LAYERS.DELETED, false)
            .set(LAYERS.CREATED_TIME, currTime)
            .set(LAYERS.MODIFIED_TIME, currTime)
            .returning(LAYERS.ID)
            .fetchOne()
            ?.get(LAYERS.ID)!!

    return layerModel.copy(
        id = layerID,
        deleted = false,
        createdTime = currTime.toString(),
        modifiedTime = currTime.toString())
  }

  fun fetchLayer(id: LayerId, skipDeleted: Boolean = true): LayerModel? {
    val layer =
        dslContext
            .select(
                LAYERS.SITE_ID,
                LAYERS.LAYER_TYPE_ID,
                LAYERS.TILE_SET_NAME,
                LAYERS.PROPOSED,
                LAYERS.HIDDEN,
                LAYERS.DELETED,
                LAYERS.CREATED_TIME,
                LAYERS.MODIFIED_TIME,
            )
            .from(LAYERS)
            .where(LAYERS.ID.eq(id))
            .fetchOne()

    if (layer == null || (layer[LAYERS.DELETED]!! && skipDeleted)) {
      return null
    }

    val siteId = layer[LAYERS.SITE_ID]!!
    if (!currentUser().canReadLayer(siteId)) {
      return null
    }

    return LayerModel(
        id = id,
        siteId = siteId,
        layerTypeId = layer[LAYERS.LAYER_TYPE_ID],
        tileSetName = layer[LAYERS.TILE_SET_NAME],
        proposed = layer[LAYERS.PROPOSED],
        hidden = layer[LAYERS.HIDDEN],
        deleted = layer[LAYERS.DELETED],
        createdTime = layer[LAYERS.CREATED_TIME].toString(),
        modifiedTime = layer[LAYERS.MODIFIED_TIME].toString(),
    )
  }

  // Does not allow a layer to move between sites
  fun updateLayer(layerModel: LayerModel): LayerModel {
    if (!currentUser().canUpdateLayer(layerModel.siteId!!)) {
      throw LayerNotFoundException(layerModel.id.toString())
    }

    val currentRow =
        dslContext
            .select(
                LAYERS.LAYER_TYPE_ID,
                LAYERS.TILE_SET_NAME,
                LAYERS.PROPOSED,
                LAYERS.HIDDEN,
                LAYERS.DELETED,
                LAYERS.CREATED_TIME,
                LAYERS.MODIFIED_TIME,
            )
            .from(LAYERS)
            .where(LAYERS.ID.eq(layerModel.id))
            .and(LAYERS.SITE_ID.eq(layerModel.siteId))
            .fetchOne()

    if (currentRow == null || currentRow[LAYERS.DELETED]!!) {
      throw LayerNotFoundException(layerModel.id.toString())
    }

    // Check if the row we just checked is the same as what we're trying to write.
    // We might as well do this since we already need to make a database call to check
    // that the requested row is not marked as "deleted".
    if (currentRow[LAYERS.LAYER_TYPE_ID]!! == layerModel.layerTypeId &&
        currentRow[LAYERS.TILE_SET_NAME]!! == layerModel.tileSetName &&
        currentRow[LAYERS.PROPOSED]!! == layerModel.proposed &&
        currentRow[LAYERS.HIDDEN]!! == layerModel.hidden) {

      // All the data that the client can update is exactly the same so don't write anything.
      // Add fields to the response that the user did not pass in.
      return layerModel.copy(
          deleted = false,
          modifiedTime = currentRow[LAYERS.MODIFIED_TIME]!!.toString(),
          createdTime = currentRow[LAYERS.CREATED_TIME]!!.toString())
    }

    val currTime = clock.instant()

    dslContext
        .update(LAYERS)
        .set(LAYERS.SITE_ID, layerModel.siteId)
        .set(LAYERS.LAYER_TYPE_ID, layerModel.layerTypeId)
        .set(LAYERS.TILE_SET_NAME, layerModel.tileSetName)
        .set(LAYERS.PROPOSED, layerModel.proposed)
        .set(LAYERS.HIDDEN, layerModel.hidden)
        .set(LAYERS.MODIFIED_TIME, currTime)
        .where(LAYERS.ID.eq(layerModel.id))
        .execute()

    // Add fields to the response that the user did not pass in.
    return layerModel.copy(
        deleted = false,
        createdTime = currentRow[LAYERS.CREATED_TIME]!!.toString(),
        modifiedTime = currTime.toString())
  }

  fun deleteLayer(id: LayerId): LayerModel {
    // Must fetch layer to get site ID and check permissions
    val layer: LayerModel? = fetchLayer(id)

    if (layer == null || !currentUser().canDeleteLayer(layer.siteId!!)) {
      throw LayerNotFoundException(id.toString())
    }

    val currTime = clock.instant()
    dslContext
        .update(LAYERS)
        .set(LAYERS.DELETED, true)
        .set(LAYERS.MODIFIED_TIME, currTime)
        .where(LAYERS.ID.eq(layer.id))
        .and(LAYERS.SITE_ID.eq(layer.siteId))
        .execute()

    return layer.copy(deleted = true, modifiedTime = currTime.toString())
  }
}
