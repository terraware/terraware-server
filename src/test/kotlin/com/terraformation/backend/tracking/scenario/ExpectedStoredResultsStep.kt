package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
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
  private val observationRow =
      dslContext.selectFrom(OBSERVATIONS).where(OBSERVATIONS.ID.eq(observationId)).fetchSingle()
  private val siteHistoryId = observationRow.plantingSiteHistoryId
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
  private val speciesTables: Map<Table<out Record>, Table<out Record>> =
      mapOf(
          OBSERVATION_SITE_RESULTS to OBSERVED_SITE_SPECIES_TOTALS,
          OBSERVATION_STRATUM_RESULTS to OBSERVED_STRATUM_SPECIES_TOTALS,
          OBSERVATION_SUBSTRATUM_RESULTS to OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
          OBSERVATION_PLOT_RESULTS to OBSERVED_PLOT_SPECIES_TOTALS,
      )
  private val speciesRows by lazy {
    speciesTables.values.associateWith { table ->
      dslContext
          .selectFrom(table)
          .where(
              table
                  .field("observation_id", OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.dataType)!!
                  .eq(observationId)
          )
          .fetch()
    }
  }
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
    private val expectedSpecies = mutableListOf<Record>()
    private var exactSpecies = false

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

    /** Checks the entire stored species row, including null metric columns. */
    fun species(
        number: Int,
        totalLive: Int = 0,
        totalDead: Int = 0,
        totalExisting: Int = 0,
        permanentLive: Int = totalLive,
        survivalRate: Int? = null,
    ) {
      val biomassSpecies =
          if (observationRow.observationTypeId == ObservationType.BiomassMeasurements)
              scenario.biomassSpecies.getValue(observationId to number)
          else null
      val speciesId =
          if (biomassSpecies == null) scenario.speciesIds.getValue(number)
          else biomassSpecies.speciesId
      val expected = identity.into(speciesTables.getValue(identity.table))
      expected.fromMap(
          identity.intoMap() +
              mapOf(
                  "biomass_species_id" to biomassSpecies?.id,
                  "certainty_id" to
                      if (speciesId == null) RecordedSpeciesCertainty.Other
                      else RecordedSpeciesCertainty.Known,
                  "species_id" to speciesId,
                  "species_name" to if (speciesId == null) biomassSpecies?.scientificName else null,
                  "total_live" to totalLive,
                  "total_dead" to totalDead,
                  "total_existing" to totalExisting,
                  "permanent_live" to permanentLive,
                  "survival_rate" to survivalRate,
              )
      )
      expectedSpecies.add(expected)
    }

    /** Also rejects unexpected species rows, including zero-count rows. */
    fun expectExactSpecies() {
      exactSpecies = true
    }

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
      if (exactSpecies || expectedSpecies.isNotEmpty()) {
        val actualSpecies =
            speciesRows.getValue(speciesTables.getValue(identity.table)).filter { row ->
              key.filterKeys { !it.endsWith("_history_id") }
                  .all { (column, expected) ->
                    row[column] == expected
                  }
            }
        if (exactSpecies) {
          assertions.add {
            assertEquals(
                expectedSpecies.map { it.intoMap() }.sortedBy { it.toString() },
                actualSpecies.map { it.intoMap() }.sortedBy { it.toString() },
                "$name exact species rows",
            )
          }
        } else {
          expectedSpecies.forEach { expected ->
            val speciesKey =
                listOf("biomass_species_id", "certainty_id", "species_id", "species_name")
                    .associateWith { expected[it] }
            assertions.add {
              assertEquals(
                  listOf(expected.intoMap()),
                  actualSpecies
                      .filter { row -> speciesKey.all { (column, value) -> row[column] == value } }
                      .map { it.intoMap() },
                  "$name species $speciesKey",
              )
            }
          }
        }
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
