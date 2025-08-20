package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import java.time.ZoneId
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class DraftPlantingSiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: DraftPlantingSiteStore by lazy {
    DraftPlantingSiteStore(clock, dslContext, ParentStore(dslContext))
  }

  private val sampleData = JSONB.valueOf("{\"foo\":\"bar\"}")
  private val timeZone = ZoneId.of("Pacific/Honolulu")

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canCreateDraftPlantingSite(any()) } returns true
    every { user.canDeleteDraftPlantingSite(any()) } returns true
    every { user.canReadDraftPlantingSite(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateDraftPlantingSite(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns record`() {
      insertDraftPlantingSite(name = "Fetch test")

      val record = store.fetchOneById(inserted.draftPlantingSiteId)

      assertEquals("Fetch test", record.name, "Name")
    }

    @Test
    fun `throws exception if no permission to read draft`() {
      every { user.canReadDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<DraftPlantingSiteNotFoundException> {
        store.fetchOneById(inserted.draftPlantingSiteId)
      }
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates draft`() {
      insertProject()

      val expected =
          DraftPlantingSitesRecord(
              createdBy = user.userId,
              createdTime = clock.instant,
              data = sampleData,
              description = "Description",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "Name",
              numPlantingSubzones = 2,
              numPlantingZones = 1,
              organizationId = organizationId,
              projectId = inserted.projectId,
              timeZone = timeZone,
          )

      val actual =
          store.create(
              data = sampleData,
              name = "Name",
              organizationId = organizationId,
              description = "Description",
              numPlantingSubzones = 2,
              numPlantingZones = 1,
              projectId = inserted.projectId,
              timeZone = timeZone,
          )

      assertEquals(expected, actual.copy())
    }

    @Test
    fun `throws exception if no permission to create drafts`() {
      every { user.canCreateDraftPlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.create(JSONB.valueOf("{}"), "Name", organizationId)
      }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertOrganization()
      insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.create(JSONB.valueOf("{}"), "Name", organizationId, projectId = inserted.projectId)
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes correct draft`() {
      val otherDraftId1 = insertDraftPlantingSite()
      val draftIdToDelete = insertDraftPlantingSite()
      val otherDraftId2 = insertDraftPlantingSite()

      store.delete(draftIdToDelete)

      assertEquals(
          setOf(otherDraftId1, otherDraftId2),
          draftPlantingSitesDao.findAll().map { it.id }.toSet(),
          "Remaining IDs after deletion",
      )
    }

    @Test
    fun `throws exception if no permission to delete drafts`() {
      every { user.canDeleteDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<AccessDeniedException> { store.delete(inserted.draftPlantingSiteId) }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates draft`() {
      insertProject()
      insertDraftPlantingSite()

      clock.instant = Instant.EPOCH.plusSeconds(30)

      val expected =
          DraftPlantingSitesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              data = sampleData,
              description = "Description",
              id = inserted.draftPlantingSiteId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "New name",
              numPlantingSubzones = 2,
              numPlantingZones = 1,
              organizationId = organizationId,
              projectId = inserted.projectId,
              timeZone = timeZone,
          )

      store.update(inserted.draftPlantingSiteId) {
        it.data = sampleData
        it.description = "Description"
        it.name = "New name"
        it.numPlantingSubzones = 2
        it.numPlantingZones = 1
        it.projectId = inserted.projectId
        it.timeZone = timeZone
      }

      val actual = store.fetchOneById(inserted.draftPlantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to update drafts`() {
      every { user.canUpdateDraftPlantingSite(any()) } returns false

      insertDraftPlantingSite()

      assertThrows<AccessDeniedException> { store.update(inserted.draftPlantingSiteId) {} }
    }

    @Test
    fun `throws exception if callback attempts to change read-only field`() {
      insertDraftPlantingSite()
      val otherOrgId = insertOrganization()

      assertThrows<AccessDeniedException> {
        store.update(inserted.draftPlantingSiteId) { it.organizationId = otherOrgId }
      }
    }

    @Test
    fun `throws exception if project is in wrong organization`() {
      insertDraftPlantingSite()

      val otherOrgId = insertOrganization()
      insertProject(organizationId = otherOrgId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.update(inserted.draftPlantingSiteId) { it.projectId = inserted.projectId }
      }
    }
  }
}
