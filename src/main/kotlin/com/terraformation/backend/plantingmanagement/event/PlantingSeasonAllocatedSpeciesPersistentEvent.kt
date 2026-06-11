package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.util.nullIfEquals

sealed interface PlantingSeasonAllocatedSpeciesPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
  val speciesId: SpeciesId
}

data class PlantingSeasonAllocatedSpeciesCreatedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val quantity: Int,
    override val speciesId: SpeciesId,
) : EntityCreatedPersistentEvent, PlantingSeasonAllocatedSpeciesPersistentEvent

typealias PlantingSeasonAllocatedSpeciesCreatedEvent = PlantingSeasonAllocatedSpeciesCreatedEventV1

data class PlantingSeasonAllocatedSpeciesDeletedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
) : EntityDeletedPersistentEvent, PlantingSeasonAllocatedSpeciesPersistentEvent

typealias PlantingSeasonAllocatedSpeciesDeletedEvent = PlantingSeasonAllocatedSpeciesDeletedEventV1

data class PlantingSeasonAllocatedSpeciesUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
) : FieldsUpdatedPersistentEvent, PlantingSeasonAllocatedSpeciesPersistentEvent {
  data class Values(val quantity: Int? = null)

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "quantity",
              changedFrom.quantity.nullIfEquals(changedTo.quantity)?.toString(),
              changedTo.quantity.nullIfEquals(changedFrom.quantity)?.toString(),
          ),
      )
}

typealias PlantingSeasonAllocatedSpeciesUpdatedEvent = PlantingSeasonAllocatedSpeciesUpdatedEventV1

typealias PlantingSeasonAllocatedSpeciesUpdatedEventValues =
    PlantingSeasonAllocatedSpeciesUpdatedEventV1.Values
