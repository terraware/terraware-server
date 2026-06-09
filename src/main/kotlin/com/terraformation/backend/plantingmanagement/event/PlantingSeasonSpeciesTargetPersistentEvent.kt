package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.util.nullIfEquals

sealed interface PlantingSeasonSpeciesTargetPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSeasonId: PlantingSeasonId
  val plantingSiteId: PlantingSiteId
  val speciesId: SpeciesId
  val stratumName: String
  val substratumHistoryId: SubstratumHistoryId
  val substratumId: SubstratumId
  val substratumName: String
}

data class PlantingSeasonSpeciesTargetCreatedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    val quantity: Int,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : EntityCreatedPersistentEvent, PlantingSeasonSpeciesTargetPersistentEvent

typealias PlantingSeasonSpeciesTargetCreatedEvent = PlantingSeasonSpeciesTargetCreatedEventV1

data class PlantingSeasonSpeciesTargetDeletedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : EntityDeletedPersistentEvent, PlantingSeasonSpeciesTargetPersistentEvent

typealias PlantingSeasonSpeciesTargetDeletedEvent = PlantingSeasonSpeciesTargetDeletedEventV1

data class PlantingSeasonSpeciesTargetUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSeasonId: PlantingSeasonId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
    override val stratumName: String,
    override val substratumHistoryId: SubstratumHistoryId,
    override val substratumId: SubstratumId,
    override val substratumName: String,
) : FieldsUpdatedPersistentEvent, PlantingSeasonSpeciesTargetPersistentEvent {
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

typealias PlantingSeasonSpeciesTargetUpdatedEvent = PlantingSeasonSpeciesTargetUpdatedEventV1

typealias PlantingSeasonSpeciesTargetUpdatedEventValues =
    PlantingSeasonSpeciesTargetUpdatedEventV1.Values
