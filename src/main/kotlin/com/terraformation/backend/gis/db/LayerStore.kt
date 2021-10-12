package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.gis.model.LayerModel
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class LayerStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
) {
  fun createLayer(layerModel: LayerModel): LayerModel {
    requirePermissions { createLayer(layerModel.siteId) }

    val currTime = clock.instant()
    val layerID =
        dslContext
            .insertInto(LAYERS)
            .set(LAYERS.SITE_ID, layerModel.siteId)
            .set(LAYERS.LAYER_TYPE_ID, layerModel.layerType)
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
        id = layerID, deleted = false, createdTime = currTime, modifiedTime = currTime)
  }

  fun fetchLayer(id: LayerId, ignoreDeleted: Boolean = true): LayerModel? {
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

    if (layer == null || (layer[LAYERS.DELETED]!! && ignoreDeleted)) {
      return null
    }

    val siteId = layer[LAYERS.SITE_ID]!!
    if (!currentUser().canReadLayer(siteId)) {
      return null
    }

    return LayerModel(
        id = id,
        siteId = siteId,
        layerType = layer[LAYERS.LAYER_TYPE_ID]!!,
        tileSetName = layer[LAYERS.TILE_SET_NAME],
        proposed = layer[LAYERS.PROPOSED]!!,
        hidden = layer[LAYERS.HIDDEN]!!,
        deleted = layer[LAYERS.DELETED],
        createdTime = layer[LAYERS.CREATED_TIME],
        modifiedTime = layer[LAYERS.MODIFIED_TIME],
    )
  }

  fun listLayers(siteId: SiteId): List<LayerModel> {
    if (!currentUser().canReadLayer(siteId)) {
      return emptyList()
    }

    with(LAYERS) {
      val records =
          dslContext
              .select(
                  ID,
                  SITE_ID,
                  LAYER_TYPE_ID,
                  TILE_SET_NAME,
                  PROPOSED,
                  HIDDEN,
                  DELETED,
                  CREATED_TIME,
                  MODIFIED_TIME,
              )
              .from(LAYERS)
              .where(SITE_ID.eq(siteId))
              .and(DELETED.isFalse)
              .fetch()

      return records.map { record ->
        LayerModel(
            id = record[ID],
            siteId = record[SITE_ID]!!,
            layerType = record[LAYER_TYPE_ID]!!,
            tileSetName = record[TILE_SET_NAME],
            proposed = record[LAYERS.PROPOSED]!!,
            hidden = record[LAYERS.HIDDEN]!!,
            deleted = record[LAYERS.DELETED],
            createdTime = record[LAYERS.CREATED_TIME],
            modifiedTime = record[LAYERS.MODIFIED_TIME],
        )
      }
    }
  }

  fun updateLayer(layerModel: LayerModel): LayerModel {
    val layerId = layerModel.id ?: throw IllegalArgumentException("No layer ID specified")

    val currentRow =
        dslContext
            .select(
                LAYERS.SITE_ID,
                LAYERS.LAYER_TYPE_ID,
                LAYERS.TILE_SET_NAME,
                LAYERS.PROPOSED,
                LAYERS.HIDDEN,
                LAYERS.CREATED_TIME,
                LAYERS.MODIFIED_TIME,
            )
            .from(LAYERS)
            .where(LAYERS.ID.eq(layerId))
            .and(LAYERS.DELETED.isFalse)
            .fetchOne()
            ?: throw LayerNotFoundException(layerId)

    // Caller provided siteId must match the siteId in the database because allowing site moves
    // would add additional permissions complexity. Plus, the caller can achieve that behavior
    // using a combination of createLayer() and deleteLayer().
    requirePermissions { updateLayer(layerId, layerModel.siteId) }
    if (layerModel.siteId != currentRow[LAYERS.SITE_ID]) {
      throw LayerNotFoundException(layerId)
    }

    // Check if the row we just fetched is the same as what we're trying to write.
    // We might as well do this since we already need to make a database call to check
    // that the requested row is not marked as "deleted".
    // This check also allows us to have a more accurate "modified" timestamp.
    if (currentRow[LAYERS.LAYER_TYPE_ID] == layerModel.layerType &&
        currentRow[LAYERS.TILE_SET_NAME] == layerModel.tileSetName &&
        currentRow[LAYERS.PROPOSED] == layerModel.proposed &&
        currentRow[LAYERS.HIDDEN] == layerModel.hidden) {

      // All the data that the client can update is exactly the same so don't write anything.
      return layerModel.copy(
          deleted = false,
          modifiedTime = currentRow[LAYERS.MODIFIED_TIME]!!,
          createdTime = currentRow[LAYERS.CREATED_TIME]!!)
    }

    val currTime = clock.instant()

    dslContext
        .update(LAYERS)
        // Overwrite all user-set fields.
        .set(LAYERS.LAYER_TYPE_ID, layerModel.layerType)
        .set(LAYERS.TILE_SET_NAME, layerModel.tileSetName)
        .set(LAYERS.PROPOSED, layerModel.proposed)
        .set(LAYERS.HIDDEN, layerModel.hidden)
        .set(LAYERS.MODIFIED_TIME, currTime)
        .where(LAYERS.ID.eq(layerId))
        .execute()

    return layerModel.copy(
        deleted = false, createdTime = currentRow[LAYERS.CREATED_TIME]!!, modifiedTime = currTime)
  }

  fun deleteLayer(id: LayerId): LayerModel {
    // Must fetch layer to get site ID and check permissions
    val layer = fetchLayer(id) ?: throw LayerNotFoundException(id)

    requirePermissions { deleteLayer(id, layer.siteId) }

    val currTime = clock.instant()
    dslContext
        .update(LAYERS)
        .set(LAYERS.DELETED, true)
        .set(LAYERS.MODIFIED_TIME, currTime)
        .where(LAYERS.ID.eq(layer.id))
        .execute()

    return layer.copy(deleted = true, modifiedTime = currTime)
  }
}
