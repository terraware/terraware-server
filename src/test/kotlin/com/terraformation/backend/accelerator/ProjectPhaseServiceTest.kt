package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortParticipantRemovedEvent
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProjectPhaseServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val service: ProjectPhaseService by lazy { ProjectPhaseService(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
  }

  @Nested
  inner class OnCohortParticipantAddedEvent {
    @Test
    fun `updates cohort and phase for participant projects`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      val participantId = insertParticipant()
      val projectId1 = insertProject(participantId = participantId)
      val projectId2 = insertProject(participantId = participantId)

      insertParticipant()
      insertProject(participantId = inserted.participantId)
      insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId1 || record.id == projectId2) {
              record.cohortId = cohortId
              record.phaseId = CohortPhase.Phase0DueDiligence
            }
          }

      service.on(CohortParticipantAddedEvent(cohortId, participantId))

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class OnCohortParticipantRemovedEvent {
    @Test
    fun `clears cohort and phase for participant projects`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
      val participantId = insertParticipant()
      val projectId1 =
          insertProject(
              cohortId = cohortId,
              participantId = participantId,
              phase = CohortPhase.Phase1FeasibilityStudy,
          )
      val projectId2 =
          insertProject(
              cohortId = cohortId,
              participantId = participantId,
              phase = CohortPhase.Phase1FeasibilityStudy,
          )

      insertProject(participantId = insertParticipant())
      insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId1 || record.id == projectId2) {
              record.cohortId = null
              record.phaseId = null
            }
          }

      service.on(CohortParticipantRemovedEvent(cohortId, participantId))

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class OnCohortPhaseUpdatedEvent {
    @Test
    fun `updates phase for all projects in cohort`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase0DueDiligence)
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 =
          insertProject(
              participantId = participantId,
              cohortId = cohortId,
              phase = CohortPhase.Phase0DueDiligence,
          )
      val projectId2 =
          insertProject(
              participantId = participantId,
              cohortId = cohortId,
              phase = CohortPhase.Phase0DueDiligence,
          )

      insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
      insertParticipant(cohortId = inserted.cohortId)
      insertProject(
          participantId = inserted.participantId,
          cohortId = inserted.cohortId,
          phase = CohortPhase.Phase1FeasibilityStudy,
      )
      insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId1 || record.id == projectId2) {
              record.phaseId = CohortPhase.Phase3ImplementAndMonitor
            }
          }

      service.on(CohortPhaseUpdatedEvent(cohortId, CohortPhase.Phase3ImplementAndMonitor))

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class OnParticipantProjectAddedEvent {
    @Test
    fun `updates cohort and phase to participant values`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
      val participantId = insertParticipant(cohortId = cohortId)
      insertProject(
          cohortId = cohortId,
          participantId = participantId,
          phase = CohortPhase.Phase1FeasibilityStudy,
      )
      insertProject()

      val projectId = insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId) {
              record.cohortId = cohortId
              record.phaseId = CohortPhase.Phase1FeasibilityStudy
            }
          }

      service.on(ParticipantProjectAddedEvent(user.userId, participantId, projectId))

      assertTableEquals(expected)
    }
  }

  @Nested
  inner class OnParticipantProjectRemovedEvent {
    @Test
    fun `clears cohort and phase when project removed from participant`() {
      val cohortId = insertCohort(phase = CohortPhase.Phase2PlanAndScale)
      val participantId = insertParticipant()
      val projectId =
          insertProject(
              participantId = participantId,
              cohortId = cohortId,
              phase = CohortPhase.Phase2PlanAndScale,
          )

      insertProject()

      val expected =
          dslContext.fetch(PROJECTS).onEach { record ->
            if (record.id == projectId) {
              record.cohortId = null
              record.phaseId = null
            }
          }

      service.on(ParticipantProjectRemovedEvent(participantId, projectId, user.userId))

      assertTableEquals(expected)
    }
  }
}
