package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSiteResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumResultsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationSubstratumResultsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import org.jooq.Record
import org.jooq.Table
import org.jooq.TableRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertAll

class ExpectedStoredResultsStep(val scenario: ObservationScenario, val observation: Int) :
    NodeWithChildren<ScenarioNode>() {
  private val dslContext = scenario.test.dslContext
  private val observationId = scenario.observationIds.getValue(observation)
  private val siteHistoryId =
      dslContext
          .select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(observationId))
          .fetchSingle(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
  private val rows =
      listOf<Table<out Record>>(
              OBSERVATION_SITE_RESULTS,
              OBSERVATION_STRATUM_RESULTS,
              OBSERVATION_SUBSTRATUM_RESULTS,
              OBSERVATION_PLOT_RESULTS,
          )
          .associateWith { table ->
            dslContext
                .selectFrom(table)
                .where(
                    table
                        .field("observation_id", OBSERVATION_SITE_RESULTS.OBSERVATION_ID.dataType)!!
                        .eq(observationId)
                )
                .fetch()
          }
  private val identities = mutableListOf<TableRecord<*>>()
  private val assertions = mutableListOf<() -> Unit>()
  private var exact = false

  /** Reject results that have additional data beyond what was expected. */
  fun expectExactResults() {
    exact = true
  }

  fun site(init: ExpectedResult.() -> Unit) =
      initChild(
          ExpectedResult(
              ObservationSiteResultsRecord(
                  observationId = observationId,
                  plantingSiteHistoryId = siteHistoryId,
                  plantingSiteId = scenario.plantingSiteId,
              )
          ),
          init,
      )

  fun stratum(number: Int, init: Stratum.() -> Unit): Stratum {
    val stratumHistoryId =
        dslContext
            .select(STRATUM_HISTORIES.ID)
            .from(STRATUM_HISTORIES)
            .where(STRATUM_HISTORIES.STRATUM_ID.eq(scenario.stratumIds.getValue(number)))
            .and(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(siteHistoryId))
            .fetchSingle(STRATUM_HISTORIES.ID)!!
    return initChild(Stratum(number, stratumHistoryId), init)
  }

  inner class Stratum(number: Int, private val stratumHistoryId: StratumHistoryId) :
      ExpectedResult(
          ObservationStratumResultsRecord(
              observationId = observationId,
              stratumHistoryId = stratumHistoryId,
              stratumId = scenario.stratumIds.getValue(number),
          )
      ) {
    fun substratum(number: Int, init: Substratum.() -> Unit): Substratum {
      val substratumHistoryId =
          dslContext
              .select(SUBSTRATUM_HISTORIES.ID)
              .from(SUBSTRATUM_HISTORIES)
              .where(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(scenario.substratumIds.getValue(number)))
              .and(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(stratumHistoryId))
              .fetchSingle(SUBSTRATUM_HISTORIES.ID)!!
      return initChild(Substratum(number, substratumHistoryId), init)
    }

    inner class Substratum(number: Int, substratumHistoryId: SubstratumHistoryId) :
        ExpectedResult(
            ObservationSubstratumResultsRecord(
                observationId = observationId,
                substratumHistoryId = substratumHistoryId,
                substratumId = scenario.substratumIds.getValue(number),
            )
        ) {
      fun plot(number: Long, init: ExpectedResult.() -> Unit): ExpectedResult {
        val plotId = scenario.getMonitoringPlotId(number)
        val plotHistoryId =
            dslContext
                .select(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID)
                .from(OBSERVATION_PLOTS)
                .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId))
                .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
                .fetchSingle(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID)
        return initChild(
            ExpectedResult(
                ObservationPlotResultsRecord(
                    monitoringPlotHistoryId = plotHistoryId,
                    monitoringPlotId = plotId,
                    observationId = observationId,
                )
            ),
            init,
        )
      }
    }
  }

  open inner class ExpectedResult(private val identity: TableRecord<*>) :
      NodeWithChildren<ScenarioNode>() {
    private val key = identity.intoMap().filterValues { it != null }
    private val name = "${identity.table.name} $key"
    private val matches =
        rows.getValue(identity.table).filter { row ->
          key.all { (column, expected) -> row[column] == expected }
        }
    private val actual = matches.singleOrNull()
    private val fields = mutableMapOf<String, Any?>()

    init {
      identities.add(identity)
    }

    fun counts(totalLive: Int, totalDead: Int = 0, totalExisting: Int = 0, permanentLive: Int) {
      expect("total_live", totalLive)
      expect("total_dead", totalDead)
      expect("total_existing", totalExisting)
      expect("permanent_live", permanentLive)
    }

    fun pending(expected: Boolean) = expect("needs_recalculation", expected)

    fun plantingDensity(expected: Int?) = expect("plant_density", expected)

    fun survivalRate(expected: Int?) = expect("survival_rate", expected)

    fun noPlantMonitoringMetrics() {
      listOf(
              "plant_density",
              "plant_density_std_dev",
              "observed_density",
              "survival_rate",
              "survival_rate_std_dev",
              "survival_rate_area",
          )
          .filter { identity.field(it) != null }
          .forEach { expect(it, null) }
    }

    private fun expect(column: String, expected: Any?) {
      requireNotNull(identity.field(column)) { "${identity.table.name} has no $column column" }
      fields[column] = expected
    }

    override fun finish() {
      assertions.add { assertEquals(1, matches.size, "$name row count") }
      fields.forEach { (column, expected) ->
        assertions.add { assertEquals(expected, actual?.get(column), "$name $column") }
      }
    }
  }

  override fun finish() {
    if (exact) {
      rows.forEach { (table, actual) ->
        val keyFields = table.primaryKey!!.fields
        fun keys(records: List<Record>) =
            records.map { row -> keyFields.map { row[it] } }.sortedBy { it.toString() }
        assertions.add {
          assertEquals(
              keys(identities.filter { it.table == table }),
              keys(actual),
              "${table.name} exact rows",
          )
        }
      }
    }
    assertAll("Stored observation $observation", assertions)
  }
}
