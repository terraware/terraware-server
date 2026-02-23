package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.ProjectPhaseUpdatedEvent
import com.terraformation.backend.customer.model.SystemUser
import jakarta.inject.Named
import org.springframework.context.event.EventListener

@Named
class VoteService(
    private val systemUser: SystemUser,
    private val voteStore: VoteStore,
) {
  @EventListener
  fun on(event: ProjectPhaseUpdatedEvent) {
    systemUser.run { voteStore.assignVoters(event.projectId) }
  }
}
