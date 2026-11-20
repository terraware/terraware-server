package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.eventlog.EntityUpdatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent.CalculatedValue
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.tracking.model.ConfidenceLevel
import com.terraformation.backend.tracking.model.StratumPlotCounts
import com.terraformation.backend.util.equalsIgnoreScale
import java.math.BigDecimal
import java.math.MathContext

sealed interface StratumPlotSelectionSettingsPersistentEvent : EntityUpdatedPersistentEvent {
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val stratumId: StratumId
  val stratumName: String
}

data class StratumPlotSelectionSettingsChangedEventV1(
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val stratumId: StratumId,
    override val stratumName: String,
    val source: Source,
    val settings: List<Settings>,
    val observationId: ObservationId? = null,
    val measuredVariability: BigDecimal? = null,
) : StratumPlotSelectionSettingsPersistentEvent, FieldsUpdatedPersistentEvent {
  override fun listUpdatedFields(messages: Messages) = settings.flatMap { setting ->
    val from = setting.changedFrom
    val to = setting.changedTo
    val locale = currentLocale()
    val calculatedValues =
        listOf(
            CalculatedValue("numPermanentPlots", listOf(setting.numPermanentPlots.toString())),
            CalculatedValue("numTemporaryPlots", listOf(setting.numTemporaryPlots.toString())),
        )

    listOfNotNull(
            createUpdatedField(
                "mode",
                from.mode?.getDisplayName(locale),
                to.mode?.getDisplayName(locale),
            ),
            createUpdatedField(
                "plotPrecisionLevel",
                from.plotPrecisionLevel?.getDisplayName(locale),
                to.plotPrecisionLevel?.getDisplayName(locale),
            ),
            createDerivedField(
                "confidenceLevel",
                "studentsT",
                from.studentsT,
                to.studentsT,
                messages,
            ) {
              plotSettingsConfidence(it)?.let { level -> "${level.percent}%" }
            },
            createUpdatedField(
                "errorMargin",
                messages.exactNumericValueOrNull(from.errorMargin)?.plus("%"),
                messages.exactNumericValueOrNull(to.errorMargin)?.plus("%"),
            ),
            createDerivedField(
                "variability",
                "variance",
                from.variance,
                to.variance,
                messages,
            ) {
              messages.numericValueOrNull(plotSettingsVariability(it))?.plus("%")
            },
            createUpdatedField(
                "numPermanentPlots",
                from.numPermanentPlots?.toString(),
                to.numPermanentPlots?.toString(),
            ),
            createUpdatedField(
                "numTemporaryPlots",
                from.numTemporaryPlots?.toString(),
                to.numTemporaryPlots?.toString(),
            ),
        )
        .map {
          it.copy(group = setting.observationType.jsonValue, calculatedValues = calculatedValues)
        }
  }

  private fun createDerivedField(
      fieldName: String,
      rawFieldName: String,
      from: BigDecimal?,
      to: BigDecimal?,
      messages: Messages,
      format: (BigDecimal) -> String?,
  ): FieldsUpdatedPersistentEvent.UpdatedField? {
    val formattedFrom = from?.let(format)
    val formattedTo = to?.let(format)
    return if ((from != null && formattedFrom == null) || (to != null && formattedTo == null)) {
      createUpdatedField(
          rawFieldName,
          messages.exactNumericValueOrNull(from),
          messages.exactNumericValueOrNull(to),
      )
    } else {
      createUpdatedField(fieldName, formattedFrom, formattedTo)
    }
  }

  enum class Source {
    UserEdit,
    AcceptedRecommendation,
    Terraformation,
  }

  data class Settings(
      val observationType: ObservationType,
      val changedFrom: Values,
      val changedTo: Values,
      val numPermanentPlots: Int,
      val numTemporaryPlots: Int,
  ) {
    companion object {
      fun of(
          type: ObservationType,
          counts: StratumPlotCounts,
          previous: StratumPlotCounts? = null,
      ): Settings {
        val from = previous?.let { changedValues(it, counts) } ?: Values()
        val to = changedValues(counts, previous)
        val includeCountChanges =
            counts.mode == PlotSelectionMode.Direct ||
                previous?.mode == PlotSelectionMode.Direct ||
                (from == Values() && to == Values())

        return Settings(
            observationType = type,
            changedFrom =
                if (includeCountChanges && previous != null) from.withCountChanges(previous, counts)
                else from,
            changedTo = if (includeCountChanges) to.withCountChanges(counts, previous) else to,
            numPermanentPlots = counts.numPermanentPlots,
            numTemporaryPlots = counts.numTemporaryPlots,
        )
      }

      private fun changedValues(counts: StratumPlotCounts, other: StratumPlotCounts?) =
          Values(
              mode = counts.mode.takeUnless { it == other?.mode },
              plotPrecisionLevel =
                  counts.plotPrecisionLevel.takeUnless { it == other?.plotPrecisionLevel },
              variance =
                  counts.variance.takeUnless {
                    other != null && it.equalsIgnoreScale(other.variance)
                  },
              studentsT =
                  counts.studentsT.takeUnless {
                    other != null && it.equalsIgnoreScale(other.studentsT)
                  },
              errorMargin =
                  counts.errorMargin.takeUnless {
                    other != null && it.equalsIgnoreScale(other.errorMargin)
                  },
          )

      private fun Values.withCountChanges(counts: StratumPlotCounts, other: StratumPlotCounts?) =
          copy(
              numPermanentPlots =
                  counts.numPermanentPlots.takeUnless { it == other?.numPermanentPlots },
              numTemporaryPlots =
                  counts.numTemporaryPlots.takeUnless { it == other?.numTemporaryPlots },
          )
    }
  }

  data class Values(
      val mode: PlotSelectionMode? = null,
      val plotPrecisionLevel: PlotPrecisionLevel? = null,
      val variance: BigDecimal? = null,
      val studentsT: BigDecimal? = null,
      val errorMargin: BigDecimal? = null,
      val numPermanentPlots: Int? = null,
      val numTemporaryPlots: Int? = null,
  )
}

typealias StratumPlotSelectionSettingsChangedEvent = StratumPlotSelectionSettingsChangedEventV1

typealias StratumPlotSelectionSettingsChangedEventSource =
    StratumPlotSelectionSettingsChangedEventV1.Source

typealias StratumPlotSelectionSettingsChangedEventSettings =
    StratumPlotSelectionSettingsChangedEventV1.Settings

typealias StratumPlotSelectionSettingsChangedEventValues =
    StratumPlotSelectionSettingsChangedEventV1.Values

private fun plotSettingsConfidence(studentsT: BigDecimal) =
    ConfidenceLevel.entries.firstOrNull { it.plotCountStudentsT.equalsIgnoreScale(studentsT) }

private fun plotSettingsVariability(variance: BigDecimal) =
    variance.takeIf { it.signum() >= 0 }?.sqrt(MathContext.DECIMAL128)
