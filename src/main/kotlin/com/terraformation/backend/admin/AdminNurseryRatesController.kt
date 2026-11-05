package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.nursery.BatchStage
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.BATCH_QUANTITY_HISTORY
import com.terraformation.backend.db.nursery.tables.references.BATCH_STAGE_DETAILS
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.nursery.db.BatchStore
import org.jooq.DSLContext
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
class AdminNurseryRatesController(
    private val dslContext: DSLContext,
    private val batchStore: BatchStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @PostMapping("/backfillNurseryRates")
  fun backfillNurseryRates(redirectAttributes: RedirectAttributes): String {
    val candidateBatchIds =
        with(BATCHES) {
          dslContext
              .select(ID)
              .from(BATCHES)
              .join(BATCH_STAGE_DETAILS)
              .on(
                  BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID)
                      .and(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Germinating))
              )
              .where(GERMINATION_RATE.isNull.and(BATCH_STAGE_DETAILS.QUANTITY.eq(0)))
              .or(LOSS_RATE.isNull)
              .fetch(ID.asNonNullable())
        }

    log.info("Recalculating rates for ${candidateBatchIds.size} batches")

    val errorMessages = mutableListOf<String>()

    systemUser.run {
      candidateBatchIds.forEach { batchId ->
        try {
          batchStore.updateRates(batchId)
        } catch (e: Exception) {
          errorMessages.add("Unable to recalculate rates for $batchId: $e")
          log.warn("Unable to recalculate rates for $batchId", e)
        }
      }
    }

    if (errorMessages.isNotEmpty()) {
      redirectAttributes.failureMessage = "Some recalculations failed"
      redirectAttributes.failureDetails = errorMessages
    } else {
      val batchesStillWithoutRates =
          with(BATCHES) {
            dslContext
                .selectCount()
                .from(BATCHES)
                .join(BATCH_STAGE_DETAILS)
                .on(
                    BATCH_STAGE_DETAILS.BATCH_ID.eq(BATCHES.ID)
                        .and(BATCH_STAGE_DETAILS.STAGE_ID.eq(BatchStage.Germinating))
                )
                .where(GERMINATION_RATE.isNull.and(BATCH_STAGE_DETAILS.QUANTITY.eq(0)))
                .or(LOSS_RATE.isNull)
                .fetchSingle()
          }
      val batchesUpdated = candidateBatchIds.size - batchesStillWithoutRates.value1()

      redirectAttributes.successMessage = "Populated rates for $batchesUpdated batches"
    }

    return "redirect:/admin/"
  }

  @PostMapping("/backfillTimeInStage")
  fun backfillTimeInStage(redirectAttributes: RedirectAttributes): String {
    val candidateBatchIds =
        dslContext
            .selectDistinct(BATCH_QUANTITY_HISTORY.BATCH_ID)
            .from(BATCH_QUANTITY_HISTORY)
            .where(BATCH_QUANTITY_HISTORY.EFFECTIVE_TIME.isNull)
            .fetch(BATCH_QUANTITY_HISTORY.BATCH_ID.asNonNullable())

    log.info("Backfilling time in stage for ${candidateBatchIds.size} batches")

    val errorMessages = mutableListOf<String>()

    val recalculatedBatchCount = systemUser.run {
      candidateBatchIds.forEach { batchId ->
        try {
          batchStore.backfillTimeInStage(batchId)
        } catch (e: Exception) {
          errorMessages.add("Unable to backfill time in stage for $batchId: $e")
          log.warn("Unable to backfill time in stage for $batchId", e)
        }
      }

      val finishedCandidateBatchIds = batchStore.fetchBatchIdsNeedingFinishedTimeInStage()
      log.info("Recalculating finished time in stage for ${finishedCandidateBatchIds.size} batches")

      finishedCandidateBatchIds.forEach { batchId ->
        try {
          batchStore.recalculateTimeInStage(batchId)
        } catch (e: Exception) {
          errorMessages.add("Unable to recalculate finished time in stage for $batchId: $e")
          log.warn("Unable to recalculate finished time in stage for $batchId", e)
        }
      }
      finishedCandidateBatchIds.size
    }

    if (errorMessages.isNotEmpty()) {
      redirectAttributes.failureMessage = "Some time-in-stage backfills or recalculations failed"
      redirectAttributes.failureDetails = errorMessages
    } else {
      redirectAttributes.successMessage =
          "Backfilled time in stage for ${candidateBatchIds.size} batches and recalculated " +
              "finished time in stage for $recalculatedBatchCount batches"
    }

    return "redirect:/admin/"
  }
}
