package com.terraformation.backend.accelerator

import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortParticipantRemovedEvent
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

/**
 * Temporary event listeners to keep the cohort and phase values up to date on the projects table
 * based on the project's participant. This will go away once we've removed participants and
 * cohorts.
 */
@Named
class ProjectPhaseService(private val dslContext: DSLContext) {
  @EventListener
  fun on(event: CohortParticipantAddedEvent) {
    val phase = dslContext.fetchValue(COHORTS.PHASE_ID, COHORTS.ID.eq(event.cohortId))

    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .set(COHORT_ID, event.cohortId)
          .set(PHASE_ID, phase)
          .where(PARTICIPANT_ID.eq(event.participantId))
          .execute()
    }
  }

  @EventListener
  fun on(event: CohortParticipantRemovedEvent) {
    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .setNull(COHORT_ID)
          .setNull(PHASE_ID)
          .where(PARTICIPANT_ID.eq(event.participantId))
          .execute()
    }
  }

  @EventListener
  fun on(event: CohortPhaseUpdatedEvent) {
    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .set(PHASE_ID, event.newPhase)
          .where(COHORT_ID.eq(event.cohortId))
          .execute()
    }
  }

  @EventListener
  fun on(event: ParticipantProjectAddedEvent) {
    val (cohortId, phase) =
        dslContext
            .select(PARTICIPANTS.COHORT_ID, COHORTS.PHASE_ID)
            .from(PARTICIPANTS)
            .leftJoin(COHORTS)
            .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
            .where(PARTICIPANTS.ID.eq(event.participantId))
            .fetchOne()
            ?: throw IllegalStateException(
                "Unable to get cohort for participant ${event.participantId}"
            )

    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .set(COHORT_ID, cohortId)
          .set(PHASE_ID, phase)
          .where(ID.eq(event.projectId))
          .execute()
    }
  }

  @EventListener
  fun on(event: ParticipantProjectRemovedEvent) {
    with(PROJECTS) {
      dslContext
          .update(PROJECTS)
          .setNull(COHORT_ID)
          .setNull(PHASE_ID)
          .where(ID.eq(event.projectId))
          .execute()
    }
  }
}
