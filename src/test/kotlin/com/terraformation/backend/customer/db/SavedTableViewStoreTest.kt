package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.SavedTableViewModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SavedTableViewNameExistsException
import com.terraformation.backend.db.SavedTableViewNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SavedTableViewId
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
      val id =
          insertSavedTableView(
              organizationId,
              "species",
              "My view",
              settings,
              baseView = "preset view",
          )

      assertEquals(
          SavedTableViewModel(
              baseView = "preset view",
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
              baseView = null,
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
      val viewAId =
          insertSavedTableView(
              organizationId,
              "species",
              "A view",
              settings,
              baseView = "preset view",
          )
      insertSavedTableView(organizationId, "accessions", "Other table", settings)
      insertSavedTableView(otherOrganizationId, "species", "Other organization", settings)
      insertSavedTableView(null, "species", "Global", settings)
      insertSavedTableView(organizationId, "species", "Other user", userId = insertUser())

      assertEquals(
          listOf(
              SavedTableViewModel(
                  "preset view",
                  viewAId,
                  "A view",
                  organizationId,
                  settings,
                  "species",
              ),
              SavedTableViewModel(null, viewBId, "B view", organizationId, settings, "species"),
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
              SavedTableViewModel(null, globalId1, "Global1", null, settings, "species"),
              SavedTableViewModel(null, globalId2, "Global2", null, settings, "species"),
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

  @Nested
  inner class Update {
    private val newSettings = JSONB.valueOf("""{"columns":["name"]}""")

    @Test
    fun `updates name without changing settings`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      store.update(id, "New name", null)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "New name",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `updates settings without changing name`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      store.update(id, null, newSettings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = newSettings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `updates name and settings`() {
      val id = insertSavedTableView(null, "species", "My view", settings)

      store.update(id, "New name", newSettings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "New name",
              organizationId = null,
              settings = newSettings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `allows updating settings when name is unchanged`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      store.update(id, "My view", newSettings)

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = organizationId,
              settings = newSettings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `throws exception if new name already exists`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)
      insertSavedTableView(organizationId, "species", "Other view", settings)

      assertThrows<SavedTableViewNameExistsException> { store.update(id, "Other view", null) }
    }

    @Test
    fun `throws exception if neither name nor settings is specified`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      assertThrows<IllegalArgumentException> { store.update(id, null, null) }
    }

    @Test
    fun `throws exception if view does not exist`() {
      assertThrows<SavedTableViewNotFoundException> {
        store.update(SavedTableViewId(-1), "New name", null)
      }
    }

    @Test
    fun `throws exception if view belongs to another user`() {
      val otherUserId = insertUser()
      val id = insertSavedTableView(null, "species", "My view", settings, otherUserId)

      assertThrows<SavedTableViewNotFoundException> { store.update(id, "New name", null) }

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = null,
              settings = settings,
              tableName = "species",
              userId = otherUserId,
          )
      )
    }

    @Test
    fun `throws exception if no permission to read view's organization`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<SavedTableViewNotFoundException> { store.update(id, "New name", null) }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes view`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)
      val otherId = insertSavedTableView(organizationId, "species", "Other view", settings)

      store.delete(id)

      assertTableEquals(
          SavedTableViewsRecord(
              id = otherId,
              name = "Other view",
              organizationId = organizationId,
              settings = settings,
              tableName = "species",
              userId = user.userId,
          )
      )
    }

    @Test
    fun `throws exception if view does not exist`() {
      assertThrows<SavedTableViewNotFoundException> { store.delete(SavedTableViewId(-1)) }
    }

    @Test
    fun `throws exception if view belongs to another user`() {
      val otherUserId = insertUser()
      val id = insertSavedTableView(null, "species", "My view", settings, otherUserId)

      assertThrows<SavedTableViewNotFoundException> { store.delete(id) }

      assertTableEquals(
          SavedTableViewsRecord(
              id = id,
              name = "My view",
              organizationId = null,
              settings = settings,
              tableName = "species",
              userId = otherUserId,
          )
      )
    }

    @Test
    fun `throws exception if no permission to read view's organization`() {
      val id = insertSavedTableView(organizationId, "species", "My view", settings)

      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<SavedTableViewNotFoundException> { store.delete(id) }
    }
  }
}
