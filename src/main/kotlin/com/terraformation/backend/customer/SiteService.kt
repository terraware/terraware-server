package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.gis.db.LayerStore
import com.terraformation.backend.gis.model.LayerModel
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/** Site-related business logic that needs to interact with multiple services. */
@ManagedBean
class SiteService(
    private val dslContext: DSLContext,
    private val siteStore: SiteStore,
    private val layerStore: LayerStore,
) {
  /** Creates a new site with an initial set of map layers. */
  fun create(row: SitesRow, layerTypes: Set<LayerType>): SiteModel {
    return dslContext.transactionResult { _ ->
      val siteModel = siteStore.create(row)

      layerTypes.forEach { layerType ->
        layerStore.createLayer(
            LayerModel(
                hidden = false,
                layerType = layerType,
                proposed = false,
                siteId = siteModel.id,
                tileSetName = null,
            ))
      }

      siteModel
    }
  }
}
