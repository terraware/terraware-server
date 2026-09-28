package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.nursery.tables.references.BATCHES
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
              .where(GERMINATION_RATE.isNull.and(GERMINATING_QUANTITY.eq(0)))
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
                .where(GERMINATION_RATE.isNull.and(GERMINATING_QUANTITY.eq(0)))
                .or(LOSS_RATE.isNull)
                .fetchSingle()
          }
      val batchesUpdated = candidateBatchIds.size - batchesStillWithoutRates.value1()

      redirectAttributes.successMessage = "Populated rates for $batchesUpdated batches"
    }

    return "redirect:/admin/"
  }
}
