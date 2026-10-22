package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages

sealed interface PlantingSitePersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val plantingSiteName: String
}

data class SurvivalRateIncludesTempPlotsChangedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val plantingSiteName: String,
) : FieldsUpdatedPersistentEvent, PlantingSitePersistentEvent {
  data class Values(val survivalRateIncludesTempPlots: Boolean? = null)

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "survivalRateIncludesTempPlots",
              messages.booleanOrNull(changedFrom.survivalRateIncludesTempPlots),
              messages.booleanOrNull(changedTo.survivalRateIncludesTempPlots),
          )
      )
}

typealias SurvivalRateIncludesTempPlotsChangedEvent = SurvivalRateIncludesTempPlotsChangedEventV1

typealias SurvivalRateIncludesTempPlotsChangedEventValues =
    SurvivalRateIncludesTempPlotsChangedEventV1.Values
