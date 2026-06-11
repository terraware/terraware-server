package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import java.time.LocalDate

sealed interface PlantingDateRequestPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
  val scheduledPlantingDateId: ScheduledPlantingDateId
}

data class PlantingDateRequestCreatedEventV1(
    val date: LocalDate,
    val notes: String?,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
    val status: PlantingDateRequestStatus,
) : EntityCreatedPersistentEvent, PlantingDateRequestPersistentEvent

typealias PlantingDateRequestCreatedEvent = PlantingDateRequestCreatedEventV1

data class PlantingDateRequestUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
) : FieldsUpdatedPersistentEvent, PlantingDateRequestPersistentEvent {
  data class Values(
      val date: LocalDate? = null,
      val notes: String? = null,
      val status: PlantingDateRequestStatus? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("date", changedFrom.date?.toString(), changedTo.date?.toString()),
          createUpdatedField("notes", changedFrom.notes, changedTo.notes),
          createUpdatedField(
              "status",
              changedFrom.status?.getDisplayName(currentLocale()),
              changedTo.status?.getDisplayName(currentLocale()),
          ),
      )
}

typealias PlantingDateRequestUpdatedEvent = PlantingDateRequestUpdatedEventV1

typealias PlantingDateRequestUpdatedEventValues = PlantingDateRequestUpdatedEventV1.Values
