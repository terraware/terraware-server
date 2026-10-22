package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.FieldsCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import java.math.BigDecimal

sealed interface PlotT0DensityPersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val plotNumber: Long
  val speciesId: SpeciesId
}

data class PlotT0DensityCreatedEventV1(
    val density: BigDecimal,
    override val monitoringPlotId: MonitoringPlotId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val plotNumber: Long,
    override val speciesId: SpeciesId,
) : FieldsCreatedPersistentEvent, PlotT0DensityPersistentEvent {
  override fun listInitialFields(messages: Messages) =
      listOfNotNull(createInitialField("density", messages.exactNumericValueOrNull(density)))
}

data class PlotT0DensityUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val plotNumber: Long,
    override val speciesId: SpeciesId,
) : FieldsUpdatedPersistentEvent, PlotT0DensityPersistentEvent {
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

typealias PlotT0DensityCreatedEvent = PlotT0DensityCreatedEventV1

typealias PlotT0DensityUpdatedEvent = PlotT0DensityUpdatedEventV1

typealias PlotT0DensityUpdatedEventValues = PlotT0DensityUpdatedEventV1.Values
