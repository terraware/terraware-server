package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import java.time.LocalDate

sealed interface PlotT0ObservationPersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val plotNumber: Long
}

data class PlotT0ObservationUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId?,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val plotNumber: Long,
) : FieldsUpdatedPersistentEvent, PlotT0ObservationPersistentEvent {
  data class Values(
      val observationId: ObservationId? = null,
      val observationStartDate: LocalDate? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "observationStartDate",
              changedFrom.observationStartDate?.toString(),
              changedTo.observationStartDate?.toString(),
          ),
          if (
              changedFrom.observationStartDate == changedTo.observationStartDate &&
                  changedFrom.observationId != changedTo.observationId
          ) {
            createUpdatedField(
                "observationId",
                changedFrom.observationId?.toString(),
                changedTo.observationId?.toString(),
            )
          } else {
            null
          },
      )
}

typealias PlotT0ObservationUpdatedEvent = PlotT0ObservationUpdatedEventV1

typealias PlotT0ObservationUpdatedEventValues = PlotT0ObservationUpdatedEventV1.Values
