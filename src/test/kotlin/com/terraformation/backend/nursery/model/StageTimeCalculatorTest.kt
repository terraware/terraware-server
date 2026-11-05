package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.nursery.BatchStage
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

internal class StageTimeCalculatorTest {
  @MethodSource("examples")
  @ParameterizedTest(name = "{0}")
  fun `matches spreadsheet example`(example: Example) {
    val actual = StageTimeCalculator.calculate(example.events, example.initialGerminatingStart)
    val assertions: List<() -> Unit> =
        BatchStage.entries.flatMapIndexed { index, stage ->
          val expected = example.expected[index]
          val stats = actual.getValue(stage)
          val label = "${example.name} / ${stage.jsonValue}"
          val countedDays =
              stats.countedDays +
                  stats.inStage * StageTimeCalculator.daysBetween(stats.asOfTime, example.asOf)
          val averageDays = stats.averageDays(example.asOf)

          listOf(
              { assertEquals(expected.inStage, stats.inStage, "$label in stage") },
              { assertEquals(expected.countedQuantity, stats.countedQuantity, "$label counted") },
              { assertEquals(expected.countedDays, countedDays, 1e-6, "$label seedling-days") },
              {
                assertDoubleEquals(expected.averageDays, averageDays, "$label average")
              },
              {
                assertEquals(
                    expected.roundedDays,
                    stats.roundedDays(example.asOf),
                    "$label rounded",
                )
              },
              { assertEquals(expected.cleared, stats.cleared, "$label cleared") },
              { assertEquals(expected.inProgress, stats.inProgress, "$label in progress") },
          )
        }
    assertAll(example.name, *assertions.toTypedArray())
  }

  @MethodSource("finishedExamples")
  @ParameterizedTest(name = "{0}")
  fun `counts only completed seedlings`(
      name: String,
      eventRows: List<String>,
      expected: List<Pair<Number, Double>>,
  ) {
    val actual = StageTimeCalculator.calculate(eventRows.map(::parseEvent), instant("01-01"))
    assertAll(
        BatchStage.entries.mapIndexed { index, stage ->
          {
            val (quantity, days) = expected[index]
            val stats = actual.getValue(stage)
            assertEquals(
                quantity.toDouble(),
                stats.finishedQuantity,
                if (quantity.toDouble() == 0.0) 0.0 else 1e-6,
                "$name / $stage finished quantity",
            )
            assertEquals(days, stats.finishedDays, 1e-6, "$name / $stage finished days")
          }
        }
    )
  }

