package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.model.ExistingParticipantModel
import com.terraformation.backend.accelerator.model.NewParticipantModel
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.db.accelerator.ParticipantId
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ParticipantService(
    private val dslContext: DSLContext,
    private val participantStore: ParticipantStore,
    private val projectStore: ProjectStore,
) {
  /** Creates a new participant, possibly assigning some existing projects to it. */
  fun create(newModel: NewParticipantModel): ExistingParticipantModel {
    return dslContext.transactionResult { _ ->
      val model = participantStore.create(newModel)

      newModel.projectIds.forEach { projectId ->
        projectStore.updateParticipant(projectId, model.id)
      }

      model.copy(projectIds = newModel.projectIds)
    }
  }

  /** Updates a participant's information, including which projects are assigned to it. */
  fun update(
      participantId: ParticipantId,
      updateFunc: (ExistingParticipantModel) -> ExistingParticipantModel,
  ) {
    dslContext.transaction { _ ->
      val existing = participantStore.fetchOneById(participantId)
      val updated = updateFunc(existing)

      participantStore.update(participantId, updateFunc)

      val projectsToAdd = updated.projectIds - existing.projectIds
      val projectsToRemove = existing.projectIds - updated.projectIds

      projectsToAdd.forEach { projectStore.updateParticipant(it, participantId) }
      projectsToRemove.forEach { projectStore.updateParticipant(it, null) }
    }
  }
}
