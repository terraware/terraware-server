package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.eventlog.FieldsCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import java.math.BigDecimal

sealed interface StratumT0DensityPersistentEvent : PersistentEvent {
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val speciesId: SpeciesId
  val stratumId: StratumId
  val stratumName: String
}

data class StratumT0DensityCreatedEventV1(
    val density: BigDecimal,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
    override val stratumId: StratumId,
    override val stratumName: String,
) : FieldsCreatedPersistentEvent, StratumT0DensityPersistentEvent {
  override fun listInitialFields(messages: Messages) =
      listOfNotNull(createInitialField("density", messages.exactNumericValueOrNull(density)))
}

data class StratumT0DensityUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val speciesId: SpeciesId,
    override val stratumId: StratumId,
    override val stratumName: String,
) : FieldsUpdatedPersistentEvent, StratumT0DensityPersistentEvent {
  data class Values(val density: BigDecimal? = null)

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "density",
              messages.exactNumericValueOrNull(changedFrom.density),
              messages.exactNumericValueOrNull(changedTo.density),
          )
      )
}

typealias StratumT0DensityCreatedEvent = StratumT0DensityCreatedEventV1

typealias StratumT0DensityUpdatedEvent = StratumT0DensityUpdatedEventV1

typealias StratumT0DensityUpdatedEventValues = StratumT0DensityUpdatedEventV1.Values