  companion object {
    /**
     * Each example is a pipe-separated string with five fields:
     * ```
     * name|asOf|initialGerminatingStart|events|expected
     * ```
     * - `asOf` is the time at which results are evaluated; in-progress stages accrue time up to it.
     * - `initialGerminatingStart` is the earlier of the Seeds Sown and Germination Started dates,
     *   or `-` if neither is set.
     * - `events` is a semicolon-separated list of events, each in this format, where the quantities
     *   are the batch's totals after the event:
     *   ```
     *   time,type,germinating,activeGrowth,hardeningOff,ready
     *   ```
     *
     *   Types are `C` (created), `S` (stage change), `M` (manual edit), `O` (outplanting
     *   withdrawal), `K` (skipped, e.g., an undone withdrawal and its undo), and `X` (any other
     *   quantity change).
     * - `expected` is a semicolon-separated list of four results in stage order (Germinating,
     *   Active Growth, Hardening Off, Ready), each in this format:
     *   ```
     *   inStage,countedQuantity,countedDays,averageDays,roundedDays,cleared,inProgress
     *   ```
     *
     *   A null average or rounded value is `-`. `countedDays` includes time accrued up to `asOf`.
     *
     * Times are `MM-DD` or `MM-DDTHH:MM` in 2026, UTC.
     */
    @JvmStatic
    fun examples(): List<Example> =
        listOf(
                "Spec example|02-05|-|01-01,C,100,0,0,0;01-06,X,90,0,0,0;01-11,S,30,60,0,0;01-21,S,0,90,0,0;01-31,S,0,0,0,90|0,90,1200,13.33333333,13,false,false;0,90,1500,16.66666667,17,false,false;0,0,0,-,-,false,false;90,90,450,5,5,false,true",
                "Full path|04-01|-|01-01,C,200,0,0,0;01-15,S,0,200,0,0;02-14,S,0,0,200,0;02-28,S,0,0,0,200;03-12,O,0,0,0,0|0,200,2800,14,14,false,false;0,200,6000,30,30,false,false;0,200,2800,14,14,false,false;0,200,2400,12,12,false,false",
                "Withdrawals|03-12|-|01-01,C,100,0,0,0;01-04,X,90,0,0,0;01-05,X,80,0,0,0;01-06,X,70,0,0,0;01-07,O,60,0,0,0;01-21,S,0,60,0,0;01-26,O,0,40,0,0;02-10,S,0,0,0,40;02-20,X,0,0,0,30;03-02,O,0,0,0,10|0,60,1200,20,20,false,false;0,40,800,20,20,false,false;0,0,0,-,-,false,false;10,30,700,23.33333333,23,false,true",
                "Even spreading|02-01|-|01-01,C,100,0,0,0;01-11,X,150,0,0,0;01-21,X,120,0,0,0;01-31,S,0,120,0,0|0,120,3200,26.66666667,27,false,false;120,120,120,1,1,false,true;0,0,0,-,-,false,false;0,0,0,-,-,false,false",
                "Manual edit|02-10|-|01-01,C,100,0,0,0;01-11,S,40,60,0,0;01-16,M,45,60,0,0;01-21,S,0,105,0,0;01-31,S,0,0,0,105|0,105,1425,-,-,true,false;0,105,1650,15.71428571,16,false,false;0,0,0,-,-,false,false;105,105,1050,10,10,false,true",
                "Undone withdrawal|01-16|-|01-01,C,100,0,0,0;01-06,K,80,0,0,0;01-07,K,100,0,0,0;01-11,S,0,100,0,0|0,100,1000,10,10,false,false;100,100,500,5,5,false,true;0,0,0,-,-,false,false;0,0,0,-,-,false,false",
                "Batch emptied|01-31|-|01-01,C,100,0,0,0;01-11,S,40,60,0,0;01-16,S,40,30,0,30;01-21,O,40,30,0,20;01-26,M,0,0,0,0|0,60,600,-,-,true,false;0,30,150,-,-,true,false;0,0,0,-,-,false,false;0,10,50,-,-,true,false",
                "Backdated start|02-20|01-03|01-21,C,50,50,0,0;02-10,S,0,100,0,0|0,50,1900,38,38,false,false;100,100,2000,20,20,false,true;0,0,0,-,-,false,false;0,0,0,-,-,false,false",
                "Backdated start with withdrawal|01-31|01-01|01-11,C,100,0,0,0;01-11,X,50,0,0,0;01-21,S,0,50,0,0|0,50,1000,20,20,false,false;50,50,500,10,10,false,true;0,0,0,-,-,false,false;0,0,0,-,-,false,false",
                "User date after recorded|02-10|01-11|01-01,C,100,0,0,0;01-31,S,0,100,0,0|0,100,3000,30,30,false,false;100,100,1000,10,10,false,true;0,0,0,-,-,false,false;0,0,0,-,-,false,false",
                "Skipped stages|01-31|-|01-01,C,100,0,0,0;01-13,S,40,0,60,0;01-22,S,0,0,60,40;01-27,S,0,0,0,100|0,100,1560,15.6,16,false,false;0,0,0,-,-,false,false;0,60,840,14,14,false,false;100,100,600,6,6,false,true",
                "Rounding|01-06T12:00|-|01-01,C,100,0,0,0;01-02T12:00,S,0,100,0,0;01-04T23:45,S,0,0,100,0;01-05T12:00,S,0,0,0,100|0,100,150,1.5,2,false,false;0,100,248.9583333,2.489583333,2,false,false;0,100,51.04166667,0.5104166667,0,false,false;100,100,100,1,1,false,true",
                "Backdated withdrawals|08-29|-|01-01,C,100,0,0,0;01-11,S,0,100,0,0;01-31,S,0,0,0,100;02-10,O,0,0,0,60;02-15,X,0,0,0,50;02-15,O,0,0,0,20;08-19T12:00,O,0,0,0,0|0,100,1000,10,10,false,false;0,100,2000,20,20,false,false;0,0,0,-,-,false,false;0,90,4860,54,54,false,false",
            )
            .map(::parseExample)

    // Events use the spreadsheet parser's time,type,germinating,activeGrowth,hardeningOff,ready
    // format. Expected pairs are finished quantity to days in Germinating, Active Growth,
    // Hardening Off, Ready order.
    @JvmStatic
    fun finishedExamples() =
        listOf(
            Arguments.of(
                "Large starting pool does not produce fractional finished seedlings",
                listOf("01-01,C,0,999999937,0,0", "01-11,S,0,3,999999934,0"),
                listOf(0 to 0.0, 0 to 0.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Emptying a mixed pool leaves no starting seedlings when refilled",
                listOf(
                    "01-01,C,31,3,0,0",
                    "01-02,S,0,34,0,0",
                    "01-03,X,0,28,0,0",
                    "01-04,X,0,0,0,0",
                    "01-05,X,0,7,0,0",
                    "01-06,S,0,0,7,0",
                ),
                listOf(31 to 31.0, 7 to 7.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Starting Active Growth seedlings leave proportionally",
                listOf(
                    "01-01,C,100,100,0,0",
                    "01-11,S,0,200,0,0",
                    "01-31,S,0,100,100,0",
                    "02-10,S,0,0,200,0",
                ),
                listOf(100 to 1000.0, 100 to 2500.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Uneven pools with non-completing and completing departures",
                listOf(
                    "01-01,C,10,20,0,0",
                    "01-11,S,0,30,0,0",
                    "01-21,X,0,27,0,0",
                    "01-31,S,0,22,5,0",
                ),
                listOf(10 to 100.0, 5.0 / 3 to 100.0 / 3, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Starting Ready seedlings do not count when outplanted",
                listOf("01-01,C,0,0,0,100", "01-11,O,0,0,0,0"),
                listOf(0 to 0.0, 0 to 0.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Creation and open stages",
                listOf("01-01,C,10,20,30,40", "01-11,X,10,20,30,40"),
                listOf(0 to 0.0, 0 to 0.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Partial completions and non-completing removals",
                listOf(
                    "01-01,C,100,0,0,0",
                    "01-06,X,80,0,0,0",
                    "01-11,S,40,40,0,0",
                    "01-16,X,20,30,0,0",
                    "01-21,S,0,20,30,0",
                    "01-26,S,0,0,20,30",
                    "01-31,O,0,0,10,10",
                ),
                listOf(60 to 800.0, 30 to 400.0, 10 to 50.0, 20 to 100.0),
            ),
            Arguments.of(
                "Mixed-age arrivals and skipped withdrawals",
                listOf(
                    "01-01,C,100,0,0,0",
                    "01-11,X,200,0,0,0",
                    "01-16,K,100,0,0,0",
                    "01-17,K,200,0,0,0",
                    "01-21,S,100,100,0,0",
                    "01-26,S,0,200,0,0",
                ),
                listOf(200 to 3500.0, 0 to 0.0, 0 to 0.0, 0 to 0.0),
            ),
            Arguments.of(
                "Manual edits do not complete stages",
                listOf("01-01,C,10,20,30,40", "01-11,M,0,0,0,0"),
                listOf(0 to 0.0, 0 to 0.0, 0 to 0.0, 0 to 0.0),
            ),
        )

    data class Example(
        val asOf: Instant,
        val events: List<StageTimeEvent>,
        val expected: List<ExpectedStage>,
        val initialGerminatingStart: Instant?,
        val name: String,
    ) {
      override fun toString() = name
    }

    data class ExpectedStage(
        val averageDays: Double?,
        val cleared: Boolean,
        val countedDays: Double,
        val countedQuantity: Int,
        val inProgress: Boolean,
        val inStage: Int,
        val roundedDays: Int?,
    )

    private fun parseExample(line: String): Example {
      val (name, asOf, start, eventRows, expectedRows) = line.split('|')
      return Example(
          asOf = instant(asOf),
          events = eventRows.split(';').map(::parseEvent),
          expected = expectedRows.split(';').map(::parseExpected),
          initialGerminatingStart = start.takeUnless { it == "-" }?.let(::instant),
          name = name,
      )
    }

    private fun parseEvent(row: String): StageTimeEvent {
      val fields = row.split(',')
      val (date, type) = fields
      val (germinating, activeGrowth, hardeningOff, ready) = fields.drop(2).map(String::toInt)
      return StageTimeEvent(
          activeGrowth = activeGrowth,
          effectiveTime = instant(date),
          germinating = germinating,
          hardeningOff = hardeningOff,
          ready = ready,
          type =
              when (type) {
                "C" -> StageTimeEventType.Created
                "S" -> StageTimeEventType.StageChange
                "M" -> StageTimeEventType.ManualEdit
                "O" -> StageTimeEventType.Outplanting
                "K" -> StageTimeEventType.Skipped
                "X" -> StageTimeEventType.Other
                else -> error("Unknown event type: $type")
              },
      )
    }

    private fun parseExpected(row: String): ExpectedStage {
      val fields = row.split(',')
      return ExpectedStage(
          averageDays = fields[3].toDoubleOrNull(),
          cleared = fields[5].toBoolean(),
          countedDays = fields[2].toDouble(),
          countedQuantity = fields[1].toInt(),
          inProgress = fields[6].toBoolean(),
          inStage = fields[0].toInt(),
          roundedDays = fields[4].toIntOrNull(),
      )
    }

    private fun instant(value: String): Instant {
      val date = value.substringBefore('T')
      val time = value.substringAfter('T', "00:00:00")
      val timeWithSeconds = if (time.count { it == ':' } == 1) "$time:00" else time
      return Instant.parse("2026-${date}T${timeWithSeconds}Z")
    }
  }
}

private fun assertDoubleEquals(expected: Double?, actual: Double?, message: String) {
  if (expected == null) {
    assertNull(actual, message)
  } else {
    assertEquals(expected, actual!!, 1e-6, message)
  }
}
