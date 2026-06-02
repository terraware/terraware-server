package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import java.time.LocalDate

sealed interface PlantingSeasonScheduledDatePersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
  val scheduledPlantingDateId: ScheduledPlantingDateId
}

data class PlantingSeasonScheduledDateCreatedEventV1(
    val date: LocalDate,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
) : EntityCreatedPersistentEvent, PlantingSeasonScheduledDatePersistentEvent

typealias PlantingSeasonScheduledDateCreatedEvent = PlantingSeasonScheduledDateCreatedEventV1

data class PlantingSeasonScheduledDateDeletedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
) : EntityDeletedPersistentEvent, PlantingSeasonScheduledDatePersistentEvent

typealias PlantingSeasonScheduledDateDeletedEvent = PlantingSeasonScheduledDateDeletedEventV1

data class PlantingSeasonScheduledDateUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
) : FieldsUpdatedPersistentEvent, PlantingSeasonScheduledDatePersistentEvent {
  data class Values(
      val date: LocalDate? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("date", changedFrom.date?.toString(), changedTo.date?.toString()),
      )
}

typealias PlantingSeasonScheduledDateUpdatedEvent = PlantingSeasonScheduledDateUpdatedEventV1

typealias PlantingSeasonScheduledDateUpdatedEventValues =
    PlantingSeasonScheduledDateUpdatedEventV1.Values
