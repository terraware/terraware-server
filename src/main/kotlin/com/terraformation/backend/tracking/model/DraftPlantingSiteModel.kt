package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.DraftPlantingSiteType
import com.terraformation.backend.db.tracking.DraftStratumId
import com.terraformation.backend.db.tracking.DraftSubstratumId
import java.math.BigDecimal
import org.locationtech.jts.geom.MultiPolygon

data class DraftSubstratumModel(
    val id: DraftSubstratumId?,
    val name: String,
    val boundary: MultiPolygon,
)

data class DraftStratumModel(
    val id: DraftStratumId?,
    val name: String,
    val boundary: MultiPolygon,
    val initialPlantingDensity: BigDecimal,
    val substrata: List<DraftSubstratumModel>,
)

/** The map and wizard-state portion of a draft planting site, which is what `data` carries. */
data class DraftPlantingSiteMap(
    val siteType: DraftPlantingSiteType,
    val siteEditStep: String?,
    val boundarySource: String?,
    val boundary: MultiPolygon?,
    val exclusion: MultiPolygon?,
    val strata: List<DraftStratumModel>,
)
