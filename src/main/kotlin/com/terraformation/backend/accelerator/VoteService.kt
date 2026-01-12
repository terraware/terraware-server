package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.customer.model.SystemUser
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class VoteService(
    private val cohortStore: CohortStore,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
    private val voteStore: VoteStore,
) {
  @EventListener
  fun on(event: CohortProjectAddedEvent) {
    systemUser.run { voteStore.assignVoters(event.projectId) }
  }

  @EventListener
  fun on(event: CohortPhaseUpdatedEvent) {
    systemUser.run {
      val cohort = cohortStore.fetchOneById(event.cohortId, CohortDepth.Project)

      dslContext.transaction { _ -> cohort.projectIds.forEach { voteStore.assignVoters(it) } }
    }
  }
}
