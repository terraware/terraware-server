package com.terraformation.backend.gis.model

import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.SiteId
import java.time.Instant

data class LayerModel(
    val id: LayerId? = null,
    val siteId: SiteId,
    val layerType: LayerType,
    val tileSetName: String?,
    val proposed: Boolean,
    val hidden: Boolean,
    val deleted: Boolean? = null,
    val createdTime: Instant? = null,
    val modifiedTime: Instant? = null,
)
