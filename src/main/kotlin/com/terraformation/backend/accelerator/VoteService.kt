package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.model.CohortDepth
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@Named
class VoteService(
    private val cohortStore: CohortStore,
    private val dslContext: DSLContext,
    private val participantStore: ParticipantStore,
    private val voteStore: VoteStore,
) {
  @EventListener
  fun on(event: ParticipantProjectAddedEvent) {
    voteStore.assignVoters(event.projectId)
  }

  @EventListener
  fun on(event: CohortParticipantAddedEvent) {
    val participant = participantStore.fetchOneById(event.participantId)

    dslContext.transaction { _ -> participant.projectIds.forEach { voteStore.assignVoters(it) } }
  }

  @EventListener
  fun on(event: CohortPhaseUpdatedEvent) {
    val cohort = cohortStore.fetchOneById(event.cohortId, CohortDepth.Participant)

    dslContext.transaction { _ ->
      cohort.participantIds.forEach { participantId ->
        val participant = participantStore.fetchOneById(participantId)

        participant.projectIds.forEach { voteStore.assignVoters(it) }
      }
    }
  }
}
