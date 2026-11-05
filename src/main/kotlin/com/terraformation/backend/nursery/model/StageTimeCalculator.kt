package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.nursery.BatchStage
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

enum class StageTimeEventType {
  Created,
  ManualEdit,
  Other,
  Outplanting,
  Skipped,
  StageChange,
}

data class StageTimeEvent(
    val effectiveTime: Instant,
    val germinating: Int,
    val activeGrowth: Int,
    val hardeningOff: Int,
    val ready: Int,
    val type: StageTimeEventType,
) {
  fun quantity(stage: BatchStage): Int =
      when (stage) {
        BatchStage.Germinating -> germinating
        BatchStage.ActiveGrowth -> activeGrowth
        BatchStage.HardeningOff -> hardeningOff
        BatchStage.Ready -> ready
      }
}

data class StageTimeStats(
    val asOfTime: Instant,
    val cleared: Boolean,
    val countedDays: Double,
    val countedQuantity: Int,
    val finishedDays: Double = 0.0,
    val finishedQuantity: Int = 0,
    val inStage: Int,
    val openDays: Double,
    val stage: BatchStage,
) {
  fun averageDays(now: Instant): Double? =
      if (cleared || countedQuantity == 0) {
        null
      } else {
        (countedDays + inStage * StageTimeCalculator.daysBetween(asOfTime, now)) / countedQuantity
      }

  fun roundedDays(now: Instant): Int? =
      averageDays(now)?.let { if (it < 1) 0 else floor(it + 0.5).toInt() }

  val inProgress: Boolean
    get() = inStage > 0
}

/**
 * Replays a batch's quantity history to compute, for each stage, how many seedlings count toward
 * the stage's average time and how many seedling-days they've accumulated. Finished quantities and
 * seedling-days count only seedlings that complete a stage via a stage change or outplanting from
 * Ready, excluding seedlings still in the stage and non-completing removals. Skipped events such as
 * undone withdrawals don't change any quantities, and manual edits clear any stage whose quantity
 * they change.
 */
object StageTimeCalculator {
  fun daysBetween(start: Instant, end: Instant): Double =
      Duration.between(start, end).toMillis() / 86_400_000.0

  fun calculate(
      events: List<StageTimeEvent>,
      initialGerminatingStart: Instant?,
  ): Map<BatchStage, StageTimeStats> {
    val created = events.first()
    val germinatingStart =
        minOf(created.effectiveTime, initialGerminatingStart ?: created.effectiveTime)
    val states =
        BatchStage.entries.associateWith { stage ->
          val quantity = created.quantity(stage)
          val prehistoryDays =
              if (stage == BatchStage.Germinating) {
                quantity * daysBetween(germinatingStart, created.effectiveTime)
              } else {
                0.0
              }
          StageState(
              cleared = false,
              countedDays = prehistoryDays,
              countedQuantity = quantity,
              inStage = quantity,
              openDays = prehistoryDays,
          )
        }

    events.zipWithNext().forEach { (previous, event) ->
      val elapsedDays = daysBetween(previous.effectiveTime, event.effectiveTime)

      BatchStage.entries.forEach { stage ->
        val state = states.getValue(stage)
        val previousQuantity = previous.quantity(stage)
        val quantity = event.quantity(stage)
        val delta = if (event.type == StageTimeEventType.Skipped) 0 else quantity - previousQuantity
        val share =
            if (state.inStage > 0) {
              (state.openDays + state.inStage * elapsedDays) / state.inStage
            } else {
              0.0
            }
        val increase = maxOf(delta, 0)
        val decrease = maxOf(-delta, 0)
        val completes =
            event.type == StageTimeEventType.StageChange ||
                (event.type == StageTimeEventType.Outplanting && stage == BatchStage.Ready)

        state.openDays += state.inStage * elapsedDays - decrease * share
        state.countedDays += state.inStage * elapsedDays - if (completes) 0.0 else decrease * share
        state.countedQuantity += increase - if (completes) 0 else decrease
        state.inStage += delta
        if (completes) {
          state.finishedDays += decrease * share
          state.finishedQuantity += decrease
        }
        if (event.type == StageTimeEventType.ManualEdit && quantity != previousQuantity) {
          state.cleared = true
        }
      }
    }

    val asOfTime = events.last().effectiveTime
    return states.mapValues { (stage, state) ->
      StageTimeStats(
          asOfTime = asOfTime,
          cleared = state.cleared,
          countedDays = state.countedDays,
          countedQuantity = state.countedQuantity,
          finishedDays = state.finishedDays,
          finishedQuantity = state.finishedQuantity,
          inStage = state.inStage,
          openDays = state.openDays,
          stage = stage,
      )
    }
  }

  private data class StageState(
      var cleared: Boolean,
      var countedDays: Double,
      var countedQuantity: Int,
      var inStage: Int,
      var openDays: Double,
      var finishedDays: Double = 0.0,
      var finishedQuantity: Int = 0,
  )
}
