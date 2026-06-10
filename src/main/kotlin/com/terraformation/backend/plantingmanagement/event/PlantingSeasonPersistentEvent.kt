package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import java.time.LocalDate

sealed interface PlantingSeasonPersistentEvent : PlantingSeasonRelatedPersistentEvent {
  override val organizationId: OrganizationId
  override val plantingSeasonId: PlantingSeasonId
  override val plantingSiteId: PlantingSiteId
}

data class PlantingSeasonCreatedEventV1(
    val endDate: LocalDate,
    val name: String,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val startDate: LocalDate,
    val status: PlantingSeasonStatus,
) : EntityCreatedPersistentEvent, PlantingSeasonPersistentEvent

typealias PlantingSeasonCreatedEvent = PlantingSeasonCreatedEventV1

data class PlantingSeasonDeletedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
) : EntityDeletedPersistentEvent, PlantingSeasonPersistentEvent

typealias PlantingSeasonDeletedEvent = PlantingSeasonDeletedEventV1

data class PlantingSeasonUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
) : FieldsUpdatedPersistentEvent, PlantingSeasonPersistentEvent {
  data class Values(
      val endDate: LocalDate? = null,
      val name: String? = null,
      val startDate: LocalDate? = null,
      val status: PlantingSeasonStatus? = null,
  )

  override fun listUpdatedFields(
      messages: Messages
  ): List<FieldsUpdatedPersistentEvent.UpdatedField> {
    return listOfNotNull(
        createUpdatedField(
            "endDate",
            changedFrom.endDate?.toString(),
            changedTo.endDate?.toString(),
        ),
        createUpdatedField("name", changedFrom.name, changedTo.name),
        createUpdatedField(
            "startDate",
            changedFrom.startDate?.toString(),
            changedTo.startDate?.toString(),
        ),
        createUpdatedField(
            "status",
            changedFrom.status?.getDisplayName(currentLocale()),
            changedTo.status?.getDisplayName(currentLocale()),
        ),
    )
  }
}

typealias PlantingSeasonUpdatedEvent = PlantingSeasonUpdatedEventV1

typealias PlantingSeasonUpdatedEventValues = PlantingSeasonUpdatedEventV1.Values
