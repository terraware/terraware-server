package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.util.nullIfEquals

sealed interface PlantingDateRequestSpeciesPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
  val scheduledPlantingDateId: ScheduledPlantingDateId
  val speciesId: SpeciesId
  val stratumName: String
  val substratumHistoryId: SubstratumHistoryId
  val substratumId: SubstratumId
  val substratumName: String
}

data class PlantingDateRequestSpeciesCreatedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val quantity: Int,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : EntityCreatedPersistentEvent, PlantingDateRequestSpeciesPersistentEvent

typealias PlantingDateRequestSpeciesCreatedEvent = PlantingDateRequestSpeciesCreatedEventV1

data class PlantingDateRequestSpeciesDeletedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : EntityDeletedPersistentEvent, PlantingDateRequestSpeciesPersistentEvent

typealias PlantingDateRequestSpeciesDeletedEvent = PlantingDateRequestSpeciesDeletedEventV1

data class PlantingDateRequestSpeciesUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val scheduledPlantingDateId: ScheduledPlantingDateId,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : FieldsUpdatedPersistentEvent, PlantingDateRequestSpeciesPersistentEvent {
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

typealias PlantingDateRequestSpeciesUpdatedEvent = PlantingDateRequestSpeciesUpdatedEventV1

typealias PlantingDateRequestSpeciesUpdatedEventValues =
    PlantingDateRequestSpeciesUpdatedEventV1.Values
