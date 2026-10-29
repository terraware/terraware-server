package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.SavedTableViewModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SavedTableViewNameExistsException
import com.terraformation.backend.db.SavedTableViewNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.records.SavedTableViewsRecord
import com.terraformation.backend.mockUser
import io.mockk.every
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SavedTableViewStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: SavedTableViewStore by lazy { SavedTableViewStore(dslContext) }

  private val settings = JSONB.valueOf("""{"sort":{"field":"name","direction":"asc"}}""")

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { user.canReadOrganization(any()) } returns true
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns organization-specific view`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      assertEquals(
          SavedTableViewModel(
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
          ),
          store.fetchOneById(id),
      )
    }

    @Test
    fun `returns global view`() {
      val id = insertSavedTableView(null, "species", "My view", settings)

      assertEquals(
          SavedTableViewModel(
              id = id,
              name = "My view",
              organizationId = null,
              settings = settings,
              tableName = "species",
          ),
          store.fetchOneById(id),
      )
    }

    @Test
    fun `throws exception if view belongs to another user`() {
      val id = insertSavedTableView(userId = insertUser())

      assertThrows<SavedTableViewNotFoundException> { store.fetchOneById(id) }
    }

    @Test
    fun `throws exception if no permission to read view's organization`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<SavedTableViewNotFoundException> { store.fetchOneById(id) }
    }
  }

  @Nested
  inner class FetchByTableName {
    @Test
    fun `returns organization-specific views for table ordered by name`() {
      val otherOrganizationId = insertOrganization()

      val viewBId = insertSavedTableView(organizationId, "species", "B view", settings)
      val viewAId = insertSavedTableView(organizationId, "species", "A view", settings)
      insertSavedTableView(organizationId, "accessions", "Other table", settings)
      insertSavedTableView(otherOrganizationId, "species", "Other organization", settings)
      insertSavedTableView(null, "species", "Global", settings)
      insertSavedTableView(organizationId, "species", "Other user", userId = insertUser())

      assertEquals(
          listOf(
              SavedTableViewModel(viewAId, "A view", organizationId, settings, "species"),
              SavedTableViewModel(viewBId, "B view", organizationId, settings, "species"),
          ),
          store.fetchByTableName(organizationId, "species"),
      )
    }

    @Test
    fun `returns global views for table`() {
      val globalId1 = insertSavedTableView(null, "species", "Global1", settings)
      val globalId2 = insertSavedTableView(null, "species", "Global2", settings)
      insertSavedTableView(null, "accessions", "Other table", settings)
      insertSavedTableView(organizationId, "species", "Organization", settings)

      assertEquals(
          listOf(
              SavedTableViewModel(globalId1, "Global1", null, settings, "species"),
              SavedTableViewModel(globalId2, "Global2", null, settings, "species"),
          ),
          store.fetchByTableName(null, "species"),
      )
    }

    @Test
    fun `returns empty list if there are no views`() {
      assertEquals(emptyList<SavedTableViewModel>(), store.fetchByTableName(null, "species"))
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        store.fetchByTableName(organizationId, "species")
      }
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `creates organization-specific view`() {
      val id = store.create(organizationId, "species", "My view", settings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `creates global view`() {
      val id = store.create(null, "species", "My view", settings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = null,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `creates view with base view`() {
      val id = store.create(organizationId, "species", "My view", settings, "preset view")

      assertTableEquals(
          SavedTableViewsRecord(
              baseView = "preset view",
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `allows same name for different tables, organizations, and users`() {
      val otherOrganizationId = insertOrganization()
      val otherUserId = insertUser()

      store.create(organizationId, "species", "My view", settings)
      store.create(organizationId, "accessions", "My view", settings)
      store.create(otherOrganizationId, "species", "My view", settings)
      store.create(null, "species", "My view", settings)
      insertSavedTableView(organizationId, "species", "My view", settings, otherUserId)

      assertTableEquals(
          listOf(
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "accessions",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = otherOrganizationId,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = null,
                  settings = settings,
                  tableName = "species",
                  userId = user.userId,
              ),
              SavedTableViewsRecord(
                  name = "My view",
                  organizationId = organizationId,
                  settings = settings,
                  tableName = "species",
                  userId = otherUserId,
              ),
          )
      )
    }

    @Test
    fun `throws exception if name already exists for organization-specific view`() {
      insertSavedTableView(organizationId, "species", "My view")

      assertThrows<SavedTableViewNameExistsException> {
        store.create(organizationId, "species", "My view", settings)
      }
    }

    @Test
    fun `throws exception if name already exists for global view`() {
      insertSavedTableView(null, "species", "My view")

      assertThrows<SavedTableViewNameExistsException> {
        store.create(null, "species", "My view", settings)
      }
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        store.create(organizationId, "species", "My view", settings)
      }
    }
  }
}
