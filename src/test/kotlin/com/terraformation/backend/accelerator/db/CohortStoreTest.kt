package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.CohortModel
import com.terraformation.backend.accelerator.model.ExistingCohortModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.CohortPhase
import com.terraformation.backend.db.default_schema.tables.pojos.CohortsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class CohortStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: CohortStore by lazy { CohortStore(clock, dslContext, cohortsDao) }

  @BeforeEach
  fun setUp() {
    insertUser()

    every { user.canReadCohort(any()) } returns true
  }

  @Nested
  inner class Create {
    @Test
    fun `creates cohort`() {
      every { user.canCreateCohort() } returns true

      clock.instant = Instant.EPOCH.plusSeconds(500)

      val model = store.create(CohortModel.create("Cohort Test", CohortPhase.Phase0DueDiligence))

      assertEquals(
          listOf(
              CohortsRow(
                  id = model.id,
                  name = "Cohort Test",
                  phaseId = CohortPhase.Phase0DueDiligence,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          cohortsDao.findAll())
    }

    @Test
    fun `throws exception if no permission to create cohorts`() {
      assertThrows<AccessDeniedException> {
        store.create(CohortModel.create("Cohort Test", CohortPhase.Phase0DueDiligence))
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes empty cohort`() {
      val cohortIdToKeep = insertCohort()
      val cohortIdToDelete = insertCohort()

      every { user.canDeleteCohort(any()) } returns true

      store.delete(cohortIdToDelete)

      assertEquals(
          listOf(cohortIdToKeep), cohortsDao.findAll().map { it.id }, "Cohort IDs after delete")
    }

    @Test
    fun `throws exception and does not delete cohort if it has participants`() {
      val cohortId = insertCohort()
      insertOrganization()
      insertParticipant(cohortId = cohortId)

      every { user.canDeleteCohort(cohortId) } returns true

      assertThrows<CohortHasParticipantsException> { store.delete(cohortId) }
    }

    @Test
    fun `throws exception if cohort does not exist`() {
      every { user.canDeleteCohort(any()) } returns true

      assertThrows<CohortNotFoundException> { store.delete(CohortId(1)) }
    }

    @Test
    fun `throws exception if no permission to delete cohort`() {
      val cohortId = insertCohort()

      assertThrows<AccessDeniedException> { store.delete(cohortId) }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `includes or excludes list of participant IDs according to the requested depth`() {
      val cohortId = insertCohort(name = "Cohort Test", phase = CohortPhase.Phase0DueDiligence)

      insertOrganization()
      val participantId1 = insertParticipant(cohortId = cohortId)
      val participantId2 = insertParticipant(cohortId = cohortId)
      insertParticipant()

      // No depth, defaults to "cohort"
      assertEquals(
          ExistingCohortModel(
              id = cohortId,
              name = "Cohort Test",
              phase = CohortPhase.Phase0DueDiligence,
              participantIds = setOf(),
          ),
          store.fetchOneById(cohortId))

      assertEquals(
          ExistingCohortModel(
              id = cohortId,
              name = "Cohort Test",
              phase = CohortPhase.Phase0DueDiligence,
              participantIds = setOf(participantId1, participantId2),
          ),
          store.fetchOneById(cohortId, CohortDepth.Participant))
    }

    @Test
    fun `throws exception if no permission to read cohort`() {
      val cohortId = insertCohort()

      every { user.canReadCohort(cohortId) } returns false

      assertThrows<CohortNotFoundException> { store.fetchOneById(cohortId) }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `only includes cohorts the user has permission to read`() {
      val cohortId1 = insertCohort()
      val cohortId2 = insertCohort()
      val invisibleCohortId = insertCohort(name = "Not Visible")

      every { user.canReadCohort(invisibleCohortId) } returns false

      assertEquals(listOf(cohortId1, cohortId2), store.findAll().map { it.id }, "Cohort IDs")
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates editable fields`() {
      val otherUserId = insertUser(10)
      val cohortId =
          insertCohort(
              name = "Old Name", createdBy = otherUserId, phase = CohortPhase.Phase0DueDiligence)

      every { user.canUpdateCohort(cohortId) } returns true

      clock.instant = Instant.ofEpochSecond(1)

      store.update(cohortId) {
        it.copy(name = "New Name", phase = CohortPhase.Phase1FeasibilityStudy)
      }

      assertEquals(
          CohortsRow(
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              id = cohortId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "New Name",
              phaseId = CohortPhase.Phase1FeasibilityStudy),
          cohortsDao.fetchOneById(cohortId))
    }

    @Test
    fun `throws exception if cohort does not exist`() {
      every { user.canUpdateCohort(any()) } returns true

      assertThrows<CohortNotFoundException> { store.update(CohortId(1)) { it } }
    }

    @Test
    fun `throws exception if no permission to update cohort`() {
      val cohortId = insertCohort()

      assertThrows<AccessDeniedException> { store.update(cohortId) { it.copy(name = "New") } }
    }
  }
}
