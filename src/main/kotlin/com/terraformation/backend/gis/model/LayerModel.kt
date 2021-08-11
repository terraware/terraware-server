package com.terraformation.backend.gis.model

import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.SiteId

data class LayerModel(
    val id: LayerId? = null,
    val siteId: SiteId? = null,
    val layerTypeId: LayerType? = null,
    val tileSetName: String? = null,
    val proposed: Boolean? = null,
    val hidden: Boolean? = null,
    val deleted: Boolean? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
)
